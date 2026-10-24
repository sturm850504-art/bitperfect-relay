package com.bitperfect.relay.capture

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * AudioRecord(ENCODING_PCM_FLOAT, 스테레오)에서 받은 float 배열을
 * float32 WAV (RIFF/WAVE, WAVE_FORMAT_IEEE_FLOAT) 로 쓴다.
 * 비트 보존 — 원본과 비교 가능.
 */
class WavWriter(
    file: File,
    private val sampleRate: Int,
    private val numChannels: Int,
) : AutoCloseable {

    private val stream = FileOutputStream(file)
    private val channel = stream.channel
    private var dataSize = 0L

    init {
        // 44바이트 헤더 자리 선점
        stream.write(ByteArray(44))
    }

    /** 스테레오 인터리브드 float (좌, 우, 좌, 우 ...) 를 쓴다. */
    fun write(floats: FloatArray) {
        val buf = ByteBuffer.allocateDirect(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in floats) buf.putFloat(f)

        val bytes = ByteArray(floats.size * 4)
        buf.rewind()
        buf.get(bytes)
        stream.write(bytes)
        dataSize += bytes.size.toLong()
    }

    override fun close() {
        stream.flush()
        val blockAlign = numChannels * 4
        val byteRate = sampleRate * blockAlign

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt((dataSize + 36).toInt())
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(3) // WAVE_FORMAT_IEEE_FLOAT
        header.putShort(numChannels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(32)
        header.put("data".toByteArray())
        header.putInt(dataSize.toInt())

        // 파일 맨 처음으로 돌아가서 헤더 덮어쓰기
        channel.position(0)
        channel.write(ByteBuffer.wrap(header.array()))
        stream.close()
    }
}
