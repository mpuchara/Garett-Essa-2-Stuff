package pl.homemind.garettgps;
import android.content.*;
import android.app.Activity;
public final class SmsReport extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i) {
        Store.log(c,getResultCode()==Activity.RESULT_OK?"SMS wyslany (modem)":"Blad wysylania SMS: "+getResultCode());
    }
}
