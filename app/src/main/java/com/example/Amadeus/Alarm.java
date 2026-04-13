package com.example.Amadeus;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.PowerManager;
import android.os.Vibrator;
import android.preference.PreferenceManager;
import android.util.Log;

class Alarm {

    private static MediaPlayer m;
    private static SharedPreferences settings;
    private static Vibrator v;

    static final int ALARM_ID = 104859;
    static final int ALARM_NOTIFICATION_ID = 102434;

    private static final String TAG = "Alarm";
    private static boolean isPlaying = false;
    private static PowerManager.WakeLock sCpuWakeLock;

    static void start(Context context, int ringtone) {

        acquireCpuWakeLock(context);

        settings = PreferenceManager.getDefaultSharedPreferences(context);

        // 控制手机震动
        if (settings.getBoolean("vibrate", false)) {
            v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            long[] pattern = {500, 1000};
            v.vibrate(pattern, 0);
        }

        // 响铃（铃声种类由外界传参设置）
        m = MediaPlayer.create(context, ringtone);
        m.setLooping(true);
        m.start();
        if (m.isPlaying()) {
            isPlaying = true;
        }

        Log.d(TAG, "Start");

    }

    static void cancel(Context context) {

        if (isPlaying) {
            settings = PreferenceManager.getDefaultSharedPreferences(context);
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent alarmIntent = new Intent(context, AlarmReceiver.class);
            // Flag似乎有问题，但未报错，待议
            final PendingIntent pendingIntent;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                pendingIntent = PendingIntent.getBroadcast(context, ALARM_ID, alarmIntent, PendingIntent.FLAG_IMMUTABLE);
            }else{
                pendingIntent = PendingIntent.getBroadcast(context, ALARM_ID, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            }
            NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

            SharedPreferences.Editor editor = settings.edit();
            editor.putBoolean("alarm_toggle", false);
            editor.apply();
            m.release();
            // 删除横幅通知与闹钟事件
            notificationManager.cancel(ALARM_NOTIFICATION_ID);
            alarmManager.cancel(pendingIntent);
            releaseCpuLock();
            isPlaying = false;
            if (v != null) {
                v.cancel();
            }
            Log.d(TAG, "Cancel");
        }

    }

    static boolean isPlaying() {
        return isPlaying;
    }

    // 唤醒cpu（不过似乎没什么用）
    private static void acquireCpuWakeLock(Context context) {
        if (sCpuWakeLock != null) {
            return;
        }
        PowerManager pm =
                (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        sCpuWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK |
                        PowerManager.ACQUIRE_CAUSES_WAKEUP |
                        PowerManager.ON_AFTER_RELEASE, "Amadeus::"+TAG);
        sCpuWakeLock.acquire();
    }

    private static void releaseCpuLock() {
        if (sCpuWakeLock != null) {
            sCpuWakeLock.release();
            sCpuWakeLock = null;
        }
    }

}

