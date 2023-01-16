package com.example.Amadeus;

/*
 * Big thanks to https://github.com/RIP95 aka Emojikage
 */

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.baidu.speech.EventListener;
import com.baidu.speech.EventManager;
import com.baidu.speech.EventManagerFactory;
import com.baidu.speech.asr.SpeechConstant;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Random;

public class MainActivity extends AppCompatActivity implements EventListener {

    private final String TAG = "MainActivity";

    private final VoiceLine[] voiceLines = VoiceLine.Line.getLines();
    private final Random random = new Random();
    private String recogLang;
    private String[] contextLang;
    private ImageView micro;
    private TextView input_view;
    // 百度语音识别模块
    private EventManager asr;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        ImageView kurisu = (ImageView) findViewById(R.id.imageView_kurisu);
        ImageView subtitlesBackground = (ImageView) findViewById(R.id.imageView_subtitles);
        // 用于提醒用户是否在录音
        micro = (ImageView) findViewById(R.id.micro);
        // 显示语音识别结果
        input_view = (TextView) findViewById(R.id.input);
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        recogLang = settings.getString("recognition_lang", "ja-JP");
        contextLang = recogLang.split("-");

        // 初始化语音识别服务 baidu
        asr = EventManagerFactory.create(this,"asr");
        asr.registerListener(this);

        final Handler handler = new Handler(Looper.getMainLooper());
        final int REQUEST_PERMISSION_RECORD_AUDIO = 11302;
        final int REQUEST_PERMISSION_INTERNET = 11303;

        if (!settings.getBoolean("show_subtitles", false)) {
            subtitlesBackground.setVisibility(View.INVISIBLE);
        }
        // 动态请求权限
        ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_PERMISSION_RECORD_AUDIO);
        ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.INTERNET}, REQUEST_PERMISSION_INTERNET);

        Amadeus.speak(voiceLines[VoiceLine.Line.HELLO], MainActivity.this);

        final Runnable loop = new Runnable() {
            @Override
            public void run() {
                if (Amadeus.isLoop) {
                    Amadeus.speak(voiceLines[random.nextInt(voiceLines.length)], MainActivity.this);
                    handler.postDelayed(this, 5000 + random.nextInt(5) * 1000);
                }
            }
        };

        kurisu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    MainActivity host = (MainActivity) view.getContext();

                    // 判断是否成功获取录音权限
                    int permissionCheck = ContextCompat.checkSelfPermission(host,
                            Manifest.permission.RECORD_AUDIO);

                    /* Input during loop produces bugs and mixes with output */
                    if (!Amadeus.isLoop && !Amadeus.isSpeaking&& !Amadeus.isListening) {
                        if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                            Amadeus.isListening = true;
                            promptSpeechInput();
                        } else {
                            Amadeus.speak(voiceLines[VoiceLine.Line.DAGA_KOTOWARU], MainActivity.this);
                        }
                    }else if (!Amadeus.isLoop && !Amadeus.isSpeaking) {
                        promptSpeechInput();
                    }
                }
            }});


        kurisu.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                if (!Amadeus.isLoop && !Amadeus.isSpeaking) {
                    handler.post(loop);
                    Amadeus.isLoop = true;
                } else {
                    handler.removeCallbacks(loop);
                    Amadeus.isLoop = false;
                }
                return true;
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
        //发送取消事件
        asr.send(SpeechConstant.ASR_CANCEL, "{}", null, 0, 0);
        //退出事件管理器
        // 必须与registerListener成对出现，否则可能造成内存泄露
        asr.unregisterListener(this);

        if (Amadeus.m != null)
            Amadeus.m.release();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Amadeus.isLoop = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        Amadeus.isLoop = false;
    }

    // 获取用户语音输入(百度sdk)
    private void promptSpeechInput() {
        micro.setVisibility(View.INVISIBLE);    //设置灰色麦克风不可见，则后面的绿色麦克风会显示出来

        asr.send(SpeechConstant.ASR_START,"{}",null,0,0);

    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case 1: {
                if (resultCode == RESULT_OK && null != data) {

                    /* Switch language within current context for voice recognition */
                    Context context = LangContext.load(getApplicationContext(), contextLang[0]);

                    ArrayList<String> input = data
                            .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                    Amadeus.responseToInput(input.get(0), context, MainActivity.this);
                }
                break;
            }

        }
    }

    // 语音识别结果事件
    @Override
    public void onEvent(String name, String params, byte[] data, int offset, int length) {
        // 识别相关的结果都在这里
        if (params == null || params.isEmpty()) {
            return;
        }
        if (params.contains("\"final_result\"")) {
            // 一句话的最终识别结果
            asr.send(SpeechConstant.ASR_STOP,"{}",null,0,0);
            Amadeus.isListening = false;
            micro.setVisibility(View.VISIBLE);

            String input = null;
            JSONObject res = null;
            try {
                res = new JSONObject(params);
                input = res.getString("best_result");
            } catch (JSONException e) {
                e.printStackTrace();
            }
            input_view.setText(input);
            params = input;
            if(input!=null){
                input = input.replaceAll("\\p{Punct}", "");
            }
            // 进入复读机模式
            if(input.contains("进入复读")){
//                Amadeus.isRepeater = true;
                Intent intent = new Intent(MainActivity.this,RepeaterActivity.class);
                startActivity(intent);
                return;
            }
            String[] splitInput = input.split(" ");
            /* Switch language within current context for voice recognition */
            Context context = LangContext.load(getApplicationContext(), contextLang[0]);

            if(!Amadeus.isRepeater){
                Amadeus.responseToInput(input, context, MainActivity.this);
            }
        }
    }
}
