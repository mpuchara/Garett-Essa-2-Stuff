package pl.homemind.garettgps;

import android.content.*;
import android.os.*;
import android.location.Location;
import android.telephony.PhoneNumberUtils;
import java.util.*;

final class Store {
    static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences("gps",0); }
    static String number(String n) {
        String s=PhoneNumberUtils.normalizeNumber(n == null ? "" : n);
        if(s.startsWith("00")) s="+"+s.substring(2);
        if(s.matches("[0-9]{9}")) s="+48"+s;
        if(s.matches("48[0-9]{9}")) s="+"+s;
        return s.matches("\\+[1-9][0-9]{6,14}") ? s : "";
    }
    static boolean allowed(Context c,String n) {
        String s=number(n);
        return !s.isEmpty() && smsReady(c);
    }
    static boolean smsReady(Context c) {
        return prefs(c).getBoolean("enabled",false) && prefs(c).getString("pin","").matches("[0-9]{6,12}");
    }
    static synchronized void log(Context c,String s) {
        String line=new java.text.SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date())+" "+s+"\n";
        String old=prefs(c).getString("log","");
        String out=line+old;
        prefs(c).edit().putString("log",out.substring(0,Math.min(4500,out.length()))).apply();
    }
    static int battery(Context c) {
        android.os.BatteryManager m=(android.os.BatteryManager)c.getSystemService(Context.BATTERY_SERVICE);
        return m==null ? -1 : m.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }
    static void save(Context c,Location l) {
        prefs(c).edit().putString("last",String.format(Locale.US,"%.6f,%.6f",l.getLatitude(),l.getLongitude()))
          .putFloat("acc",l.getAccuracy()).putLong("time",l.getTime()).apply();
    }
    static String last(Context c) {
        android.content.SharedPreferences p=prefs(c);
        String s=p.getString("last","");
        if(s.isEmpty()) return "GPS LAST: brak zapisanej pozycji";
        return "GPS LAST (historyczna) "+new java.text.SimpleDateFormat("dd.MM HH:mm",Locale.ROOT).format(new Date(p.getLong("time",0)))+" +/-"+Math.round(p.getFloat("acc",0))+"m\nhttps://maps.google.com/?q="+s;
    }
}
