package com.example.Amadeus;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.SpeechRecognizer;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.app.TaskStackBuilder;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;


public class LaunchActivity extends AppCompatActivity {

    private ImageView connect, cancel, logo;
    private TextView status;
    private Boolean isPressed = false;
    private MediaPlayer m;
    private final Handler aniHandle = new Handler(Looper.getMainLooper());

    private int i = 0;

    Runnable aniRunnable = new Runnable() {
        // 应用启动动画
        public void run() {
            final int DURATION = 20;
            if (i < 39) {
                i++;
                // 字符串与整型相加似乎不用将后者转换
                String imgName = "logo" + i;
                int id = getResources().getIdentifier(imgName, "drawable", getPackageName());
                logo.setImageDrawable((ContextCompat.getDrawable(LaunchActivity.this, id)));
                aniHandle.postDelayed(this, DURATION);
            }
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launch);
        connect = (ImageView) findViewById(R.id.imageView_connect);
        cancel = (ImageView) findViewById(R.id.imageView_cancel);
        status = (TextView) findViewById(R.id.textView_call);
        logo = (ImageView) findViewById(R.id.imageView_logo);
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        aniHandle.post(aniRunnable);

        // 检测系统语音识别服务是否正常运行
        if (!SpeechRecognizer.isRecognitionAvailable(LaunchActivity.this)) {
            status.setText(R.string.google_app_error);
        }

        if (Alarm.isPlaying()) {
            status.setText(R.string.incoming_call);
            // 发送通知并使屏幕常亮
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        if (settings.getBoolean("show_notification", false)) {
            showNotification();
        }

        connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // 检测是否安装谷歌翻译软件（用以采集麦克风声音转化指令）
//                if (!isPressed && isAppInstalled(LaunchActivity.this, "com.google.android.googlequicksearchbox")) {
                  if(!isPressed){
                    isPressed = true;
                    // 按压效果图片
                    connect.setImageResource(R.drawable.connect_select);

                    if (!Alarm.isPlaying()) {
                        m = MediaPlayer.create(LaunchActivity.this, R.raw.tone);

                        m.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                            @Override
                            public void onPrepared(MediaPlayer mp) {
                                mp.start();
                                status.setText(R.string.connecting);
                            }
                        });

                        m.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                            @Override
                            public void onCompletion(MediaPlayer mp) {
                                mp.release();
                                Intent intent = new Intent(LaunchActivity.this, MainActivity.class);
                                startActivity(intent);
                            }
                        });
                    } else {
                        Alarm.cancel(LaunchActivity.this);
                        // 正式进入软件
                        Intent intent = new Intent(LaunchActivity.this, MainActivity.class);
                        startActivity(intent);
                    }
                }
            }
        });

        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                cancel.setImageResource(R.drawable.cancel_select);
                Alarm.cancel(getApplicationContext());
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.addCategory(Intent.CATEGORY_HOME);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        });

        logo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Intent settingIntent = new Intent(LaunchActivity.this, SettingsActivity.class);
                startActivity(settingIntent);
            }
        });
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LangContext.wrap(newBase));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();


        if (m != null) {
            m.release();
        }

        Alarm.cancel(LaunchActivity.this);
        aniHandle.removeCallbacks(aniRunnable);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (isPressed) {
            status.setText(R.string.disconnected);
        } else if (Alarm.isPlaying()) {
            status.setText(R.string.incoming_call);
        } else if (!SpeechRecognizer.isRecognitionAvailable(LaunchActivity.this)) {
            status.setText(R.string.google_app_error);
        }  else {
            status.setText(R.string.call);
        }

        isPressed = false;
        connect.setImageResource(R.drawable.connect_unselect);
        cancel.setImageResource(R.drawable.cancel_unselect);
    }

    private void showNotification() {
        // 高版本安卓发送通知需要指定channelId与重要性，最终控制权在用户手中
        NotificationChannel channel = new NotificationChannel("notification_id", "notification", NotificationManager.IMPORTANCE_HIGH);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                LaunchActivity.this,"notification_id")
                .setSmallIcon(R.drawable.xp2)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_text));
        Intent resultIntent = new Intent(LaunchActivity.this, MainActivity.class);
        TaskStackBuilder stackBuilder = TaskStackBuilder.create(LaunchActivity.this);
        stackBuilder.addParentStack(MainActivity.class);
        stackBuilder.addNextIntent(resultIntent);
        PendingIntent resultPendingIntent;
        // 不同sdk版本似乎会影响通知方式，暂且这样处理（待议）
        // API 31即以上，创建时需要明确指定 PendingIntent.FLAG_IMMUTABLE
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            resultPendingIntent = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_IMMUTABLE);
        }else{
            resultPendingIntent = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_ONE_SHOT);
        }
        builder.setContentIntent(resultPendingIntent);
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(channel);
        notificationManager.notify(0, builder.build());
    }
}