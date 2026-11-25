package pl.homemind.garettgps;

import android.content.*;
import android.provider.Telephony;
import android.telephony.*;
import android.app.*;
import android.os.SystemClock;
import java.util.*;

public final class SmsReceiver extends BroadcastReceiver {
    private static final Map<String,Long> recent=new HashMap<>();
    @Override public void onReceive(Context c, Intent i) {
        if(!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(i.getAction())) return;
        SmsMessage[] parts=Telephony.Sms.Intents.getMessagesFromIntent(i);
        if(parts==null || parts.length==0 || parts[0]==null) return;
        String sender=Store.number(parts[0].getOriginatingAddress());
        if(!Store.allowed(c,sender)) return;
        StringBuilder body=new StringBuilder();
        for(SmsMessage m:parts) {
            if(m==null || !sender.equals(Store.number(m.getOriginatingAddress()))) return;
            body.append(m.getMessageBody());
        }
        String cmd=body.toString().trim().replaceAll("\\s+"," ").toUpperCase(Locale.ROOT);
        if(!Arrays.asList("GPS","GPS FAST","GPS STATUS","GPS STOP").contains(cmd)) return;
        long now=SystemClock.elapsedRealtime();
        String key=sender+":"+cmd;
        synchronized(recent) {
            recent.entrySet().removeIf(e->now-e.getValue()>120000);
            Long prev=recent.get(key);
            if(prev!=null && now-prev<15000 && !cmd.equals("GPS STOP")) return;
            recent.put(key,now);
        }
        Store.prefs(c).edit().putLong("lastSmsCommand",System.currentTimeMillis()).apply();
        Store.log(c,"SMS: "+cmd);
        if(cmd.equals("GPS STATUS")) {
            reply(c,sender,"GPS "+(GpsService.active?"POMIAR":"GOTOWY")+"; bateria "+Store.battery(c)+"%; "+(GpsService.active?("sat "+GpsService.visible+"/"+GpsService.used+"; max "+Math.round(GpsService.strongest)+"dBHz; odczyty "+GpsService.raw+"; NET "+GpsService.networkStatus):Store.prefs(c).getString("lastDiagnostic","brak pomiaru")));
            return;
        }
        if(cmd.equals("GPS STOP") && !GpsService.active) { reply(c,sender,"GPS: brak aktywnego pomiaru"); return; }
        try {
            c.startForegroundService(new Intent(c,GpsService.class).putExtra("sender",sender).putExtra("cmd",cmd));
        } catch(RuntimeException e) { Store.log(c,"Nie mozna uruchomic GPS"); reply(c,sender,"GPS: blad uruchomienia"); }
    }
    static boolean reply(Context c,String n,String body) {
        if(!Store.allowed(c,n)) return false;
        try {
            SmsManager sm=SmsManager.getDefault();
            ArrayList<String> segments=sm.divideMessage(body);
            // Do not retry automatically: avoid duplicate messages and charges.
            ArrayList<PendingIntent> sent=new ArrayList<>();
            for(int j=0;j<segments.size();j++) {
                Intent report=new Intent(c, SmsReport.class).putExtra("segment",j+1);
                sent.add(PendingIntent.getBroadcast(c,(int)(System.nanoTime()&0x7fffffff),report,PendingIntent.FLAG_ONE_SHOT|PendingIntent.FLAG_IMMUTABLE));
            }
            if(segments.size()==1) sm.sendTextMessage(n,null,body,sent.get(0),null);
            else sm.sendMultipartTextMessage(n,null,segments,sent,null);
            Store.log(c,"SMS przekazany do modemu");return true;
        } catch(RuntimeException e) { Store.log(c,"Blad SMS: "+e.getClass().getSimpleName());return false; }
    }
}
