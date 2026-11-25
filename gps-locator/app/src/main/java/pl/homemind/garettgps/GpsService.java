package pl.homemind.garettgps;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import java.util.*;

public final class GpsService extends Service implements LocationListener {
    static volatile boolean active=false;
    static volatile String state="GPS OFF";
    static volatile float accuracy=Float.NaN;
    static volatile long started=0,firstFix=0,limitMs=900000,lastDuration=0;
    static volatile int raw=0,rejected=0,peakVisible=0,peakUsed=0,signalCount=0,peakSignalCount=0;
    static volatile float strongest=0,peakStrongest=0;
    static volatile long gnssUpdates=0;
    static volatile String lastDiagnostic="Brak pomiaru", networkStatus="Nie testowano";
    static volatile int networkRaw=0;
    static volatile int visible=0,used=0, samples=0;
    static volatile Sat[] sats=new Sat[0];
    static final class Sat {
        final int id,type; final float az,el,cn; final boolean used;
        Sat(GnssStatus g,int i) {id=g.getSvid(i);type=g.getConstellationType(i);az=g.getAzimuthDegrees(i);el=g.getElevationDegrees(i);cn=g.getCn0DbHz(i);used=g.usedInFix(i);}
    }
    private final Handler h=new Handler(Looper.getMainLooper());
    private final LinkedHashSet<String> senders=new LinkedHashSet<>();
    private LocationManager lm;
    private PowerManager.WakeLock lock;
    private Stabilizer st;
    private boolean fast,manual,closing,gpsRequested,networkRequested;
    private Location networkFix;
    private final Map<String,Location> preliminary=new HashMap<>();
    private final Runnable timeout=()->finish(bestAvailable(),"TIMEOUT");
    private final Runnable coarseCheck=()->sendPreliminary();
    private final Runnable tick=new Runnable(){public void run(){
        if(!active || closing)return;
        Store.log(GpsService.this,"GPS "+((SystemClock.elapsedRealtime()-started)/1000)+"s: sat "+visible+"/"+used+", sygnal "+signalCount+", max "+Math.round(strongest)+" dBHz, odczyty "+raw+", odrzucone "+rejected+(st==null?"":", "+st.reason));
        h.postDelayed(this,30000);
    }};
    private final GnssStatus.Callback gnss=new GnssStatus.Callback() {
        @Override public void onSatelliteStatusChanged(GnssStatus g) {
            Sat[] a=new Sat[g.getSatelliteCount()];int u=0, signals=0;float max=0;
            for(int i=0;i<a.length;i++){a[i]=new Sat(g,i);if(a[i].used)u++;if(Float.isFinite(a[i].cn)&&a[i].cn>0){signals++;max=Math.max(max,a[i].cn);}}
            gnssUpdates++;visible=a.length;used=u;sats=a;signalCount=signals;strongest=max;
            peakVisible=Math.max(peakVisible,visible);peakUsed=Math.max(peakUsed,used);peakSignalCount=Math.max(peakSignalCount,signals);peakStrongest=Math.max(peakStrongest,max);
        }
    };
    @Override public IBinder onBind(Intent i){return null;}
    @Override public int onStartCommand(Intent i,int flags,int id) {
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("gps","Pomiar GPS",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        startForeground(7,new Notification.Builder(this,"gps").setSmallIcon(android.R.drawable.ic_menu_mylocation)
          .setContentTitle("Garett GPS").setContentText("Pomiar lokalizacji").setContentIntent(open).setOngoing(true).build());
        if(i==null){stopSelf();return START_NOT_STICKY;}
        String cmd=i.getStringExtra("cmd"),sender=i.getStringExtra("sender");
        boolean local=sender==null;
        if(!local && !Store.allowed(this,sender)){if(!active)stopSelf();return START_NOT_STICKY;}
        if("GPS STOP".equals(cmd)) {
            if(local){manual=false;if(senders.isEmpty())finish(null,"ANULOWANO");}
            else {senders.add(sender);finish(null,"ANULOWANO");}
            return START_NOT_STICKY;
        }
        if(!local) {if(senders.size()>=8 && !senders.contains(sender)){SmsReceiver.reply(this,sender,"GPS: zbyt wiele zapytan; sprobuj pozniej");return START_NOT_STICKY;} senders.add(sender);} else manual=true;
        if(active) {if(!"GPS FAST".equals(cmd))fast=false;sendPreliminary();return START_NOT_STICKY;}
        fast="GPS FAST".equals(cmd);
        active=true;closing=false;state="SZUKANIE";accuracy=Float.NaN;started=SystemClock.elapsedRealtime();
        firstFix=0;visible=used=samples=raw=rejected=peakVisible=peakUsed=signalCount=peakSignalCount=0;strongest=peakStrongest=0;gnssUpdates=0;sats=new Sat[0];st=new Stabilizer();
        limitMs=Math.max(300000L,Math.min(1200000L,Store.prefs(this).getInt("timeoutMinutesV2",15)*60000L));
        gpsRequested=networkRequested=false;networkFix=null;networkRaw=0;networkStatus="Sprawdzanie";preliminary.clear();
        Store.log(this,"Start GPS + NETWORK, limit "+limitMs/1000+"s");
        try {
            if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) throw new SecurityException();
            lm=(LocationManager)getSystemService(LOCATION_SERVICE);
            Store.log(this,"Dostawcy: "+lm.getAllProviders());
            lock=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"GarettGPS:session");
            lock.setReferenceCounted(false);lock.acquire(limitMs+15000L);
            h.postDelayed(timeout,limitMs);h.postDelayed(tick,30000L);
            try{
                if(lm.getAllProviders().contains(LocationManager.NETWORK_PROVIDER) && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)){
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,15000,0,this,Looper.getMainLooper());
                    networkRequested=true;networkStatus="Oczekiwanie";
                }else networkStatus=lm.getAllProviders().contains(LocationManager.NETWORK_PROVIDER)?"Wylaczony":"Niedostepny";
            }catch(RuntimeException e){networkStatus="Blad "+e.getClass().getSimpleName();}
            Store.log(this,"NETWORK: "+networkStatus);
            try{
                if(lm.isProviderEnabled(LocationManager.GPS_PROVIDER)){
                    try{Store.log(this,"GNSS callback: "+lm.registerGnssStatusCallback(gnss,h));}catch(RuntimeException e){Store.log(this,"Brak diagnostyki GNSS");}
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,this,Looper.getMainLooper());gpsRequested=true;
                }
            }catch(RuntimeException e){Store.log(this,"GPS blad: "+e.getClass().getSimpleName());}
            if(!gpsRequested&&!networkRequested){finish(null,"Brak dostawcy");return START_NOT_STICKY;}
            if(!gpsRequested){state="TYLKO NETWORK";h.removeCallbacks(timeout);limitMs=60000;h.postDelayed(timeout,limitMs);}
            h.postDelayed(coarseCheck,30000L);
        } catch(RuntimeException e){finish(null,"BLAD "+e.getClass().getSimpleName());}
        return START_NOT_STICKY;
    }
    @Override public void onLocationChanged(Location l) {
        if(!active || closing || st==null)return;
        if(LocationManager.NETWORK_PROVIDER.equals(l.getProvider())){acceptNetwork(l);return;}
        if(!LocationManager.GPS_PROVIDER.equals(l.getProvider()))return;
        raw++;
        if(!st.accept(l)){rejected++;if(rejected<=3)Store.log(this,"Odrzucony odczyt: "+st.reason);return;}
        accuracy=l.getAccuracy();samples=st.window.size();state="STABILIZACJA";
        long elapsed=SystemClock.elapsedRealtime()-started;
        if(firstFix==0){firstFix=elapsed;Store.log(this,"Pierwszy fix: "+elapsed/1000+" s");}
        boolean stable=st.stable(10) || (elapsed>=30000 && st.stable(20)) || (elapsed>=90000 && st.stable(35));
        if((fast && l.getAccuracy()<=20) || stable)finish(l,l.getAccuracy()<=20?"OK":"SLABY");
    }
    private boolean validNetwork(Location l){
        if(l==null || !l.hasAccuracy() || !Float.isFinite(l.getAccuracy()) || l.getAccuracy()<=0 || l.isFromMockProvider() ||
          !Double.isFinite(l.getLatitude()) || !Double.isFinite(l.getLongitude()) || Math.abs(l.getLatitude())>90 || Math.abs(l.getLongitude())>180)return false;
        long stamp=l.getElapsedRealtimeNanos()/1000000L,age=SystemClock.elapsedRealtime()-stamp;
        return stamp>=started && age>=-1000 && age<=30000;
    }
    private Location freshNetwork(){return validNetwork(networkFix)?networkFix:null;}
    private Location bestAvailable(){
        Location gps=st==null?null:st.recentBest(),net=freshNetwork();
        return gps==null?net:net!=null && net.getAccuracy()<gps.getAccuracy()?net:gps;
    }
    private void acceptNetwork(Location l){
        networkRaw++;
        if(!validNetwork(l)){networkStatus="Odczyt nieaktualny";return;}
        boolean first=networkFix==null;
        networkFix=new Location(l);networkStatus="OK +/-"+Math.round(l.getAccuracy())+"m";
        if(first)Store.log(this,"NETWORK pierwszy odczyt: "+networkStatus);
        if(!gpsRequested){finish(networkFix,"PRZYBLIZONA");return;}
        sendPreliminary();
    }
    private String locationMessage(Location l,String why){
        boolean net=LocationManager.NETWORK_PROVIDER.equals(l.getProvider());
        long age=Math.max(0,(SystemClock.elapsedRealtime()-l.getElapsedRealtimeNanos()/1000000L)/1000);
        return String.format(Locale.US,"%s %s +/- %dm, wiek %ds, bat %d%%\nhttps://maps.google.com/?q=%.6f,%.6f",
          net?"WiFi/LBS":"GPS",why,Math.round(l.getAccuracy()),age,Store.battery(this),l.getLatitude(),l.getLongitude());
    }
    private void sendPreliminary(){
        if(!active||closing||SystemClock.elapsedRealtime()-started<30000)return;
        Location l=freshNetwork();if(l==null)return;
        Location gps=st==null?null:st.recentBest();
        if(gps!=null&&gps.getAccuracy()<=l.getAccuracy())return;
        for(String n:senders)if(!preliminary.containsKey(n)&&Store.allowed(this,n)){
            SmsReceiver.reply(this,n,locationMessage(l,"PRZYBLIZONA"));
            preliminary.put(n,new Location(l));Store.log(this,"Wyslano przyblizona pozycje NETWORK");
        }
        Store.save(this,l);
    }
    @Override public void onProviderDisabled(String p){
        if(!active)return;
        if(LocationManager.NETWORK_PROVIDER.equals(p)){networkRequested=false;networkStatus="Wylaczony";}
        if(LocationManager.GPS_PROVIDER.equals(p)){gpsRequested=false;state="TYLKO NETWORK";}
        if(!gpsRequested&&!networkRequested)finish(bestAvailable(),"DOSTAWCY OFF");
        else if(!gpsRequested){Location l=freshNetwork();if(l!=null)finish(l,"PRZYBLIZONA");}
    }
    @Override public void onProviderEnabled(String p){}
    @Override public void onStatusChanged(String p,int status,Bundle extras){}
    private void finish(Location l,String why) {
        if(closing)return;closing=true;
        lastDuration=active?SystemClock.elapsedRealtime()-started:0;
        lastDiagnostic="GPS "+lastDuration/1000+"s; sat max "+peakVisible+"/"+peakUsed+"; sygnal max "+peakSignalCount+"/"+Math.round(peakStrongest)+"dBHz; odczyty "+raw+"; odrz. "+rejected+"; NET "+networkStatus+" ("+networkRaw+")";
        Store.prefs(this).edit().putString("lastDiagnostic",lastDiagnostic).apply();
        String msg;
        if(l!=null) {
            Store.save(this,l);
            msg=locationMessage(l,why);
            state=why+" +/-"+Math.round(l.getAccuracy())+"m";
        } else {msg="GPS: "+why+"; brak swiezej pozycji. "+lastDiagnostic;state=why;}
        cleanup();
        for(String n:senders){
            Location prior=preliminary.get(n);
            if(prior==null || "ANULOWANO".equals(why) || (l!=null && l.getAccuracy()<prior.getAccuracy()*0.8f))SmsReceiver.reply(this,n,msg);
        }
        preliminary.clear();
        senders.clear();manual=false;
        Store.log(this,"Koniec: "+state+"; "+lastDiagnostic);
        stopForeground(true);stopSelf();
    }
    private void cleanup(){
        h.removeCallbacksAndMessages(null);
        if(lm!=null){try{lm.removeUpdates(this);}catch(RuntimeException ignored){}try{lm.unregisterGnssStatusCallback(gnss);}catch(RuntimeException ignored){}lm=null;}
        if(lock!=null){if(lock.isHeld())lock.release();lock=null;}
        active=false;
    }
    @Override public void onDestroy(){cleanup();stopForeground(true);super.onDestroy();}
}
