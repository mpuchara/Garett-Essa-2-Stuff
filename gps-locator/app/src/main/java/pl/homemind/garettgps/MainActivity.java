package pl.homemind.garettgps;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

public final class MainActivity extends Activity {
    final Handler h=new Handler(Looper.getMainLooper());
    LinearLayout root; TextView title,summary; Graph graph; int page=0;
    float down; boolean manual=false;
    final int bg=0xff08111d, cyan=0xff27d9ee, light=0xffe8f0ff, muted=0xff9aacbd;
    final Runnable refresh=new Runnable(){public void run(){update();h.postDelayed(this,1000);}};
    @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(bg);show(0);}
    @Override public void onResume(){super.onResume();h.post(refresh);}
    @Override public void onPause(){h.removeCallbacks(refresh);super.onPause();}
    @Override public void onStop(){if(manual){command("GPS STOP");manual=false;}super.onStop();}
    boolean permissions(){for(String p:new String[]{Manifest.permission.RECEIVE_SMS,Manifest.permission.SEND_SMS,Manifest.permission.ACCESS_FINE_LOCATION})if(checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)return false;return true;}
    void ask(){requestPermissions(new String[]{Manifest.permission.RECEIVE_SMS,Manifest.permission.SEND_SMS,Manifest.permission.ACCESS_FINE_LOCATION},1);}
    void command(String c){try{startForegroundService(new Intent(this,GpsService.class).putExtra("cmd",c));}catch(RuntimeException e){Toast.makeText(this,"Nie mozna uruchomic GPS",Toast.LENGTH_SHORT).show();}}
    int dp(float d){return Math.round(getResources().getDisplayMetrics().density*d);}
    TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER);return t;}
    Button button(String s,Runnable r){Button b=new Button(this);b.setText(s);b.setTextSize(12);b.setAllCaps(false);b.setOnClickListener(v->r.run());return b;}
    void show(int p){
        page=p;root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(6),dp(4),dp(6),dp(4));root.setBackgroundColor(bg);setContentView(root);
        title=text(new String[]{"GARETT GPS","SYGNAL GNSS","NIEBO","HISTORIA"}[p],14,cyan);root.addView(title);
        summary=text("",15,light);root.addView(summary);
        graph=null;
        if(p==0){
            root.addView(button("Sprawdz GPS",()->{if(!permissions()){ask();return;}manual=true;command("GPS");show(1);}));
            root.addView(button("Ustawienia / SMS",this::settings));
            root.addView(button("Zatrzymaj test",()->{manual=false;command("GPS STOP");}));
        }else if(p==1||p==2){
            graph=new Graph(p==2);root.addView(graph,new LinearLayout.LayoutParams(-1,0,1));
        }else {
            ScrollView sc=new ScrollView(this);TextView logs=text(Store.prefs(this).getString("log","Brak zdarzen"),11,muted);logs.setGravity(Gravity.START);sc.addView(logs);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
            root.addView(button("Odswiez",()->show(3)));
        }
        if(p==0)root.addView(new View(this),new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout nav=new LinearLayout(this);
        String[] names={"GPS","Syg.","Niebo","Log"};
        for(int j=0;j<4;j++){final int k=j;Button b=button(names[j],()->show(k));b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(0,0,0,0);nav.addView(b,new LinearLayout.LayoutParams(0,dp(36),1));}
        root.addView(nav);update();
    }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent e){
        if(e.getAction()==MotionEvent.ACTION_DOWN)down=e.getX();
        if(e.getAction()==MotionEvent.ACTION_UP && Math.abs(e.getX()-down)>dp(70) && page<4){
            show((page+(e.getX()<down?1:3))%4);return true;
        }return super.dispatchTouchEvent(e);
    }
    void update(){
        if(summary==null)return;
        boolean enabled=Store.prefs(this).getBoolean("enabled",false);
        String acc=Float.isNaN(GpsService.accuracy)?"--":Math.round(GpsService.accuracy)+" m";
        long sec=GpsService.active?(SystemClock.elapsedRealtime()-GpsService.started)/1000:0;
        if(page==0)summary.setText((enabled&&permissions()?"SMS: GOTOWY":"SMS: skonfiguruj")+"\n"+GpsService.state+"\nBateria "+Store.battery(this)+"%");
        else if(page<3)summary.setText((GpsService.active?GpsService.state:"GPS OFF")+"  "+acc+"\nWid. "+GpsService.visible+" / uzyw. "+GpsService.used+"\n"+sec+"s | fix "+(GpsService.firstFix==0?"--":GpsService.firstFix/1000+"s")+" | "+GpsService.samples+"/5");
        else summary.setText("Zdarzenia lokalne");
        if(graph!=null)graph.invalidate();
    }
    void settings(){
        LinearLayout form=new LinearLayout(this);form.setOrientation(1);form.setPadding(dp(12),0,dp(12),0);
        EditText nums=new EditText(this);nums.setHint("+48..., +48...");nums.setText(Store.prefs(this).getString("numbers",""));nums.setInputType(android.text.InputType.TYPE_CLASS_TEXT);form.addView(text("Zaufane numery (przecinek)",12,light));form.addView(nums);
        EditText pin=new EditText(this);pin.setHint("PIN opcjonalny");pin.setInputType(18);pin.setText(Store.prefs(this).getString("pin",""));form.addView(pin);
        CheckBox en=new CheckBox(this);en.setText("Udostepniaj GPS przez SMS");en.setChecked(Store.prefs(this).getBoolean("enabled",false));form.addView(en);
        ScrollView sc=new ScrollView(this);sc.addView(form);
        AlertDialog d=new AlertDialog.Builder(this).setTitle("Ustawienia").setView(sc).setNegativeButton("Anuluj",null).setNeutralButton("Uprawnienia",(a,b)->ask()).setPositiveButton("Zapisz",null).create();
        d.setOnShowListener(a->d.getButton(-1).setOnClickListener(v->{
            LinkedHashSet<String> valid=new LinkedHashSet<>();
            for(String s:nums.getText().toString().split("[,;\\s]+")){if(s.isEmpty())continue;String n=Store.number(s);if(n.isEmpty()){nums.setError("Nieprawidlowy numer");return;}valid.add(n);}
            String code=pin.getText().toString().trim();
            if(!code.isEmpty()&&!code.matches("[0-9]{4,12}")){pin.setError("4-12 cyfr");return;}
            if(en.isChecked()&&valid.isEmpty()){nums.setError("Dodaj zaufany numer");return;}
            Store.prefs(this).edit().putString("numbers",android.text.TextUtils.join(",",valid)).putString("pin",code).putBoolean("enabled",en.isChecked()).apply();d.dismiss();if(en.isChecked()&&!permissions())ask();update();
        }));d.show();
    }
    final class Graph extends View {
        final boolean sky; final Paint paint=new Paint(3);
        Graph(boolean s){super(MainActivity.this);sky=s;}
        void color(int c){paint.setColor(c);paint.setStyle(Paint.Style.FILL);}
        int signal(float n){return n<10?0xfff24444:n<20?0xffff9417:n<30?0xfff8e72a:n<40?0xffa7ed3c:0xff23c96b;}
        void label(Canvas c,String s,float x,float y,float size){color(light);paint.setTextSize(size);paint.setTextAlign(Paint.Align.CENTER);c.drawText(s,x,y,paint);}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);float w=getWidth(),height=getHeight();GpsService.Sat[] a=GpsService.active?GpsService.sats:new GpsService.Sat[0];
            if(a.length==0){label(c,"Brak danych GNSS",w/2,height/2,dp(12));return;}
            if(sky){
                float r=Math.min(w,height)/2-dp(12),x=w/2,y=height/2;
                if(r<=0)return;
                paint.setColor(0xff33485c);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);
                for(int i=1;i<=3;i++)c.drawCircle(x,y,r*i/3,paint);
                c.drawLine(x-r,y,x+r,y,paint);c.drawLine(x,y-r,x,y+r,paint);
                label(c,"N",x,y-r-dp(2),dp(10));label(c,"S",x,y+r+dp(9),dp(10));
                label(c,"E",x+r+dp(6),y,dp(10));label(c,"W",x-r-dp(6),y,dp(10));
                for(GpsService.Sat s:a){
                    double az=Math.toRadians(s.az);float d=r*(90-Math.max(0,Math.min(90,s.el)))/90;
                    float px=x+(float)Math.sin(az)*d,py=y-(float)Math.cos(az)*d;
                    color(signal(s.cn));paint.setStyle(s.used?Paint.Style.FILL:Paint.Style.STROKE);paint.setStrokeWidth(dp(1));
                    float z=dp(4);
                    if(s.type==1)c.drawCircle(px,py,z,paint);
                    else if(s.type==3)c.drawRect(px-z,py-z,px+z,py+z,paint);
                    else {Path path=new Path();path.moveTo(px,py-z);path.lineTo(px+z,py+z);path.lineTo(px-z,py+z);path.close();c.drawPath(path,paint);}
                }
                label(c,"GPS o  GLO []  inne ^",w/2,height-dp(1),dp(9));
            }else{
                Arrays.sort(a=a.clone(),(s,t)->Float.compare(t.cn,s.cn));
                int n=Math.min(20,a.length);float step=w/n,base=height-dp(26),top=dp(8);
                if(base<=top)return;
                float avg=0;for(GpsService.Sat s:a)avg+=s.cn;
                for(int i=0;i<n;i++){GpsService.Sat s=a[i];float bar=Math.max(0,Math.min(1,s.cn/50))*Math.max(0,base-top);color(signal(s.cn));paint.setAlpha(s.used?255:105);
                    c.drawRect(i*step+1,base-bar,(i+1)*step-1,base,paint);paint.setAlpha(255);label(c,""+s.id,(i+.5f)*step,base+dp(10),Math.min(step,dp(9)));}
                label(c,String.format(Locale.US,"C/N0 %.1f dB-Hz",avg/a.length),w/2,height-dp(2),dp(11));
            }
        }
    }
}
