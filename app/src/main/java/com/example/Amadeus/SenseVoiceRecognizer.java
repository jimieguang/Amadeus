package com.example.Amadeus;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * SenseVoice 本地语音识别（基于 sherpa-onnx）
 * 模型需放在 assets/sensevoice/ 下：model.int8.onnx、tokens.txt
 */
public class SenseVoiceRecognizer {
    private static final String TAG = "SenseVoiceRecognizer";
    private static final String MODEL_DIR = "sensevoice";
    private static final String[] MODEL_FILES = {"model.int8.onnx", "model_int8.onnx"};
    private static final String TOKENS_FILE = "tokens.txt";

    private final Context context;
    private OfflineRecognizer recognizer;
    private boolean initialized = false;

    public SenseVoiceRecognizer(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 初始化识别器（需在后台线程调用，避免阻塞 UI）
     */
    public synchronized boolean init() {
        if (initialized) return true;

        try {
            File modelDir = getModelDir();
            if (modelDir == null) {
                Log.e(TAG, "模型目录不存在，请将 model.int8.onnx 和 tokens.txt 放入 assets/sensevoice/");
                return false;
            }

            File modelFile = null;
            for (String name : MODEL_FILES) {
                File f = new File(modelDir, name);
                if (f.exists()) { modelFile = f; break; }
            }
            File tokensPath = new File(modelDir, TOKENS_FILE);
            if (modelFile == null || !tokensPath.exists()) {
                Log.e(TAG, "模型文件缺失: " + modelDir.getAbsolutePath() + " (需 model.int8.onnx 或 model_int8.onnx)");
                return false;
            }

            // Kotlin data class API (无 builder)，使用构造函数
            OfflineSenseVoiceModelConfig senseVoice = new OfflineSenseVoiceModelConfig(
                    modelFile.getAbsolutePath(), "", true, new com.k2fsa.sherpa.onnx.QnnConfig());

            OfflineModelConfig modelConfig = new OfflineModelConfig(
                    new com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig(),
                    new com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig(),
                    new com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig(),
                    new com.k2fsa.sherpa.onnx.OfflineFireRedAsrModelConfig(),
                    new com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig(),
                    new com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig(),
                    senseVoice,
                    new com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig(),
                    new com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig(),
                    new com.k2fsa.sherpa.onnx.OfflineWenetCtcModelConfig(),
                    new com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig(),
                    new com.k2fsa.sherpa.onnx.OfflineMedAsrCtcModelConfig(),
                    new com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig(),
                    new com.k2fsa.sherpa.onnx.OfflineFireRedAsrCtcModelConfig(),
                    new com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig(),
                    "", 2, false, "cpu", "", tokensPath.getAbsolutePath(), "", "");

            OfflineRecognizerConfig config = new OfflineRecognizerConfig(
                    new FeatureConfig(), modelConfig, new HomophoneReplacerConfig(),
                    "greedy_search", 4, "", 1.5f, "", "", 0.0f);

            // null = 从文件路径加载；传 AssetManager 则从 assets 加载
            recognizer = new OfflineRecognizer(null, config);
            initialized = true;
            Log.i(TAG, "SenseVoice 初始化成功");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "SenseVoice 初始化失败", e);
            return false;
        }
    }

    /**
     * 从 PCM 样本识别（16kHz, mono, int16）
     * @param samples PCM 数据
     * @param sampleRate 采样率，建议 16000
     * @return 识别文本，失败返回空字符串
     */
    public String recognize(short[] samples, int sampleRate) {
        if (!initialized || recognizer == null) {
            if (!init()) return "";
        }
        float[] floatSamples = new float[samples.length];
        for (int i = 0; i < samples.length; i++) {
            floatSamples[i] = samples[i] / 32768.0f;
        }
        return recognizeFloat(floatSamples, sampleRate);
    }

    private String recognizeFloat(float[] samples, int sampleRate) {
        try {
            OfflineStream stream = recognizer.createStream();
            stream.acceptWaveform(samples, sampleRate);
            recognizer.decode(stream);
            String text = recognizer.getResult(stream).getText();
            stream.release();
            return text != null ? text.trim() : "";
        } catch (Exception e) {
            Log.e(TAG, "识别失败", e);
            return "";
        }
    }

    /**
     * 从 WAV 文件识别
     */
    public String recognizeFromFile(File wavFile) {
        if (!initialized || recognizer == null) {
            if (!init()) return "";
        }
        if (wavFile == null || !wavFile.exists()) return "";

        try {
            short[] samples = readWavSamples(wavFile);
            if (samples == null || samples.length == 0) return "";
            return recognize(samples, 16000);
        } catch (Exception e) {
            Log.e(TAG, "文件识别失败", e);
            return "";
        }
    }

    private short[] readWavSamples(File wavFile) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(wavFile, "r")) {
            byte[] header = new byte[44];
            raf.readFully(header);
            // 简单校验 WAV 头
            if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F') {
                return null;
            }
            int dataLen = (int) (raf.length() - 44);
            byte[] bytes = new byte[dataLen];
            raf.readFully(bytes);
            short[] samples = new short[dataLen / 2];
            for (int i = 0; i < samples.length; i++) {
                int lo = bytes[i * 2] & 0xFF;
                int hi = bytes[i * 2 + 1] & 0xFF;
                samples[i] = (short) (lo | (hi << 8));
            }
            return samples;
        }
    }

    private File getModelDir() {
        File cacheDir = context.getExternalFilesDir(null);
        if (cacheDir == null) cacheDir = context.getFilesDir();
        File modelDir = new File(cacheDir, MODEL_DIR);
        for (String name : MODEL_FILES) {
            if (modelDir.exists() && new File(modelDir, name).exists()) return modelDir;
        }

        // 从 assets 复制
        try {
            String[] list = context.getAssets().list(MODEL_DIR);
            if (list == null || list.length == 0) return null;
            modelDir.mkdirs();
            copyAssetDir(MODEL_DIR, modelDir);
            for (String name : MODEL_FILES) {
                if (new File(modelDir, name).exists()) return modelDir;
            }
            return null;
        } catch (IOException e) {
            Log.e(TAG, "复制模型失败", e);
            return null;
        }
    }

    private void copyAssetDir(String assetPath, File destDir) throws IOException {
        String[] files = context.getAssets().list(assetPath);
        if (files == null) return;
        for (String name : files) {
            String subPath = assetPath + "/" + name;
            File dest = new File(destDir, name);
            if (context.getAssets().list(subPath) != null && context.getAssets().list(subPath).length > 0) {
                dest.mkdirs();
                copyAssetDir(subPath, dest);
            } else {
                try (InputStream is = context.getAssets().open(subPath);
                     FileOutputStream os = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        }
    }

    public void release() {
        if (recognizer != null) {
            try {
                recognizer.release();
            } catch (Exception e) {
                Log.e(TAG, "release error", e);
            }
            recognizer = null;
        }
        initialized = false;
    }

    public boolean isInitialized() {
        return initialized;
    }
}
