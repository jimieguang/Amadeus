package com.example.Amadeus;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.baidu.speech.EventListener;
import com.baidu.speech.EventManager;
import com.baidu.speech.EventManagerFactory;
import com.baidu.speech.asr.SpeechConstant;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

public class RepeaterActivity extends AppCompatActivity implements EventListener {
    final int REQUEST_PERMISSION_RECORD_AUDIO = 11302;
    final int REQUEST_PERMISSION_INTERNET = 11303;
    private ImageView micro;
    private TextView input_view,state;
    private EventManager asr;
    private Handler myHandler;
    private Voice_reply voice_reply;

    private static final int REQUEST_EXTERNAL_STORAGE = 1;
    private static String[] PERMISSIONS_STORAGE = {
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE" };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_repeater);

        micro = findViewById(R.id.micro);
        input_view = findViewById(R.id.input);
        state = findViewById(R.id.textView_subtitles);

        // 初始化语音识别服务 baidu
        asr = EventManagerFactory.create(this,"asr");
        asr.registerListener(this);

        //
        voice_reply = new Voice_reply(myHandler);

        // 动态请求权限(录音、网络、储存）
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_PERMISSION_RECORD_AUDIO);
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.INTERNET}, REQUEST_PERMISSION_INTERNET);
        ActivityCompat.requestPermissions(RepeaterActivity.this, PERMISSIONS_STORAGE,REQUEST_EXTERNAL_STORAGE);


        myHandler = new Handler(Looper.getMainLooper()){
            @Override
            public void handleMessage(Message msg){
                switch (msg.what){
                    case 1:
                        state.setText("OVER!");
                        try {
                            voice_reply.voice_play("last",myHandler);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        break;
                    case 2:
                        state.setText("OVER!");
                    default:
                        break;
                }
            }
        };

        micro.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    RepeaterActivity host = (RepeaterActivity) view.getContext();

                    // 判断是否成功获取录音权限
                    int permissionCheck = ContextCompat.checkSelfPermission(host,
                            Manifest.permission.RECORD_AUDIO);
                    if (permissionCheck == PackageManager.PERMISSION_GRANTED){
                        micro.setVisibility(View.INVISIBLE);    //设置灰色麦克风不可见，则后面的绿色麦克风会显示出来
                        asr.send(SpeechConstant.ASR_START,"{}",null,0,0);
                    }
                }
            }});
    }

    @Override
    public void onEvent(String name, String params, byte[] data, int offset, int length) {
        // 识别相关的结果都在这里
        if (params == null || params.isEmpty()) {
            return;
        }
        if (params.contains("\"final_result\"")) {
            // 一句话的最终识别结果
            asr.send(SpeechConstant.ASR_STOP, "{}", null, 0, 0);
            String input = null;
            JSONObject res = null;
            try {
                res = new JSONObject(params);
                input = res.getString("best_result");
            } catch (JSONException e) {
                e.printStackTrace();
            }
            input_view.setText(input);
            micro.setVisibility(View.VISIBLE);
            params = input;
            if (input != null) {
                // 删除标点符号
                input = input.replaceAll("\\p{Punct}", "");
            }
            // 退出复读机模式
            if (input.contains("退出复读")) {
                Intent intent = new Intent(RepeaterActivity.this,MainActivity.class);
                startActivity(intent);
            }
            // 复读
            state.setText("Loading......");
            try {
                voice_reply.voice_play(params,myHandler);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}