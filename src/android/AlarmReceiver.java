package fr.dijkman.pgs;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;


import fr.dijkman.pgs.constant.Constant;


public class AlarmReceiver extends BroadcastReceiver {
    static final String TAG = PassiveGeolocationService.class.getCanonicalName();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals(Constant.START_SERVICE)) {
            Log.i(TAG, "?? Alarm ??");
            Intent serviceIntent = new Intent(context, PassiveGeolocationService.class);
            context.startService(serviceIntent);
        }
    }
}
