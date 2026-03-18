package info.skyblond.recorder.spotify.wav

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.min

/**
 * Reads audio samples from WAV and RF64 files.
 *
 * Parses the file header on construction to extract format information
 * (sample rate, channels, bit depth, etc.) and locate the data chunk.
 *
 * Each [readSamples] call opens the file, seeks to the requested position,
 * reads and converts the samples, then closes the file.
 *
 * Only stereo (2-channel) files are supported. Other channel counts
 * will cause an [IllegalArgumentException] on construction.
 */
class WavSampleReader(private val file: File) {

    companion object {
        /** WAV audio format tag: PCM integer */
        const val AUDIO_FORMAT_PCM = 1

        /** WAV audio format tag: IEEE float */
        const val AUDIO_FORMAT_IEEE_FLOAT = 3

        /** WAV audio format tag: WAVE_FORMAT_EXTENSIBLE (actual format in subFormat GUID) */
        private const val AUDIO_FORMAT_EXTENSIBLE = 0xFFFE

        private const val RIFF_MAGIC = "RIFF"
        private const val RF64_MAGIC = "RF64"
        private const val WAVE_ID = "WAVE"
    }

    /** Audio format tag (1 = PCM integer, 3 = IEEE float) */
    val audioFormat: Int

    /** Number of audio channels (always 2 for this reader) */
    val numChannels: Int

    /** Sample rate in Hz (e.g. 44100) */
    val sampleRate: Int

    /** Bits per sample (16, 24, or 32) */
    val bitsPerSample: Int

    /** Bytes per single sample (bitsPerSample / 8) */
    val bytesPerSample: Int

    /** Block align: bytes per sample frame across all channels */
    val blockAlign: Int

    /** Byte offset in the file where audio data begins */
    val dataChunkOffset: Long

    /** Size of the audio data in bytes */
    val dataChunkSize: Long

    /** Total duration of the audio in seconds */
    val duration: Double

    init {
        require(file.exists()) { "File does not exist: ${file.absolutePath}" }
        require(file.isFile) { "Not a regular file: ${file.absolutePath}" }

        var parsedAudioFormat: Int? = null
        var parsedNumChannels: Int? = null
        var parsedSampleRate: Int? = null
        var parsedBitsPerSample: Int? = null
        var parsedBlockAlign: Int? = null
        var parsedDataOffset: Long? = null
        var parsedDataSize: Long? = null
        var ds64DataSize: Long? = null

        RandomAccessFile(file, "r").use { raf ->
            // Read the RIFF/RF64 header (12 bytes)
            val headerBytes = ByteArray(12)
            raf.readFully(headerBytes)

            val isRf64 = when (val magic = String(headerBytes, 0, 4, Charsets.US_ASCII)) {
                RIFF_MAGIC -> false
                RF64_MAGIC -> true
                else -> throw IllegalArgumentException(
                    "Not a WAV file: expected RIFF or RF64 magic, got '$magic'"
                )
            }
            // skip 4 bytes of file size (already read as part of headerBytes)
            val waveId = String(headerBytes, 8, 4, Charsets.US_ASCII)
            require(waveId == WAVE_ID) {
                "Not a WAV file: expected WAVE identifier, got '$waveId'"
            }

            // Traverse chunks
            while (raf.filePointer < raf.length()) {
                val chunkIdBytes = ByteArray(4)
                if (raf.read(chunkIdBytes) < 4) break
                val chunkId = String(chunkIdBytes, Charsets.US_ASCII)

                val chunkSizeBytes = ByteArray(4)
                raf.readFully(chunkSizeBytes)
                val chunkSize = ByteBuffer.wrap(chunkSizeBytes)
                    .order(ByteOrder.LITTLE_ENDIAN).getInt().toLong() and 0xFFFFFFFFL

                val chunkDataStart = raf.filePointer

                when (chunkId) {
                    "ds64" -> {
                        if (isRf64) {
                            val ds64Bytes = ByteArray(24)
                            raf.readFully(ds64Bytes)
                            val ds64Buf = ByteBuffer.wrap(ds64Bytes).order(ByteOrder.LITTLE_ENDIAN)
                            ds64Buf.getLong() // riffSize (not needed)
                            ds64DataSize = ds64Buf.getLong()
                            // ds64Buf.getLong() // sampleCount (not needed)
                        }
                    }

                    "fmt " -> {
                        val fmtBytes = ByteArray(minOf(chunkSize.toInt(), 40))
                        raf.readFully(fmtBytes)
                        val fmtBuf = ByteBuffer.wrap(fmtBytes).order(ByteOrder.LITTLE_ENDIAN)
                        var rawAudioFormat = fmtBuf.getShort().toInt() and 0xFFFF
                        parsedNumChannels = fmtBuf.getShort().toInt() and 0xFFFF
                        parsedSampleRate = fmtBuf.getInt()
                        fmtBuf.getInt() // byteRate (not needed, we compute it)
                        parsedBlockAlign = fmtBuf.getShort().toInt() and 0xFFFF
                        parsedBitsPerSample = fmtBuf.getShort().toInt() and 0xFFFF

                        // Handle WAVE_FORMAT_EXTENSIBLE: actual format is in subFormat GUID
                        if (rawAudioFormat == AUDIO_FORMAT_EXTENSIBLE && chunkSize >= 40) {
                            fmtBuf.getShort() // cbSize (should be 22)
                            fmtBuf.getShort() // validBitsPerSample
                            fmtBuf.getInt()   // channelMask
                            // First 2 bytes of subFormat GUID are the actual format tag
                            rawAudioFormat = fmtBuf.getShort().toInt() and 0xFFFF
                        }
                        parsedAudioFormat = rawAudioFormat
                    }

                    "data" -> {
                        parsedDataOffset = raf.filePointer
                        parsedDataSize = if (isRf64 && chunkSize == 0xFFFFFFFFL) {
                            ds64DataSize
                                ?: throw IllegalStateException("RF64 data chunk found before ds64 chunk")
                        } else {
                            chunkSize
                        }
                    }
                }

                // Skip to next chunk (chunks are 2-byte aligned)
                val nextChunkPos = chunkDataStart + chunkSize + (chunkSize % 2)
                if (nextChunkPos > raf.length()) break
                raf.seek(nextChunkPos)
            }
        }

        // Validate all required fields were parsed
        audioFormat = parsedAudioFormat
            ?: throw IllegalArgumentException("WAV file missing fmt chunk")
        numChannels = parsedNumChannels!!
        sampleRate = parsedSampleRate!!
        bitsPerSample = parsedBitsPerSample!!
        blockAlign = parsedBlockAlign!!
        dataChunkOffset = parsedDataOffset
            ?: throw IllegalArgumentException("WAV file missing data chunk")
        dataChunkSize = parsedDataSize!!

        // Validate supported configurations
        require(numChannels == 2) {
            "Only stereo (2-channel) files are supported, got $numChannels channels"
        }
        require(audioFormat == AUDIO_FORMAT_PCM || audioFormat == AUDIO_FORMAT_IEEE_FLOAT) {
            "Unsupported audio format: $audioFormat (expected $AUDIO_FORMAT_PCM for PCM or $AUDIO_FORMAT_IEEE_FLOAT for IEEE float)"
        }
        when (audioFormat) {
            AUDIO_FORMAT_IEEE_FLOAT -> require(bitsPerSample == 32) {
                "IEEE float format only supports 32 bits per sample, got $bitsPerSample"
            }

            AUDIO_FORMAT_PCM -> require(bitsPerSample in intArrayOf(16, 24, 32)) {
                "PCM format supports 16, 24, or 32 bits per sample, got $bitsPerSample"
            }
        }

        bytesPerSample = bitsPerSample / 8
        duration = dataChunkSize.toDouble() / (sampleRate.toLong() * numChannels * bytesPerSample)
    }

    /**
     * Read audio samples in the given time range.
     *
     * Each call independently opens the file, reads the requested segment,
     * and closes the file.
     *
     * @param startTime start of the range in seconds (inclusive), must be >= 0
     * @param endTime end of the range in seconds (exclusive), must be <= [duration]
     * @return a [Pair] of [FloatArray]s: (leftChannel, rightChannel).
     *         All sample values are normalized to the [-1.0, 1.0] range.
     * @throws IllegalArgumentException if the time range is invalid
     */
    fun readSamples(startTime: Double, endTime: Double): Pair<FloatArray, FloatArray> {
        require(startTime >= 0.0) { "startTime must be >= 0, got $startTime" }
        require(endTime <= duration) { "endTime must be <= duration ($duration), got $endTime" }
        require(startTime <= endTime) { "startTime ($startTime) must be <= endTime ($endTime)" }

        if (startTime == endTime) {
            return Pair(FloatArray(0), FloatArray(0))
        }

        val startSample = floor(startTime * sampleRate).toLong()
        val endSample = floor(endTime * sampleRate).toLong()
        // Clamp to actual data boundary
        val totalSamplesInFile = dataChunkSize / (numChannels * bytesPerSample)
        val clampedEndSample = min(endSample, totalSamplesInFile)
        val numSamples = (clampedEndSample - startSample).toInt()

        if (numSamples <= 0) {
            return Pair(FloatArray(0), FloatArray(0))
        }

        val left = FloatArray(numSamples)
        val right = FloatArray(numSamples)

        val bytesPerFrame = numChannels * bytesPerSample
        val seekPosition = dataChunkOffset + startSample * bytesPerFrame

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(seekPosition)

            // Read in chunks to avoid allocating a huge byte array
            val bufferFrames = 4096
            val bufferBytes = ByteArray(bufferFrames * bytesPerFrame)
            var samplesRead = 0

            while (samplesRead < numSamples) {
                val remaining = numSamples - samplesRead
                val framesToRead = min(remaining, bufferFrames)
                val bytesNeeded = framesToRead * bytesPerFrame
                raf.readFully(bufferBytes, 0, bytesNeeded)

                val buf = ByteBuffer.wrap(bufferBytes, 0, bytesNeeded)
                    .order(ByteOrder.LITTLE_ENDIAN)

                for (i in 0 until framesToRead) {
                    left[samplesRead + i] = readOneSample(buf)
                    right[samplesRead + i] = readOneSample(buf)
                }
                samplesRead += framesToRead
            }
        }

        return Pair(left, right)
    }

    /**
     * Read one sample from the buffer and convert to float [-1.0, 1.0].
     */
    private fun readOneSample(buf: ByteBuffer): Float {
        return when (audioFormat) {
            AUDIO_FORMAT_IEEE_FLOAT -> {
                buf.getFloat()
            }

            AUDIO_FORMAT_PCM -> when (bitsPerSample) {
                16 -> buf.getShort().toFloat() / 32768.0f

                24 -> {
                    val b0 = buf.get().toInt() and 0xFF
                    val b1 = buf.get().toInt() and 0xFF
                    val b2 = buf.get().toInt() // sign-extended
                    val value = (b2 shl 16) or (b1 shl 8) or b0
                    value.toFloat() / 8388608.0f
                }

                32 -> (buf.getInt().toDouble() / 2147483648.0).toFloat()

                else -> throw IllegalStateException(
                    "Unsupported PCM bit depth: $bitsPerSample"
                )
            }

            else -> throw IllegalStateException(
                "Unsupported format: audioFormat=$audioFormat, bitsPerSample=$bitsPerSample"
            )
        }
    }
}
