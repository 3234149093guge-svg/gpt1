package com.example.douyinexport

import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import java.io.File

/**
 * Same Qwen3-ASR-0.6B INT8 ONNX weights and decoding parameters as v0.7.
 * The only variable is CPU thread count (2, 3 or 4).
 */
class Qwen3AsrEngine(modelDir: String, val threads: Int) : AutoCloseable {
    private val recognizer: OfflineRecognizer

    init {
        require(threads in 2..4) { "线程必须为 2、3 或 4" }
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
                numThreads = threads,
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

    override fun close() = recognizer.release()
}
