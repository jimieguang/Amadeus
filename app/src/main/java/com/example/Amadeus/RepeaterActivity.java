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
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RepeaterActivity extends AppCompatActivity {
    private static final int REQUEST_PERMISSION_RECORD_AUDIO = 11302;
    private static final String TTS_SMOKE_TEXT = "これはVITS日本語TTSのセルフチェックです。";

    private ImageView micro;
    private ImageView microGreen;
    private TextView input_view, state;
    private Handler myHandler;
    private Voice_reply voice_reply;
    private SenseVoiceRecognizer senseVoiceRecognizer;
    private AudioRecorder audioRecorder;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isRecording = false;
    private boolean smokeTestRunning = false;
    private File currentRecordFile;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_repeater);

        micro = findViewById(R.id.micro);
        microGreen = findViewById(R.id.micro_green);
        input_view = findViewById(R.id.input);
        state = findViewById(R.id.textView_subtitles);

        micro.setVisibility(View.VISIBLE);
        microGreen.setVisibility(View.INVISIBLE);

        senseVoiceRecognizer = SpeechEngineManager.getAsr(this);
        audioRecorder = new AudioRecorder();

        myHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                try {
                    switch (msg.what) {
                        case Voice_reply.EVENT_SYNTH_DONE:
                        case Voice_reply.EVENT_READY_PLAY:
                            // 这里仅更新UI状态；播放由 Voice_reply 内部统一触发。
                            if (smokeTestRunning) {
                                Voice_reply.TtsRunStats stats = voice_reply.getLastStats();
                                state.setText(stats.toDebugText());
                                smokeTestRunning = false;
                            } else {
                                state.setText("OVER!");
                            }
                            break;
                        default:
                            break;
                    }
                } catch (Exception e) {
                    Log.e("RepeaterActivity", "Handler callback failed", e);
                }
            }
        };
        Context ttsContext = this;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ttsContext = createAttributionContext("amadeus_tts");
        }
        voice_reply = new Voice_reply(ttsContext, myHandler);

        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_PERMISSION_RECORD_AUDIO);

        View.OnClickListener micClick = view -> onMicClicked();
        micro.setOnClickListener(micClick);
        microGreen.setOnClickListener(micClick);

        View.OnLongClickListener micLongClick = view -> {
            runTtsSmokeTest();
            return true;
        };
        micro.setOnLongClickListener(micLongClick);
        microGreen.setOnLongClickListener(micLongClick);
    }

    private void onMicClicked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
            if (permissionCheck != PackageManager.PERMISSION_GRANTED) return;

            if (isRecording) {
                stopRecordingAndRecognize();
            } else {
                startRecording();
            }
        }
    }

    private void runTtsSmokeTest() {
        if (isRecording) {
            stopRecordingAndRecognize();
            return;
        }
        state.setText("TTS自检中...");
        input_view.setText(TTS_SMOKE_TEXT);
        smokeTestRunning = true;
        Log.d("RepeaterActivity", "Run TTS smoke test");
        voice_reply.clearCacheForText(TTS_SMOKE_TEXT);
        voice_reply.voice_play(TTS_SMOKE_TEXT, myHandler);
    }

    private void startRecording() {
        isRecording = true;
        micro.setVisibility(View.INVISIBLE);
        microGreen.setVisibility(View.VISIBLE);
        state.setText("录音中...");
        try {
            currentRecordFile = File.createTempFile("amadeus_repeat_", ".wav", getCacheDir());
            audioRecorder.startRecording(currentRecordFile, () -> mainHandler.post(this::stopRecordingAndRecognize));
        } catch (Exception e) {
            Log.e("RepeaterActivity", "创建录音文件失败", e);
            mainHandler.post(() -> {
                isRecording = false;
                micro.setVisibility(View.VISIBLE);
                microGreen.setVisibility(View.INVISIBLE);
            });
        }
    }

    private void stopRecordingAndRecognize() {
        if (!isRecording) return;
        audioRecorder.stopRecording();
        isRecording = false;
        micro.setVisibility(View.VISIBLE);
        microGreen.setVisibility(View.INVISIBLE);

        File wavFile = currentRecordFile;
        currentRecordFile = null;
        if (wavFile == null || !wavFile.exists()) return;

        state.setText("Loading......");
        executor.execute(() -> {
            String input = senseVoiceRecognizer.recognizeFromFile(wavFile);
            try { wavFile.delete(); } catch (Exception ignore) {}

            mainHandler.post(() -> {
                input_view.setText(input);
                String cleaned = (input != null) ? input.replaceAll("\\p{Punct}", "") : "";
                Log.d("RepeaterActivity", "ASR(cleaned): " + cleaned);
                if (cleaned.contains("退出复读")) {
                    startActivity(new Intent(RepeaterActivity.this, MainActivity.class));
                    finish();
                    return;
                }
                if (!cleaned.isEmpty()) {
                    voice_reply.voice_play(cleaned, myHandler);
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        if (audioRecorder != null && audioRecorder.isRecording()) audioRecorder.stopRecording();
        if (voice_reply != null) voice_reply.shutdown();
    }
}
