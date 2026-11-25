package pl.homemind.garettgps;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.PowerManager;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){
        String action=i.getAction();
        if(!Intent.ACTION_BOOT_COMPLETED.equals(action)&&!Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))return;
        boolean ready=Store.smsReady(c)&&permissions(c);
        Store.prefs(c).edit().putLong("lastSystemStart",System.currentTimeMillis()).putBoolean("readyAtSystemStart",ready).apply();
        Store.log(c,(Intent.ACTION_BOOT_COMPLETED.equals(action)?"Restart zegarka":"Aktualizacja aplikacji")+": SMS "+(ready?"GOTOWY":"wymaga konfiguracji")+", GPS na zadanie");
        // SMS receiver is declared in the manifest. No idle service, alarm or GPS request is needed.
    }
    static boolean permissions(Context c){
        for(String p:new String[]{Manifest.permission.RECEIVE_SMS,Manifest.permission.SEND_SMS,Manifest.permission.ACCESS_FINE_LOCATION})
            if(c.checkSelfPermission(p)!=PackageManager.PERMISSION_GRANTED)return false;
        return true;
    }
    static boolean unrestricted(Context c){
        PowerManager p=(PowerManager)c.getSystemService(Context.POWER_SERVICE);
        return p!=null&&p.isIgnoringBatteryOptimizations(c.getPackageName());
    }
}
