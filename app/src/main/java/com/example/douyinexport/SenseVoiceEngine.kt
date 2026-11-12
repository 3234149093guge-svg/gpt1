package com.example.douyinexport

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File

/** Local ONNX SenseVoice inference. No server, API key or upload. */
class SenseVoiceEngine(modelDir: String) : AutoCloseable {
    private val recognizer: OfflineRecognizer

    init {
        val model = File(modelDir, "model.int8.onnx")
        val tokens = File(modelDir, "tokens.txt")
        require(model.isFile && tokens.isFile) { "内置识别模型不完整" }
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = model.absolutePath,
                    language = "zh",
                    useInverseTextNormalization = true
                ),
                tokens = tokens.absolutePath,
                numThreads = 2,
                provider = "cpu"
            )
        )
        recognizer = OfflineRecognizer(config = config)
    }

    fun recognize(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    override fun close() { recognizer.release() }
}
