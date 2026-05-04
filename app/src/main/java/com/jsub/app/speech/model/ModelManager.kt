package com.jsub.app.speech.model

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * SenseVoice ONNX模型管理器
 *
 * 负责模型的检测、路径管理。
 * 支持用户手动下载的 model_quant.onnx 模型文件。
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"

        /** 模型子目录名 */
        const val MODEL_SUBDIR = "sensevoice"

        /** 用户通过 OpenClaw 下载的模型文件名 */
        const val MODEL_FILENAME = "model_quant.onnx"

        /** 配套文件 */
        val REQUIRED_FILES = listOf(
            "model_quant.onnx",
            "tokens.json",
            "config.yaml",
            "am.mvn",
            "configuration.json"
        )
    }

    /** 模型存储目录: /Android/data/com.jsu.app/files/models/sensevoice/ */
    private val modelDir: File by lazy {
        File(context.getExternalFilesDir(null), "models/$MODEL_SUBDIR").apply { mkdirs() }
    }

    /** 模型文件完整路径 */
    val modelPath: String by lazy {
        File(modelDir, MODEL_FILENAME).absolutePath
    }

    /**
     * 检查模型是否已下载
     * 检测 model_quant.onnx 是否存在且大小合理（>50MB）
     */
    fun isModelDownloaded(): Boolean {
        val modelFile = File(modelDir, MODEL_FILENAME)
        val exists = modelFile.exists()
        val size = modelFile.length()
        val valid = exists && size > 50_000_000L // 至少 50MB
        Log.i(TAG, "Check model: exists=$exists, size=${size / 1024 / 1024}MB, valid=$valid, path=$modelPath")
        return valid
    }

    /**
     * 获取模型目录路径（供用户参考）
     */
    fun getModelDirPath(): String = modelDir.absolutePath

    /**
     * 获取缺失的文件列表
     */
    fun getMissingFiles(): List<String> {
        return REQUIRED_FILES.filter { filename ->
            !File(modelDir, filename).exists()
        }
    }
}
