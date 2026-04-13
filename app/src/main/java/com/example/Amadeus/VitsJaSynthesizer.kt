package com.example.Amadeus

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import com.example.moereng.Vits
import com.example.moereng.utils.text.JapaneseTextUtils
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class VitsJaSynthesizer(private val context: Context) {
    private var initialized = false
    private var textUtils: JapaneseTextUtils? = null
    private var samplingRate = 22050
    private var multi = false
    private var sid = 0
    private var threads = 1

    fun warmup(): Boolean {
        return ensureReady()
    }

    /**
     * 合成文本到内存的浮点PCM数据（推荐）
     */
    fun synthesizeToMemory(text: String): FloatArray? {
        if (text.isBlank()) {
            return null
        }
        if (!ensureReady()) {
            return null
        }

        val timeStart = System.nanoTime()
        
        // 步骤1: 文本预处理
        val timePreprocessStart = System.nanoTime()
        val inputs = textUtils?.convertText(text, context) ?: return null
        if (inputs.isEmpty()) {
            return null
        }
        val timePreprocessMs = (System.nanoTime() - timePreprocessStart) / 1_000_000L
        
        val audioChunks = ArrayList<FloatArray>()
        var totalSamples = 0
        threads = resolveThreads()
        
        // 步骤2: VITS推理
        val timeInferenceStart = System.nanoTime()
        for ((idx, ids) in inputs.withIndex()) {
            val timeChunkStart = System.nanoTime()
            val out = Vits.forward(ids, false, multi, sid, 0.667f, 0.8f, 1.0f, threads)
            val timeChunkMs = (System.nanoTime() - timeChunkStart) / 1_000_000L
            Log.d(TAG, "VITS chunk $idx: ${timeChunkMs}ms, samples=${out?.size ?: 0}")
            
            if (out != null && out.isNotEmpty()) {
                audioChunks.add(out)
                totalSamples += out.size
            }
        }
        val timeInferenceMs = (System.nanoTime() - timeInferenceStart) / 1_000_000L

        if (totalSamples <= 0) {
            return null
        }

        // 步骤3: 音频合并
        val timeMergeStart = System.nanoTime()
        val merged = FloatArray(totalSamples)
        var offset = 0
        for (chunk in audioChunks) {
            System.arraycopy(chunk, 0, merged, offset, chunk.size)
            offset += chunk.size
        }
        val timeMergeMs = (System.nanoTime() - timeMergeStart) / 1_000_000L
        
        val timeTotal = (System.nanoTime() - timeStart) / 1_000_000L
        Log.i(TAG, "TTS synthesis breakdown: " +
            "preprocess=${timePreprocessMs}ms, " +
            "inference=${timeInferenceMs}ms, " +
            "merge=${timeMergeMs}ms, " +
            "total=${timeTotal}ms, " +
            "chunks=${inputs.size}, " +
            "threads=$threads, " +
            "text_len=${text.length}")
        
        return merged
    }

    @Synchronized
    private fun ensureReady(): Boolean {
        if (initialized) {
            return true
        }

        val timeStart = System.nanoTime()
        return try {
            val configText = readAssetText("vits_ja/config.json")
            val config = JSONObject(configText)
            val data = config.getJSONObject("data")
            val cleaner = data.getJSONArray("text_cleaners").getString(0)
            if (!cleaner.contains("japanese_cleaners")) {
                Log.e(TAG, "Only japanese_cleaners is supported, got: $cleaner")
                return false
            }

            val symbolsJson = config.getJSONArray("symbols")
            val symbols = ArrayList<String>(symbolsJson.length())
            for (i in 0 until symbolsJson.length()) {
                symbols.add(symbolsJson.getString(i))
            }

            samplingRate = data.optInt("sampling_rate", 22050)
            val nSpeakers = data.optInt("n_speakers", 1)
            multi = nSpeakers > 1
            val nVocab = data.optInt("n_vocabs", symbols.size)

            // Use a small bounded thread count to improve latency without starving UI/audio.
            threads = resolveThreads()

            val modelDir = File(context.filesDir, "vits_ja")
            if (!modelDir.exists()) {
                modelDir.mkdirs()
            }
            val timeCopyStart = System.nanoTime()
            copyAssetDir("vits_ja", modelDir)
            val timeCopyMs = (System.nanoTime() - timeCopyStart) / 1_000_000L

            textUtils = JapaneseTextUtils(symbols, cleaner, context.assets)

            val modelPath = modelDir.absolutePath + File.separator
            val timeInitStart = System.nanoTime()
            val ok = Vits.init_vits(context.assets, modelPath, false, multi, nVocab)
            val timeInitMs = (System.nanoTime() - timeInitStart) / 1_000_000L
            
            initialized = ok
            val timeTotal = (System.nanoTime() - timeStart) / 1_000_000L
            if (!ok) {
                Log.e(TAG, "VITS init failed. Check assets/vits_ja and ncnn params in assets/single or assets/multi")
            } else {
                Log.i(TAG, "VITS init succeeded: copy=${timeCopyMs}ms, " +
                    "init_vits=${timeInitMs}ms, total=${timeTotal}ms, " +
                    "multi=$multi, n_vocab=$nVocab")
            }
            ok
        } catch (e: Throwable) {
            Log.e(TAG, "ensureReady failed", e)
            false
        }
    }

    private fun readAssetText(assetPath: String): String {
        context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use {
            return it.readText()
        }
    }

    private fun copyAssetDir(assetPath: String, destDir: File) {
        val list = context.assets.list(assetPath) ?: return
        for (name in list) {
            val subPath = "$assetPath/$name"
            val out = File(destDir, name)
            val children = context.assets.list(subPath)
            if (children != null && children.isNotEmpty()) {
                if (!out.exists()) {
                    out.mkdirs()
                }
                copyAssetDir(subPath, out)
            } else {
                context.assets.open(subPath).use { input ->
                    FileOutputStream(out).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun resolveThreads(): Int {
        val autoThreads = 1

        return try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val raw = prefs.getString(PREF_TTS_THREADS, PREF_TTS_THREADS_AUTO) ?: PREF_TTS_THREADS_AUTO
            val user = raw.toIntOrNull() ?: 0
            if (user > 0) {
                user.coerceIn(1, MAX_TTS_THREADS)
            } else {
                autoThreads
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Resolve TTS threads from settings failed, fallback to auto", e)
            autoThreads
        }
    }

    companion object {
        private const val TAG = "VitsJaSynthesizer"
        private const val PREF_TTS_THREADS = "tts_threads"
        private const val PREF_TTS_THREADS_AUTO = "0"
        private const val MAX_TTS_THREADS = 8
    }
}
