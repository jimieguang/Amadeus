package com.example.Amadeus;

import android.app.IntentService;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.app.NotificationCompat;

public class AlarmService extends IntentService {

    public AlarmService() {
        super("AlarmService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        sendNotification(getString(R.string.incoming_call));
        Intent launch = new Intent(this, LaunchActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }

    private void sendNotification(String msg) {
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, LaunchActivity.class), PendingIntent.FLAG_IMMUTABLE);
        NotificationChannel channel = new NotificationChannel("alarm_notification", "notification", NotificationManager.IMPORTANCE_HIGH);
        NotificationCompat.Builder alarmNotificationBuilder = new NotificationCompat.Builder(
                this, "alarm_notification")
                .setContentTitle(getString(R.string.app_name)).setSmallIcon(R.drawable.incoming_call)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(msg))
                .setContentText(msg);
        alarmNotificationBuilder.setContentIntent(contentIntent);
        NotificationManager alarmNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        // 高版本安卓必须设置channel才能成功发送通知
        alarmNotificationManager.createNotificationChannel(channel);
        alarmNotificationManager.notify(Alarm.ALARM_NOTIFICATION_ID, alarmNotificationBuilder.build());
    }
}