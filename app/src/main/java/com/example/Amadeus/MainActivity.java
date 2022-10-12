package com.example.Amadeus;

/*
 * Big thanks to https://github.com/RIP95 aka Emojikage
 */

import static android.content.pm.PackageManager.MATCH_ALL;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;


import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MainActivity extends AppCompatActivity {

    private final String TAG = "MainActivity";

    private final VoiceLine[] voiceLines = VoiceLine.Line.getLines();
    private final Random random = new Random();
    private String recogLang;
    private String[] contextLang;
    private SpeechRecognizer sr;

    // 根据系统的不同，初始化语音识别模块
    protected void initSpeechRecognizer(Context context, SharedPreferences settings) {
        String default_recognition = settings.getString("default_recognition", "com.google.android.googlequicksearchbox");
        // 获取当前系统内置语音识别服务，并组装成一个Component组件
        String serviceComponent = Settings.Secure.getString(this.getContentResolver(),
                "voice_recognition_service");
        ComponentName component = ComponentName.unflattenFromString(serviceComponent);
        // 如果默认识别应用与用户设置相同，则使用该应用
        if(component.getPackageName().equals(default_recognition)){
            sr = SpeechRecognizer.createSpeechRecognizer(this);
            return ;
        }
        // 获取所有”可用的“语音识别服务, 设置用户所选择的应用为默认语音识别服务（没有则随便设置一个）
        List<ResolveInfo> list = this.getPackageManager().queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), MATCH_ALL);
        if (list != null && list.size() != 0) {
            for (ResolveInfo info : list) {
                Log.d(TAG, "\t" + info.loadLabel(context.getPackageManager()) + ": "
                        + info.serviceInfo.packageName + "/" + info.serviceInfo.name);
                if (info.serviceInfo.packageName.equals(default_recognition)) {
                    component = new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name);
                    sr = SpeechRecognizer.createSpeechRecognizer(this, component);
                    return ;
                }
            }
        }
        // 运行到这一步说明系统没有语音识别模块或与用户设置不符，应提醒用户
        assert false;  //没想到怎么提醒，干脆先闪退吧
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        ImageView kurisu = (ImageView) findViewById(R.id.imageView_kurisu);
        ImageView subtitlesBackground = (ImageView) findViewById(R.id.imageView_subtitles);
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        recogLang = settings.getString("recognition_lang", "ja-JP");
        contextLang = recogLang.split("-");

        // 初始化语音识别服务
        initSpeechRecognizer(this,settings);
        sr.setRecognitionListener(new listener());

//        if(SpeechRecognizer.isRecognitionAvailable(this)!=true){
//            Log.e(TAG,"未支持的设备");
//        }
        final Handler handler = new Handler(Looper.getMainLooper());
        final int REQUEST_PERMISSION_RECORD_AUDIO = 11302;
        final int REQUEST_PERMISSION_INTERNET = 11303;

        if (!settings.getBoolean("show_subtitles", false)) {
            subtitlesBackground.setVisibility(View.INVISIBLE);
        }

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

                    // 获取录音权限
                    int permissionCheck = ContextCompat.checkSelfPermission(host,
                            Manifest.permission.RECORD_AUDIO);

                    /* Input during loop produces bugs and mixes with output */
                    if (!Amadeus.isLoop && !Amadeus.isSpeaking) {
                        if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                            promptSpeechInput();
                        } else {
                            Amadeus.speak(voiceLines[VoiceLine.Line.DAGA_KOTOWARU], MainActivity.this);
                        }
                    }

                } else if (!Amadeus.isLoop && !Amadeus.isSpeaking) {
                    promptSpeechInput();
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
        if (sr != null)
            sr.destroy();
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

    // 获取用户语音输入(谷歌原生)
    private void promptSpeechInput() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, recogLang);

        if(SpeechRecognizer.isRecognitionAvailable(MainActivity.this)){
            //提示语音开始文字
            intent.putExtra(RecognizerIntent.EXTRA_PROMPT,"Please start your voice");
            sr.startListening(intent);
        }else{
            Amadeus.speak(voiceLines[VoiceLine.Line.SORRY], MainActivity.this);
            System.out.println("没有相关服务");
//            sr.startListening(intent);
        }
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

    private class listener implements RecognitionListener {

        private final String TAG = "VoiceListener";

        public void onReadyForSpeech(Bundle params) {
            Log.d(TAG, "Speech recognition start");
        }
        public void onBeginningOfSpeech() {
            Log.d(TAG, "Listening speech");
        }
        public void onRmsChanged(float rmsdB) {
            //Log.d(TAG, "onRmsChanged");
        }
        public void onBufferReceived(byte[] buffer) {
            Log.d(TAG, "onBufferReceived");
        }
        public void onEndOfSpeech() {
            Log.d(TAG, "Speech recognition end");
        }
        public void onError(int error) {
            switch (error) {
                case SpeechRecognizer.ERROR_AUDIO:
                    Log.d(TAG, "ERROR_AUDIO");
                    break;
                case SpeechRecognizer.ERROR_CLIENT:
                    Log.d(TAG, "ERROR_CLIENT");
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    Log.d(TAG, "ERROR_RECOGNIZER_BUSY");
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    Log.d(TAG, "ERROR_INSUFFICIENT_PERMISSIONS");
                    break;
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    Log.d(TAG, "ERROR_NETWORK_TIMEOUT");
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                    sr.destroy();
                    Log.d(TAG, "ERROR_NETWORK");
                    break;
                case SpeechRecognizer.ERROR_SERVER:
                    Log.d(TAG, "ERROR_SERVER");
                    break;
                case SpeechRecognizer.ERROR_NO_MATCH:
                    Log.d(TAG, "ERROR_NO_MATCH");
                    break;
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    Log.d(TAG, "ERROR_SPEECH_TIMEOUT");
                    break;
                case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                    Log.d(TAG,"ERROR_LANGUAGE_UNAVAILABLE ");
                    break;
                default:
                    assert false;
                    return;
            }
            sr.cancel();
            Amadeus.speak(voiceLines[VoiceLine.Line.SORRY], MainActivity.this);
        }
        public void onResults(Bundle results) {
            String input = "";
            String debug = "";
            Log.d(TAG, "Received results");
            ArrayList data = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

            for (Object word: data) {
                debug += word + "\n";
            }
            Log.d(TAG, debug);

            input += data.get(0);
            /* TODO: Japanese doesn't split the words. Sigh. */
            String[] splitInput = input.split(" ");

            /* Really, google? */
            if (splitInput[0].equalsIgnoreCase("Асистент")) {
                splitInput[0] = "Ассистент";
            }

            /* Switch language within current context for voice recognition */
            Context context = LangContext.load(getApplicationContext(), contextLang[0]);

            if (splitInput.length > 2 && splitInput[0].equalsIgnoreCase(context.getString(R.string.assistant))) {
                String cmd = splitInput[1].toLowerCase();
                String[] args = new String[splitInput.length - 2];
                System.arraycopy(splitInput, 2, args, 0, splitInput.length - 2);

                if (cmd.contains(context.getString(R.string.open))) {
                    Amadeus.openApp(args, MainActivity.this);
                }

            } else {
                Amadeus.responseToInput(input, context, MainActivity.this);
            }
        }
        public void onPartialResults(Bundle partialResults) {
            Log.d(TAG, "onPartialResults");
        }
        public void onEvent(int eventType, Bundle params) {
            Log.d(TAG, "onEvent " + eventType);
        }

    }

}
