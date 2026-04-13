package com.example.Amadeus;

import android.content.Context;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 全局语音引擎管理：
 * 1) 启动时后台预热 ASR/TTS
 * 2) 在页面间复用实例，减少首次调用延迟
 */
public final class SpeechEngineManager {
    private static final String TAG = "SpeechEngineManager";

    private static final ExecutorService WARMUP_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Object ASR_LOCK = new Object();
    private static final Object TTS_LOCK = new Object();

    private static volatile SenseVoiceRecognizer sharedAsr;
    private static volatile VitsJaSynthesizer sharedTts;

    private SpeechEngineManager() {
    }

    public static void prewarm(Context context) {
        final Context appContext = context.getApplicationContext();
        WARMUP_EXECUTOR.execute(() -> {
            try {
                getAsr(appContext).init();
            } catch (Throwable t) {
                Log.w(TAG, "ASR prewarm failed", t);
            }

            try {
                getTtsSynthesizer(appContext).warmup();
            } catch (Throwable t) {
                Log.w(TAG, "TTS prewarm failed", t);
            }
        });
    }

    public static SenseVoiceRecognizer getAsr(Context context) {
        if (sharedAsr == null) {
            synchronized (ASR_LOCK) {
                if (sharedAsr == null) {
                    Context asrContext = context;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        asrContext = context.createAttributionContext("amadeus_asr");
                    }
                    sharedAsr = new SenseVoiceRecognizer(asrContext.getApplicationContext());
                }
            }
        }
        return sharedAsr;
    }

    public static VitsJaSynthesizer getTtsSynthesizer(Context context) {
        if (sharedTts == null) {
            synchronized (TTS_LOCK) {
                if (sharedTts == null) {
                    Context ttsContext = context;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        ttsContext = context.createAttributionContext("amadeus_tts");
                    }
                    sharedTts = new VitsJaSynthesizer(ttsContext.getApplicationContext());
                }
            }
        }
        return sharedTts;
    }
}