package com.example.Amadeus;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;


public class AlarmReceiver extends BroadcastReceiver {

    private final String TAG = "AlarmReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {

        Log.d(TAG, "Broadcast received!");

        int[] ringtones = {
                R.raw.ringtone_gate_of_steiner, R.raw.ringtone_village,
                R.raw.ringtone_beginning_of_fight, R.raw.ringtone_easygoingness,
                R.raw.ringtone_reunion, R.raw.ringtone_precaution,
                R.raw.ringtone_over_the_sky
        };

        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(context);
        int index = Integer.parseInt(settings.getString("ringtone", "0"));

        Log.d(TAG, "Starting alarm...");

        Alarm.start(context, ringtones[index]);

        Intent service = new Intent(context, AlarmService.class);
        context.startService(service);
        // 此代码会产生奇怪的错误：BroadcastReceiver trying to return result during a non-ordered broadcast
//        setResultCode(Activity.RESULT_OK);
    }

}
