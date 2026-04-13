package com.example.Amadeus;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 录音工具：16kHz 单声道 PCM，用于 SenseVoice 识别
 */
public class AudioRecorder {
    private static final String TAG = "AudioRecorder";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    private AudioRecord audioRecord;
    private volatile boolean isRecording = false;
    private Thread recordThread;
    private final int bufferSize;

    public AudioRecorder() {
        // 获取最小缓冲区大小
        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING);
        if (minBufferSize <= 0) {
            Log.e(TAG, "Invalid buffer size: " + minBufferSize);
            // 给一个默认安全值，避免 crash，虽然可能无法录音
            bufferSize = 4096;
        } else {
            bufferSize = minBufferSize;
        }
    }

    public int getSampleRate() {
        return SAMPLE_RATE;
    }

    public boolean isRecording() {
        return isRecording;
    }

    /**
     * 开始录音并写入 WAV 文件，直至 stopRecording 被调用
     * @param outputFile 输出 WAV 文件
     * @param callback 录音结束回调（在主线程）
     */
    public void startRecording(File outputFile, Runnable callback) {
        if (isRecording) return;
        
        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize * 2);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败");
                if (callback != null) callback.run();
                return;
            }

            isRecording = true;
            // 先开始录音，再启动线程读取，避免竞态条件
            audioRecord.startRecording();
            
            recordThread = new Thread(() -> {
                writeAudioDataToFile(outputFile, callback);
            });
            recordThread.start();
            
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "创建 AudioRecord 失败", e);
            isRecording = false;
            if (callback != null) callback.run();
        }
    }

    private void writeAudioDataToFile(File outputFile, Runnable callback) {
        FileOutputStream fos = null;
        int totalBytes = 0;
        
        try {
            fos = new FileOutputStream(outputFile);
            // 先写 44 字节占位
            fos.write(new byte[44]);
            
            byte[] buffer = new byte[bufferSize];
            
            while (isRecording && audioRecord != null) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    fos.write(buffer, 0, read);
                    totalBytes += read;
                } else if (read < 0) {
                     Log.e(TAG, "AudioRecord read error: " + read);
                     break; 
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "录音写入失败", e);
        } finally {
            try {
                if (fos != null) fos.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        
        // 录音循环结束后，写入 WAV 头
        if (totalBytes > 0 && outputFile.exists()) {
             try {
                writeWavHeader(outputFile, totalBytes, SAMPLE_RATE, 1, 16);
            } catch (IOException e) {
                Log.e(TAG, "写入 WAV 头失败", e);
            }
        } else {
             Log.w(TAG, "录音文件为空或未生成");
        }

        if (callback != null) {
            callback.run();
        }
    }

    /**
     * 停止录音
     */
    public void stopRecording() {
        isRecording = false;
        
        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
                audioRecord.release();
            } catch (IllegalStateException e) {
                Log.e(TAG, "停止录音时出错", e);
            }
            audioRecord = null;
        }
        
        // 等待录音线程结束
        if (recordThread != null) {
            try {
                recordThread.join(1000); // 最多等待1秒
            } catch (InterruptedException ignore) {}
            recordThread = null;
        }
    }

    private void writeWavHeader(File file, int dataLen, int sampleRate, int channels, int bitsPerSample) throws IOException {
        long byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        
        // 使用小端字节序
        byte[] header = new byte[44];
        
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        int totalDataLen = dataLen + 36;
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0; // PCM chunk size
        header[20] = 1; header[21] = 0; // Audio format 1 = PCM
        header[22] = (byte) channels; header[23] = 0;
        
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        
        header[32] = (byte) blockAlign; header[33] = 0;
        header[34] = (byte) bitsPerSample; header[35] = 0;
        
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (dataLen & 0xff);
        header[41] = (byte) ((dataLen >> 8) & 0xff);
        header[42] = (byte) ((dataLen >> 16) & 0xff);
        header[43] = (byte) ((dataLen >> 24) & 0xff);

        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(0);
            raf.write(header);
        }
    }
}
