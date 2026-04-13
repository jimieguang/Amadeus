package com.example.Amadeus;

/*
 * Big thanks to https://github.com/RIP95 aka Emojikage
 * 语音识别已改为 SenseVoice 本地方案 (sherpa-onnx)
 */

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.audiofx.Visualizer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.graphics.drawable.AnimationDrawable;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_PERMISSION_RECORD_AUDIO = 11302;
    private static final String REQUIRED_JSON_RULE =
            "你必须只输出一个JSON对象，不能输出任何JSON外文本。"
                    + "字段固定为: zh(中文短句), ja(日语短句), emotion(情绪英文大写)。"
                    + "ja字段必须始终为自然日语，且不能为空。"
                    + "每个句子必须简练，建议不超过25个字。"
                    + "emotion仅允许: HAPPY, ANGRY, ANNOYED, BLUSH, SAD, NORMAL, WINKING, DISAPPOINTED, INDIFFERENT, SIDE, SIDED_PLEASANT, SIDED_WORRIED, SLEEPY。"
                    + "输出示例:{\"zh\":\"我在\",\"ja\":\"います\",\"emotion\":\"HAPPY\"}";

    private final VoiceLine[] voiceLines = VoiceLine.Line.getLines();
    private ImageView micro;
    private ImageView kurisuView;
    private TextView subtitlesView;
    private TextView input_view;
    private Voice_reply voice_reply;
    private AnimationDrawable currentMoodAnimation;
    private Visualizer aiTtsVisualizer;
    private final OpenAiChatClient openAiChatClient = new OpenAiChatClient();

    private SenseVoiceRecognizer senseVoiceRecognizer;
    private AudioRecorder audioRecorder;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean isRecording = false;
    private boolean isAiChatMode = false;
    private File currentRecordFile;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ImageView kurisu = findViewById(R.id.imageView_kurisu);
        kurisuView = kurisu;
        ImageView subtitlesBackground = findViewById(R.id.imageView_subtitles);
        subtitlesView = findViewById(R.id.textView_subtitles);
        micro = findViewById(R.id.micro);
        input_view = findViewById(R.id.input);

        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        senseVoiceRecognizer = SpeechEngineManager.getAsr(this);
        audioRecorder = new AudioRecorder();

        Handler ttsHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                // 播放由 Voice_reply 内部统一处理，这里预留给后续UI状态更新。
                if (msg.what == Voice_reply.EVENT_SYNTH_DONE || msg.what == Voice_reply.EVENT_READY_PLAY) {
                    // no-op
                }
            }
        };
        voice_reply = new Voice_reply(this, ttsHandler);
        voice_reply.setPlaybackObserver(new Voice_reply.PlaybackObserver() {
            @Override
            public void onPlaybackStarted(int audioSessionId) {
                mainHandler.post(() -> startLipSync(audioSessionId));
            }

            @Override
            public void onPlaybackCompleted() {
                mainHandler.post(MainActivity.this::stopLipSync);
            }
        });

        if (!settings.getBoolean("show_subtitles", false)) {
            subtitlesBackground.setVisibility(View.INVISIBLE);
        }

        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_PERMISSION_RECORD_AUDIO);

        Amadeus.speak(voiceLines[VoiceLine.Line.HELLO], MainActivity.this);

        kurisu.setOnClickListener(view -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                int permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);

                if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                    Amadeus.speak(voiceLines[VoiceLine.Line.DAGA_KOTOWARU], MainActivity.this);
                    return;
                }

                if (Amadeus.isLoop || Amadeus.isSpeaking) return;

                if (isRecording) {
                    // 停止录音并识别
                    stopRecordingAndRecognize();
                } else {
                    // 开始录音
                    if (!Amadeus.isListening) {
                        Amadeus.isListening = true;
                        promptSpeechInput();
                    }
                }
            }
        });

        kurisu.setOnLongClickListener(view -> {
            isAiChatMode = !isAiChatMode;
            String tip = isAiChatMode ? "AI语音对话模式已开启" : "AI语音对话模式已关闭";
            input_view.setText(tip);
            Toast.makeText(MainActivity.this, tip, Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LangContext.wrap(newBase));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopLipSync();
        executor.shutdown();
        if (audioRecorder != null && audioRecorder.isRecording()) audioRecorder.stopRecording();
        if (Amadeus.m != null) Amadeus.m.release();
        if (voice_reply != null) voice_reply.shutdown();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Amadeus.isLoop = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopLipSync();
        Amadeus.isLoop = false;
        if (isRecording) {
            audioRecorder.stopRecording();
            isRecording = false;
            Amadeus.isListening = false;
            micro.setVisibility(View.VISIBLE);
        }
    }

    private void promptSpeechInput() {
        micro.setVisibility(View.INVISIBLE);
        isRecording = true;
        try {
            currentRecordFile = File.createTempFile("amadeus_rec_", ".wav", getCacheDir());
            audioRecorder.startRecording(currentRecordFile, () -> mainHandler.post(this::stopRecordingAndRecognize));
        } catch (Exception e) {
            Log.e(TAG, "创建录音文件失败", e);
            mainHandler.post(() -> {
                isRecording = false;
                Amadeus.isListening = false;
                micro.setVisibility(View.VISIBLE);
                Toast.makeText(this, R.string.rec_error, Toast.LENGTH_SHORT).show();
            });
        }
    }

    private void stopRecordingAndRecognize() {
        if (!isRecording) return;
        audioRecorder.stopRecording();
        isRecording = false;
        micro.setVisibility(View.VISIBLE);

        File wavFile = currentRecordFile;
        currentRecordFile = null;
        if (wavFile == null || !wavFile.exists()) {
            Amadeus.isListening = false;
            return;
        }

        input_view.setText(getString(R.string.recognizing));
        executor.execute(() -> {
            String input = senseVoiceRecognizer.recognizeFromFile(wavFile);
            try { wavFile.delete(); } catch (Exception ignore) {}

            mainHandler.post(() -> {
                Amadeus.isListening = false;
                handleRecognitionResult(input);
            });
        });
    }

    private void handleRecognitionResult(String input) {
        if (input == null) input = "";
        input = input.replaceAll("\\p{Punct}", "");
        input_view.setText(input);

        if (input.isEmpty()) return;

        if (isAiChatMode) {
            requestAiReply(input);
            return;
        }

        if (input.contains("复读")) {
            startActivity(new Intent(MainActivity.this, RepeaterActivity.class));
            return;
        }

        // 使用当前经过 Locale 包装的 Context 处理输入
        if (!Amadeus.isRepeater) {
            Amadeus.responseToInput(input, this, MainActivity.this);
        }
    }

    private void requestAiReply(String userInput) {
        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        String baseUrl = settings.getString("llm_base_url", "");
        String apiKey = settings.getString("llm_api_key", "");
        String model = settings.getString("llm_model", "");
        String systemPrompt = settings.getString("llm_system_prompt", "你是一个简洁、自然、友好的语音助手。");

        int timeoutSec = 30;
        try {
            String timeout = settings.getString("llm_timeout_sec", "30");
            timeoutSec = Integer.parseInt(timeout);
        } catch (Exception ignore) {
        }

        String finalSystemPrompt = buildFinalSystemPrompt(systemPrompt);
        OpenAiChatClient.Config config = new OpenAiChatClient.Config(baseUrl, apiKey, model, finalSystemPrompt, timeoutSec);
        String configError = config.validateError();
        if (configError != null) {
            Toast.makeText(this, configError, Toast.LENGTH_SHORT).show();
            return;
        }

        input_view.setText("思考中...");
        executor.execute(() -> {
            try {
                String replyRaw = openAiChatClient.chat(config, userInput);
                ParsedAiReply parsed = parseAiReply(replyRaw);
                mainHandler.post(() -> {
                    input_view.setText(parsed.zh);
                    if (subtitlesView != null) {
                        subtitlesView.setText(parsed.ja);
                    }
                    applyEmotionPortrait(parsed.emotion);
                    if (voice_reply != null) {
                        // TTS 模型仅支持日语，这里强制使用 ja 字段。
                        voice_reply.voice_play(parsed.ja, null);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "LLM request failed", e);
                mainHandler.post(() -> Toast.makeText(MainActivity.this, "LLM请求失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private String buildFinalSystemPrompt(String userPrompt) {
        String p = userPrompt == null ? "" : userPrompt.trim();
        if (p.isEmpty()) {
            p = "你是一个简洁、自然、友好的语音助手。";
        }
        return p + "\n\n" + REQUIRED_JSON_RULE;
    }

    private ParsedAiReply parseAiReply(String raw) throws Exception {
        String json = extractJsonObject(raw);
        JSONObject obj = new JSONObject(json);

        String zh = obj.optString("zh", "").trim();
        String ja = obj.optString("ja", "").trim();
        String emotion = obj.optString("emotion", "NORMAL").trim().toUpperCase();

        if (ja.isEmpty()) {
            throw new IllegalArgumentException("AI返回缺少 ja(日语) 字段，无法进行TTS播报");
        }
        if (zh.isEmpty()) zh = ja;
        if (emotion.isEmpty()) emotion = "NORMAL";

        return new ParsedAiReply(zh, ja, emotion);
    }

    private String extractJsonObject(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();

        int codeStart = s.indexOf("```");
        if (codeStart >= 0) {
            int openBrace = s.indexOf('{', codeStart);
            int closeFence = s.indexOf("```", codeStart + 3);
            if (openBrace >= 0 && closeFence > openBrace) {
                String block = s.substring(openBrace, closeFence);
                int blockEnd = block.lastIndexOf('}');
                if (blockEnd >= 0) {
                    return block.substring(0, blockEnd + 1);
                }
            }
        }

        int first = s.indexOf('{');
        int last = s.lastIndexOf('}');
        if (first >= 0 && last > first) {
            return s.substring(first, last + 1);
        }
        return s;
    }

    private void applyEmotionPortrait(String emotion) {
        if (kurisuView == null) return;

        int moodRes;
        switch (emotion) {
            case "HAPPY":
                moodRes = VoiceLine.Mood.HAPPY;
                break;
            case "ANGRY":
                moodRes = VoiceLine.Mood.ANGRY;
                break;
            case "ANNOYED":
                moodRes = VoiceLine.Mood.ANNOYED;
                break;
            case "BLUSH":
                moodRes = VoiceLine.Mood.BLUSH;
                break;
            case "SAD":
                moodRes = VoiceLine.Mood.SAD;
                break;
            case "WINKING":
                moodRes = VoiceLine.Mood.WINKING;
                break;
            case "DISAPPOINTED":
                moodRes = VoiceLine.Mood.DISAPPOINTED;
                break;
            case "INDIFFERENT":
                moodRes = VoiceLine.Mood.INDIFFERENT;
                break;
            case "SIDE":
                moodRes = VoiceLine.Mood.SIDE;
                break;
            case "SIDED_PLEASANT":
                moodRes = VoiceLine.Mood.SIDED_PLEASANT;
                break;
            case "SIDED_WORRIED":
                moodRes = VoiceLine.Mood.SIDED_WORRIED;
                break;
            case "SLEEPY":
                moodRes = VoiceLine.Mood.SLEEPY;
                break;
            case "NORMAL":
            default:
                moodRes = VoiceLine.Mood.NORMAL;
                break;
        }

        try {
            Drawable d = Drawable.createFromXml(getResources(), getResources().getXml(moodRes));
            if (d instanceof AnimationDrawable) {
                currentMoodAnimation = (AnimationDrawable) d;
                kurisuView.setImageDrawable(currentMoodAnimation.getFrame(0));
            } else {
                currentMoodAnimation = null;
                kurisuView.setImageDrawable(d);
            }
        } catch (Throwable e) {
            Log.w(TAG, "applyEmotionPortrait failed: " + emotion, e);
        }
    }

    private void startLipSync(int audioSessionId) {
        stopLipSync();
        if (currentMoodAnimation == null || kurisuView == null) return;

        try {
            aiTtsVisualizer = new Visualizer(audioSessionId);
            aiTtsVisualizer.setEnabled(false);
            aiTtsVisualizer.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
            aiTtsVisualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] bytes, int samplingRate) {
                    int sum = 0;
                    for (int i = 1; i < bytes.length; i++) {
                        sum += bytes[i] + 128;
                    }
                    final float normalized = sum / (float) bytes.length;
                    runOnUiThread(() -> {
                        if (currentMoodAnimation == null || currentMoodAnimation.getNumberOfFrames() <= 0) return;
                        if (normalized > 50 && currentMoodAnimation.getNumberOfFrames() >= 3) {
                            kurisuView.setImageDrawable(currentMoodAnimation.getFrame((int) Math.ceil(Math.random() * 2)));
                        } else {
                            kurisuView.setImageDrawable(currentMoodAnimation.getFrame(0));
                        }
                    });
                }

                @Override
                public void onFftDataCapture(Visualizer visualizer, byte[] bytes, int samplingRate) {
                }
            }, Visualizer.getMaxCaptureRate() / 2, true, false);
            aiTtsVisualizer.setEnabled(true);
        } catch (Throwable e) {
            Log.w(TAG, "startLipSync failed", e);
        }
    }

    private void stopLipSync() {
        if (aiTtsVisualizer != null) {
            try {
                aiTtsVisualizer.setEnabled(false);
                aiTtsVisualizer.release();
            } catch (Throwable ignore) {
            }
            aiTtsVisualizer = null;
        }
        if (currentMoodAnimation != null && currentMoodAnimation.getNumberOfFrames() > 0 && kurisuView != null) {
            kurisuView.setImageDrawable(currentMoodAnimation.getFrame(0));
        }
    }

    private static final class ParsedAiReply {
        final String zh;
        final String ja;
        final String emotion;

        ParsedAiReply(String zh, String ja, String emotion) {
            this.zh = zh;
            this.ja = ja;
            this.emotion = emotion;
        }
    }
}
