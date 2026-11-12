package dev.turkce.olcum

import java.io.File

/** Anything that turns one English line into Turkish. */
interface LineTranslator : AutoCloseable {
    fun translate(source: String): String
}

/**
 * Firefox's tiny English to Turkish model on the Bergamot engine, built for 32-bit ARM from `motor/`.
 * It runs on the calling thread and uses one CPU core.
 */
class BergamotTranslator(root: File) : LineTranslator {
    private val handle: Long

    init {
        System.loadLibrary("turkce_mt")
        val yaml = """
            models:
              - $root/model.entr.intgemm.alphas.bin
            vocabs:
              - $root/vocab.entr.spm
              - $root/vocab.entr.spm
            shortlist:
              - $root/lex.50.50.entr.s2t.bin
              - false
            beam-size: 1
            normalize: 1.0
            word-penalty: 0
            max-length-break: 128
            mini-batch-words: 1024
            max-length-factor: 2.0
            skip-cost: true
            cpu-threads: 1
            quiet: true
            quiet-translation: true
            gemm-precision: int8shiftAlphaAll
            alignment: soft
        """.trimIndent()
        handle = open(yaml.toByteArray())
    }

    override fun translate(source: String): String = String(translate(handle, source.toByteArray()))

    override fun close() = close(handle)

    private companion object {
        @JvmStatic private external fun open(yaml: ByteArray): Long
        @JvmStatic private external fun translate(handle: Long, source: ByteArray): ByteArray
        @JvmStatic private external fun close(handle: Long)
    }
}
