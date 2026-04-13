package com.example.Amadeus;

import android.content.Context;
import android.app.ActivityManager;
import android.os.Debug;
import android.media.MediaPlayer;
import android.media.AudioTrack;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Voice_reply {
    private static final String TAG = "Voice_reply";
    private static final String CACHE_NAMESPACE = "vitsja_v1::";
    public static final int EVENT_SYNTH_DONE = 1;
    public static final int EVENT_READY_PLAY = 2;

    public static String last_voice;

    private final Handler myHandler;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Context appContext;

    private final VitsJaSynthesizer synthesizer;
    private final Map<String, float[]> audioCache = new ConcurrentHashMap<>();
    private MediaPlayer mediaPlayer;
    private AudioTrack audioTrack;
    private volatile PlaybackObserver playbackObserver;
    private volatile TtsRunStats lastStats = TtsRunStats.empty();

    public interface PlaybackObserver {
        void onPlaybackStarted(int audioSessionId);
        void onPlaybackCompleted();
    }

    Voice_reply(Context context, Handler myHandler) {
        this.myHandler = myHandler;
        this.appContext = context.getApplicationContext();
        this.synthesizer = SpeechEngineManager.getTtsSynthesizer(this.appContext);

        // Preload dictionary/model in background so first utterance is faster.
        executor.execute(() -> {
            try {
                synthesizer.warmup();
            } catch (Throwable e) {
                Log.w(TAG, "VITS warmup failed", e);
            }
        });
    }

    public void voice_play(String input, Handler ignored) {
        if (Objects.equals(input, "last")) {
            input = last_voice;
        }
        if (input == null || input.trim().isEmpty()) {
            return;
        }

        final String text = input;
        final String cacheKey = md5(text);
        
        // 检查内存缓存
        if (audioCache.containsKey(cacheKey)) {
            lastStats = TtsRunStats.cacheHit(captureMemory());
            Message msg = Message.obtain();
            msg.what = EVENT_READY_PLAY;
            myHandler.sendMessage(msg);
            playMemory(audioCache.get(cacheKey));
            return;
        }

        last_voice = input;
        executor.execute(() -> {
            try {
                MemorySnapshot before = captureMemory();
                long beginNs = System.nanoTime();
                
                // 合成到内存（不写文件）
                float[] samples = synthesizer.synthesizeToMemory(text);
                long costMs = (System.nanoTime() - beginNs) / 1_000_000L;
                MemorySnapshot after = captureMemory();
                
                if (samples != null && samples.length > 0) {
                    // 缓存到内存
                    audioCache.put(cacheKey, samples);
                    lastStats = TtsRunStats.synthesized(costMs, before, after, true);
                    Message msg = Message.obtain();
                    msg.what = EVENT_SYNTH_DONE;
                    myHandler.sendMessage(msg);
                    // 统一播放入口：无论是新合成还是缓存命中，都在 Voice_reply 内部直接播放。
                    playMemory(samples);
                } else {
                    lastStats = TtsRunStats.synthesized(costMs, before, after, false);
                    Log.w(TAG, "TTS synthesis returned empty samples");
                }
            } catch (Throwable e) {
                Log.e(TAG, "VITS TTS synthesis failed", e);
            }
        });
    }

    public void clearCacheForText(String input) {
        if (input == null || input.trim().isEmpty()) {
            return;
        }
        // 仅清理内存缓存
        String cacheKey = md5(input);
        audioCache.remove(cacheKey);
    }

    public boolean find_wav(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        // 兼容旧方法名：仅检查内存缓存
        String cacheKey = md5(input);
        return audioCache.containsKey(cacheKey);
    }

    public void playLastIfExists() {
        if (last_voice == null || last_voice.trim().isEmpty()) {
            return;
        }
        String cacheKey = md5(last_voice);
        if (audioCache.containsKey(cacheKey)) {
            playMemory(audioCache.get(cacheKey));
        }
    }

    public void shutdown() {
        executor.shutdown();
        releasePlayer();
        audioCache.clear();  // 清理内存缓存
    }

    public void setPlaybackObserver(PlaybackObserver observer) {
        this.playbackObserver = observer;
    }

    /**
     * 使用AudioTrack播放内存中的PCM音频（浮点）
     */
    private void playMemory(float[] samples) {
        if (samples == null || samples.length == 0) {
            Log.w(TAG, "playMemory: samples is null or empty");
            return;
        }
        Log.d(TAG, "playMemory: starting playback, samples=" + samples.length);
        try {
            releasePlayer();
            
            int sampleRate = 22050;  // 与VITS输出一致
            int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
            int audioFormat = AudioFormat.ENCODING_PCM_FLOAT;
            
            // 计算buffer大小（字节）
            int sampleBufferSize = samples.length * 4;  // float = 4 bytes
            
            // 创建AudioTrack
            AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
            
            AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(audioFormat)
                .build();
            
            int minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat);
            int bufferSize = Math.max(sampleBufferSize, minBufferSize * 2);  // At least 2x min buffer
            audioTrack = new AudioTrack(attrs, format, bufferSize, 
                                       AudioTrack.MODE_STREAM, 0);
            
            Log.d(TAG, "playMemory: AudioTrack created, minBufferSize=" + minBufferSize + ", bufferSize=" + bufferSize);
            
            // 检查AudioTrack状态
            if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "playMemory: AudioTrack not initialized, state=" + audioTrack.getState());
                releasePlayer();
                return;
            }
            
            // 播放
            audioTrack.play();
            Log.d(TAG, "playMemory: AudioTrack.play() called");
            
            // 触发开始事件
            PlaybackObserver o = playbackObserver;
            if (o != null) {
                try {
                    o.onPlaybackStarted(audioTrack.getAudioSessionId());
                    Log.d(TAG, "playMemory: onPlaybackStarted triggered");
                } catch (Throwable ignore) {
                    Log.w(TAG, "playMemory: onPlaybackStarted failed", ignore);
                }
            }
            
            // 在后台线程写入音频并检测完成
            new Thread(() -> {
                try {
                    Log.d(TAG, "playMemory: write thread started");
                    int written = audioTrack.write(samples, 0, samples.length, AudioTrack.WRITE_BLOCKING);
                    Log.d(TAG, "playMemory: wrote " + written + " samples to AudioTrack");
                    
                    // 等待播放完成
                    int pollCount = 0;
                    while (audioTrack != null && audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                        Thread.sleep(100);
                        pollCount++;
                    }
                    Log.d(TAG, "playMemory: playback completed after " + (pollCount * 100) + "ms");
                    
                    // 触发完成事件
                    PlaybackObserver obs = playbackObserver;
                    if (obs != null) {
                        try {
                            obs.onPlaybackCompleted();
                            Log.d(TAG, "playMemory: onPlaybackCompleted triggered");
                        } catch (Throwable ignore) {
                            Log.w(TAG, "playMemory: onPlaybackCompleted failed", ignore);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "playMemory: write thread failed", e);
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to play audio track", e);
        }
    }

    private void releasePlayer() {
        // 释放AudioTrack
        if (audioTrack != null) {
            try {
                if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.stop();
                }
                audioTrack.release();
            } catch (Exception ignore) {
            }
            audioTrack = null;
        }
        
        // 释放MediaPlayer（向后兼容）
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                PlaybackObserver o = playbackObserver;
                if (o != null) {
                    try {
                        o.onPlaybackCompleted();
                    } catch (Throwable ignore) {
                    }
                }
                mediaPlayer.release();
            } catch (Exception ignore) {
            }
            mediaPlayer = null;
        }
    }

    public String md5(String src) {
        try {
            src = CACHE_NAMESPACE + src;
            java.security.MessageDigest m = java.security.MessageDigest.getInstance("MD5");
            m.update(src.getBytes("UTF-8"));
            byte[] s = m.digest();
            StringBuilder srcBuilder = new StringBuilder();
            for (byte b : s) {
                srcBuilder.append(Integer.toHexString((0x000000ff & b) | 0xffffff00).substring(6));
            }
            src = srcBuilder.toString();
        } catch (Exception e) {
            Log.e(TAG, "md5 failed", e);
        }
        return src;
    }

    public TtsRunStats getLastStats() {
        return lastStats;
    }

    private MemorySnapshot captureMemory() {
        Runtime rt = Runtime.getRuntime();
        long javaUsedBytes = rt.totalMemory() - rt.freeMemory();
        long nativeBytes = Debug.getNativeHeapAllocatedSize();

        long availBytes = -1;
        try {
            ActivityManager am = (ActivityManager) appContext.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                availBytes = mi.availMem;
            }
        } catch (Throwable ignore) {
        }

        return new MemorySnapshot(javaUsedBytes, nativeBytes, availBytes);
    }

    private static String toMb(long bytes) {
        if (bytes < 0) return "N/A";
        return String.format(Locale.US, "%.2f", bytes / (1024.0 * 1024.0));
    }

    public static final class TtsRunStats {
        public final boolean fromCache;
        public final boolean success;
        public final long durationMs;
        public final MemorySnapshot before;
        public final MemorySnapshot after;

        private TtsRunStats(boolean fromCache, boolean success, long durationMs, MemorySnapshot before, MemorySnapshot after) {
            this.fromCache = fromCache;
            this.success = success;
            this.durationMs = durationMs;
            this.before = before;
            this.after = after;
        }

        static TtsRunStats empty() {
            MemorySnapshot s = new MemorySnapshot(-1, -1, -1);
            return new TtsRunStats(false, false, -1, s, s);
        }

        static TtsRunStats cacheHit(MemorySnapshot now) {
            return new TtsRunStats(true, true, 0, now, now);
        }

        static TtsRunStats synthesized(long durationMs, MemorySnapshot before, MemorySnapshot after, boolean success) {
            return new TtsRunStats(false, success, durationMs, before, after);
        }

        public String toDebugText() {
            if (fromCache) {
                return "TTS cache hit | avail=" + toMb(after.availBytes) + "MB | java=" + toMb(after.javaUsedBytes)
                        + "MB | native=" + toMb(after.nativeBytes) + "MB";
            }
            String status = success ? "ok" : "failed";
            return "TTS " + status + " | infer=" + durationMs + "ms"
                    + " | avail=" + toMb(after.availBytes) + "MB"
                    + " | java=" + toMb(after.javaUsedBytes) + "MB"
                    + " | native=" + toMb(after.nativeBytes) + "MB"
                    + " | dJava=" + toMb(after.javaUsedBytes - before.javaUsedBytes) + "MB"
                    + " | dNative=" + toMb(after.nativeBytes - before.nativeBytes) + "MB";
        }
    }

    public static final class MemorySnapshot {
        public final long javaUsedBytes;
        public final long nativeBytes;
        public final long availBytes;

        MemorySnapshot(long javaUsedBytes, long nativeBytes, long availBytes) {
            this.javaUsedBytes = javaUsedBytes;
            this.nativeBytes = nativeBytes;
            this.availBytes = availBytes;
        }
    }
}
