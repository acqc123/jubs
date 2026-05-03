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
 * 负责模型的下载、校验和路径管理。
 */
class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
        
        /** 模型下载地址（ModelScope CDN） */
        private const val MODEL_URL = "https://www.modelscope.cn/models/iic/sensevoice-small-onnx/resolve/master/model.onnx"
        
        /** 模型文件名 */
        const val MODEL_FILENAME = "sensevoice_small.onnx"
        
        /** 模型期望大小（字节），用于校验 */
        private const val MODEL_EXPECTED_SIZE = 200_000_000L // ~200MB
    }

    /** 模型存储目录 */
    private val modelDir: File by lazy {
        File(context.getExternalFilesDir(null), "models").apply { mkdirs() }
    }

    /** 模型文件路径 */
    val modelPath: String by lazy {
        File(modelDir, MODEL_FILENAME).absolutePath
    }

    /** 模型是否已下载 */
    fun isModelDownloaded(): Boolean {
        val modelFile = File(modelDir, MODEL_FILENAME)
        return modelFile.exists() && modelFile.length() > MODEL_EXPECTED_SIZE * 0.8
    }

    /**
     * 下载模型
     * @param onProgress (downloadedBytes, totalBytes) -> Unit
     * @return true if success
     */
    suspend fun downloadModel(onProgress: ((Long, Long) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder().url(MODEL_URL).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Download failed: HTTP ${response.code}")
                    return@withContext false
                }

                val body = response.body ?: return@withContext false
                val totalBytes = body.contentLength()
                val tempFile = File(modelDir, "${MODEL_FILENAME}.tmp")

                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var downloadedBytes = 0L
                    val inputStream = body.byteStream()

                    var read: Int
                    while (inputStream.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        onProgress?.invoke(downloadedBytes, totalBytes)
                    }
                }

                // 重命名为最终文件
                tempFile.renameTo(File(modelDir, MODEL_FILENAME))
                Log.i(TAG, "Model downloaded: $modelPath (${File(modelDir, MODEL_FILENAME).length()} bytes)")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            false
        }
    }

    /** 获取模型下载进度百分比 */
    fun getDownloadProgress(): Int {
        val modelFile = File(modelDir, MODEL_FILENAME)
        if (!modelFile.exists()) return 0
        return ((modelFile.length() * 100) / MODEL_EXPECTED_SIZE).toInt().coerceIn(0, 100)
    }

    /** 删除模型 */
    fun deleteModel() {
        File(modelDir, MODEL_FILENAME).delete()
    }
}
