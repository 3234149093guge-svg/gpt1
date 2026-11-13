package com.example.douyinexport

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import java.io.File

/**
 * Qwen3-ASR-0.6B INT8 offline inference via sherpa-onnx 1.12.34.
 * Model path is on the device. Audio is never sent to a server.
 */
class Qwen3AsrEngine(modelDir: String) : AutoCloseable {
    private val recognizer: OfflineRecognizer

    init {
        val root = File(modelDir)
        val frontend = File(root, "conv_frontend.onnx")
        val encoder = File(root, "encoder.int8.onnx")
        val decoder = File(root, "decoder.int8.onnx")
        val tokenizer = File(root, "tokenizer")
        require(frontend.isFile && encoder.isFile && decoder.isFile
                && File(tokenizer, "vocab.json").isFile) {
            "Qwen3-ASR-0.6B INT8 模型文件缺失，请重新安装模型"
        }
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                qwen3Asr = OfflineQwen3AsrModelConfig(
                    convFrontend = frontend.absolutePath,
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    tokenizer = tokenizer.absolutePath,
                    maxTotalLen = 512,
                    maxNewTokens = 256
                ),
                tokens = "",
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

    override fun close() {
        recognizer.release()
    }
}
