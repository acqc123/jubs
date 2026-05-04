package com.jsub.app.speech.engine

import ai.onnxruntime.*
import android.content.Context
import android.util.Log
import com.jsub.app.speech.model.CtcDecoder
import com.jsub.app.speech.model.MelFeatureExtractor
import com.jsub.app.speech.model.ModelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.FloatBuffer

/**
 * SenseVoice本地ONNX语音识别引擎
 *
 * 在Android设备上本地运行SenseVoice Small模型，
 * 完全离线，不需要网络连接。
 */
class SenseVoiceEngine(private val context: Context) : SpeechRecognitionEngine {

    companion object {
        private const val TAG = "SenseVoiceEngine"
        private const val SAMPLE_RATE = 16000
        private const val STREAM_BUFFER_MS = 3000L
    }

    override val name: String = "SenseVoice（本地离线）"
    override val requiresNetwork: Boolean = false
    override val requiresModelDownload: Boolean = true
    override var isModelReady: Boolean = false

    private val modelManager = ModelManager(context)
    private val melExtractor = MelFeatureExtractor(sampleRate = SAMPLE_RATE)
    private val ctcDecoder = CtcDecoder()

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    /**
     * 初始化引擎
     * 1. 检查/下载模型
     * 2. 加载ONNX Runtime
     * 3. 加载词汇表
     */
    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. 检查模型是否存在
            if (!modelManager.isModelDownloaded()) {
                val modelDir = modelManager.getModelDirPath()
                val missing = modelManager.getMissingFiles()
                Log.e(TAG, "SenseVoice 模型未找到！")
                Log.e(TAG, "模型目录: $modelDir")
                Log.e(TAG, "缺失文件: $missing")
                Log.e(TAG, "请将 model_quant.onnx 及配套文件放到: $modelDir")
                return@withContext false
            }
            Log.i(TAG, "SenseVoice 模型已找到: ${modelManager.modelPath}")

            // 2. 加载ONNX Runtime
            ortEnvironment = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2) // 使用2个线程
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            ortSession = ortEnvironment?.createSession(modelManager.modelPath, sessionOptions)

            // 3. 加载词汇表（简化：从assets加载）
            loadVocabulary()

            isModelReady = true
            Log.i(TAG, "SenseVoice engine initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SenseVoice engine", e)
            isModelReady = false
            false
        }
    }

    override suspend fun recognize(audioData: ByteArray): String = withContext(Dispatchers.IO) {
        if (!isModelReady) {
            Log.e(TAG, "Engine not initialized")
            return@withContext "[引擎未初始化，请在设置中下载模型]"
        }

        try {
            // 1. Mel特征提取
            val melSpecs = melExtractor.extract(audioData)

            // 2. 构建ONNX输入张量
            val inputTensor = createInputTensor(melSpecs)

            // 3. ONNX推理
            val results = ortSession?.run(mapOf("input" to inputTensor))

            // 4. 获取输出并解码
            val outputTensor = results?.get(0) as? OnnxTensor
            val probs = outputTensor?.floatBuffer?.let { buffer ->
                val shape = outputTensor.info.shape // [batch, time, vocab]
                val timeSteps = shape[1].toInt()
                val vocabSize = shape[2].toInt()
                Array(timeSteps) { t ->
                    FloatArray(vocabSize) { v ->
                        buffer.get(t * vocabSize + v)
                    }
                }
            }

            // 5. CTC解码
            val text = if (probs != null) {
                ctcDecoder.greedyDecode(probs)
            } else {
                ""
            }

            inputTensor.close()
            outputTensor?.close()
            results?.close()

            text
        } catch (e: Exception) {
            Log.e(TAG, "Recognition failed", e)
            "[识别错误: ${e.localizedMessage}]"
        }
    }

    override fun streamingRecognize(audioFlow: Flow<ByteArray>): Flow<String> = flow {
        val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
        val targetBytes = (SAMPLE_RATE * 2 * STREAM_BUFFER_MS / 1000).toInt()

        audioFlow.collect { chunk ->
            buffer.put(chunk)
            if (buffer.position() >= targetBytes) {
                val audioData = ByteArray(buffer.position())
                buffer.flip()
                buffer.get(audioData)
                buffer.clear()

                val result = recognize(audioData)
                if (result.isNotBlank() && !result.startsWith("[")) {
                    emit(result)
                }
            }
        }

        // 处理剩余
        if (buffer.position() > 1600) {
            val audioData = ByteArray(buffer.position())
            buffer.flip()
            buffer.get(audioData)
            val result = recognize(audioData)
            if (result.isNotBlank() && !result.startsWith("[")) {
                emit(result)
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun release() {
        try {
            ortSession?.close()
            ortEnvironment?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing resources", e)
        }
        ortSession = null
        ortEnvironment = null
        isModelReady = false
    }

    /** 创建ONNX输入张量 */
    private fun createInputTensor(melSpecs: Array<FloatArray>): OnnxTensor {
        val batchSize = 1
        val nFrames = melSpecs.size
        val nMels = melSpecs[0].size

        val floatBuffer = FloatBuffer.allocate(batchSize * nFrames * nMels)
        for (frame in melSpecs) {
            floatBuffer.put(frame)
        }
        floatBuffer.flip()

        return OnnxTensor.createTensor(
            ortEnvironment,
            floatBuffer,
            longArrayOf(batchSize.toLong(), nFrames.toLong(), nMels.toLong())
        )
    }

    /** 加载词汇表 */
    private fun loadVocabulary() {
        if (ctcDecoder.isVocabularyLoaded()) return

        try {
            // 从assets加载vocab文件（如果存在）
            context.assets.list("")?.find { it == "sensevoice_vocab.txt" }?.let { vocabFile ->
                context.assets.open(vocabFile).bufferedReader().useLines { lines ->
                    ctcDecoder.loadVocabulary(lines.toList())
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load vocabulary file, using default", e)
        }
    }
}
