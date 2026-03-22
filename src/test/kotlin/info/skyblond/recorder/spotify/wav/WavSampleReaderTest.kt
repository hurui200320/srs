package info.skyblond.recorder.spotify.wav

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WavSampleReaderTest {

    companion object {
        private fun resourceFile(name: String): File {
            val url = WavSampleReaderTest::class.java.getResource("/wav/sine/$name")
                ?: throw IllegalStateException("Test resource not found: /wav/sine/$name")
            return File(url.toURI())
        }

        /** Expected sine wave sample value at a given sample index. */
        private fun expectedSine(frequency: Double, sampleIndex: Int, sampleRate: Int = 44100): Float {
            return sin(2.0 * PI * frequency * sampleIndex / sampleRate).toFloat()
        }

        // Test files
        private val f32leStereo = resourceFile("sine_f32le_stereo.wav")
        private val f32leStereoRf64 = resourceFile("sine_f32le_stereo_rf64.wav")
        private val s16leStereo = resourceFile("sine_s16le_stereo.wav")
        private val s24leStereo = resourceFile("sine_s24le_stereo.wav")
        private val s32leStereo = resourceFile("sine_s32le_stereo.wav")
        private val dualTone = resourceFile("dual_tone_f32le_stereo.wav")

        /**
         * Create a minimal f32le stereo WAV file where the data chunk size
         * in the header is set to [headerDataSize] but the actual audio data
         * written is [actualFrames] frames of silence.
         */
        private fun createWavWithFakeDataSize(
            targetFile: File,
            actualFrames: Int = 4410, // 0.1s at 44100 Hz
            headerDataSize: Int = -1, // -1 → unsigned 0xFFFFFFFF
        ) {
            val sampleRate = 44100
            val numChannels = 2
            val bitsPerSample = 32
            val blockAlign = numChannels * (bitsPerSample / 8) // 8
            val actualDataSize = actualFrames * blockAlign

            RandomAccessFile(targetFile, "rw").use { raf ->
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                // RIFF header
                header.put("RIFF".toByteArray(Charsets.US_ASCII))
                header.putInt(36 + actualDataSize) // RIFF size (not critical for this test)
                header.put("WAVE".toByteArray(Charsets.US_ASCII))
                // fmt chunk
                header.put("fmt ".toByteArray(Charsets.US_ASCII))
                header.putInt(16) // fmt chunk size
                header.putShort(3) // IEEE float
                header.putShort(numChannels.toShort())
                header.putInt(sampleRate)
                header.putInt(sampleRate * blockAlign) // byte rate
                header.putShort(blockAlign.toShort())
                header.putShort(bitsPerSample.toShort())
                // data chunk
                header.put("data".toByteArray(Charsets.US_ASCII))
                header.putInt(headerDataSize) // deliberately wrong

                raf.write(header.array())
                // Write actual audio data (silence)
                raf.write(ByteArray(actualDataSize))
            }
        }
    }

    // ── Header Parsing ──────────────────────────────────────────────

    @Test
    fun `parse standard WAV header - f32le stereo`() {
        val reader = WavSampleReader(f32leStereo)
        assertEquals(44100, reader.sampleRate)
        assertEquals(2, reader.numChannels)
        assertEquals(32, reader.bitsPerSample)
        assertEquals(WavSampleReader.AUDIO_FORMAT_IEEE_FLOAT, reader.audioFormat)
        assertEquals(4, reader.bytesPerSample)
        assertEquals(8, reader.blockAlign)
        assertEquals(1.0, reader.duration, 0.001)
    }

    @Test
    fun `parse RF64 header - f32le stereo`() {
        val reader = WavSampleReader(f32leStereoRf64)
        assertEquals(44100, reader.sampleRate)
        assertEquals(2, reader.numChannels)
        assertEquals(32, reader.bitsPerSample)
        assertEquals(WavSampleReader.AUDIO_FORMAT_IEEE_FLOAT, reader.audioFormat)
        assertEquals(1.0, reader.duration, 0.001)
    }

    @Test
    fun `parse WAV header - s16le stereo`() {
        val reader = WavSampleReader(s16leStereo)
        assertEquals(44100, reader.sampleRate)
        assertEquals(2, reader.numChannels)
        assertEquals(16, reader.bitsPerSample)
        assertEquals(WavSampleReader.AUDIO_FORMAT_PCM, reader.audioFormat)
        assertEquals(2, reader.bytesPerSample)
        assertEquals(4, reader.blockAlign)
        assertEquals(1.0, reader.duration, 0.001)
    }

    @Test
    fun `parse WAV header - s24le stereo`() {
        val reader = WavSampleReader(s24leStereo)
        assertEquals(44100, reader.sampleRate)
        assertEquals(2, reader.numChannels)
        assertEquals(24, reader.bitsPerSample)
        assertEquals(WavSampleReader.AUDIO_FORMAT_PCM, reader.audioFormat)
        assertEquals(3, reader.bytesPerSample)
        assertEquals(6, reader.blockAlign)
        assertEquals(1.0, reader.duration, 0.001)
    }

    @Test
    fun `parse WAV header - s32le stereo`() {
        val reader = WavSampleReader(s32leStereo)
        assertEquals(44100, reader.sampleRate)
        assertEquals(2, reader.numChannels)
        assertEquals(32, reader.bitsPerSample)
        assertEquals(WavSampleReader.AUDIO_FORMAT_PCM, reader.audioFormat)
        assertEquals(4, reader.bytesPerSample)
        assertEquals(8, reader.blockAlign)
        assertEquals(1.0, reader.duration, 0.001)
    }

    // ── Sample Reading & Format Conversion ──────────────────────────

    @Test
    fun `read f32le samples match expected sine wave`() {
        val reader = WavSampleReader(f32leStereo)
        val (left, _) = reader.readSamples(0.0, 0.01) // 10ms = 441 samples
        assertEquals(441, left.size)

        for (i in left.indices) {
            val expected = expectedSine(440.0, i)
            // float32 should be very precise
            assertEquals(expected, left[i], 1e-4f, "Sample $i mismatch")
        }
    }

    @Test
    fun `read s16le samples match expected sine wave within tolerance`() {
        val reader = WavSampleReader(s16leStereo)
        val (left, _) = reader.readSamples(0.0, 0.01)
        assertEquals(441, left.size)

        for (i in left.indices) {
            val expected = expectedSine(440.0, i)
            // int16 quantization: 1/32768 ≈ 3e-5
            assertEquals(expected, left[i], 5e-5f, "Sample $i mismatch")
        }
    }

    @Test
    fun `read s24le samples match expected sine wave within tolerance`() {
        val reader = WavSampleReader(s24leStereo)
        val (left, _) = reader.readSamples(0.0, 0.01)
        assertEquals(441, left.size)

        for (i in left.indices) {
            val expected = expectedSine(440.0, i)
            // int24 quantization: 1/8388608 ≈ 1.2e-7, but float32 precision limits this
            assertEquals(expected, left[i], 5e-5f, "Sample $i mismatch")
        }
    }

    @Test
    fun `read s32le samples match expected sine wave within tolerance`() {
        val reader = WavSampleReader(s32leStereo)
        val (left, _) = reader.readSamples(0.0, 0.01)
        assertEquals(441, left.size)

        for (i in left.indices) {
            val expected = expectedSine(440.0, i)
            // int32 → float conversion loses precision; also source was s16le upsampled
            assertEquals(expected, left[i], 5e-5f, "Sample $i mismatch")
        }
    }

    // ── Channel Separation ──────────────────────────────────────────

    @Test
    fun `stereo channels are correctly separated`() {
        val reader = WavSampleReader(dualTone)
        val (left, right) = reader.readSamples(0.0, 0.01)
        assertEquals(441, left.size)
        assertEquals(441, right.size)

        for (i in left.indices) {
            val expectedLeft = expectedSine(440.0, i)
            val expectedRight = expectedSine(880.0, i)
            assertEquals(expectedLeft, left[i], 1e-4f, "Left channel sample $i mismatch")
            assertEquals(expectedRight, right[i], 1e-4f, "Right channel sample $i mismatch")
        }
    }

    // ── Time Positioning ────────────────────────────────────────────

    @Test
    fun `read samples at mid-file offset`() {
        val reader = WavSampleReader(f32leStereo)
        val startTime = 0.5
        val endTime = 0.51 // 10ms
        val (left, _) = reader.readSamples(startTime, endTime)

        val startSample = floor(startTime * 44100).toInt()
        assertEquals(441, left.size)

        for (i in left.indices) {
            val expected = expectedSine(440.0, startSample + i)
            assertEquals(expected, left[i], 1e-4f, "Sample $i at offset $startTime mismatch")
        }
    }

    @Test
    fun `read samples at end of file`() {
        val reader = WavSampleReader(f32leStereo)
        val startTime = 0.99
        val endTime = reader.duration
        val (left, _) = reader.readSamples(startTime, endTime)

        val startSample = floor(startTime * 44100).toInt()
        assertTrue(left.isNotEmpty(), "Should have read some samples")

        for (i in left.indices) {
            val expected = expectedSine(440.0, startSample + i)
            assertEquals(expected, left[i], 1e-4f, "Sample $i at end of file mismatch")
        }
    }

    // ── Boundary Conditions ─────────────────────────────────────────

    @Test
    fun `startTime negative throws exception`() {
        val reader = WavSampleReader(f32leStereo)
        assertFailsWith<IllegalArgumentException> {
            reader.readSamples(-0.1, 0.5)
        }
    }

    @Test
    fun `endTime exceeds duration throws exception`() {
        val reader = WavSampleReader(f32leStereo)
        assertFailsWith<IllegalArgumentException> {
            reader.readSamples(0.0, reader.duration + 1.0)
        }
    }

    @Test
    fun `startTime greater than endTime throws exception`() {
        val reader = WavSampleReader(f32leStereo)
        assertFailsWith<IllegalArgumentException> {
            reader.readSamples(0.5, 0.1)
        }
    }

    @Test
    fun `startTime equals endTime returns empty arrays`() {
        val reader = WavSampleReader(f32leStereo)
        val (left, right) = reader.readSamples(0.5, 0.5)
        assertEquals(0, left.size)
        assertEquals(0, right.size)
    }

    // ── RF64 Consistency ────────────────────────────────────────────

    @Test
    fun `RF64 and standard WAV produce identical samples`() {
        val readerWav = WavSampleReader(f32leStereo)
        val readerRf64 = WavSampleReader(f32leStereoRf64)

        val (wavLeft, wavRight) = readerWav.readSamples(0.0, 0.1)
        val (rf64Left, rf64Right) = readerRf64.readSamples(0.0, 0.1)

        assertEquals(wavLeft.size, rf64Left.size, "Sample count mismatch")

        for (i in wavLeft.indices) {
            assertEquals(wavLeft[i], rf64Left[i], 0.0f, "Left sample $i differs")
            assertEquals(wavRight[i], rf64Right[i], 0.0f, "Right sample $i differs")
        }
    }

    // ── Unfinalized Header (dataChunkSize clamping) ─────────────────

    @Test
    fun `oversized data chunk size is clamped to actual file size`() {
        val tempFile = File.createTempFile("test_oversized_header", ".wav")
        tempFile.deleteOnExit()

        // 4410 frames = 0.1s, but header says 0xFFFFFFFF bytes
        createWavWithFakeDataSize(tempFile, actualFrames = 4410, headerDataSize = -1)

        val reader = WavSampleReader(tempFile)
        // Duration should reflect actual data, not the fake header
        val expectedDuration = 4410.0 / 44100.0 // 0.1s
        assertEquals(expectedDuration, reader.duration, 0.001, "Duration should be clamped to actual data")
        // dataChunkSize should be clamped: actual data = 4410 * 8 = 35280
        assertEquals(35280L, reader.dataChunkSize, "dataChunkSize should be clamped")
    }

    @Test
    fun `readSamples works with oversized header - no EOFException`() {
        val tempFile = File.createTempFile("test_oversized_read", ".wav")
        tempFile.deleteOnExit()

        createWavWithFakeDataSize(tempFile, actualFrames = 4410, headerDataSize = -1)

        val reader = WavSampleReader(tempFile)
        // Should be able to read the full (clamped) duration without EOFException
        val (left, right) = reader.readSamples(0.0, reader.duration)
        assertEquals(4410, left.size, "Should read all actual frames")
        assertEquals(4410, right.size, "Should read all actual frames")
    }

    @Test
    fun `correct header is not affected by clamping`() {
        val tempFile = File.createTempFile("test_correct_header", ".wav")
        tempFile.deleteOnExit()

        val actualFrames = 4410
        val correctDataSize = actualFrames * 8 // 35280
        createWavWithFakeDataSize(tempFile, actualFrames = actualFrames, headerDataSize = correctDataSize)

        val reader = WavSampleReader(tempFile)
        assertEquals(4410.0 / 44100.0, reader.duration, 0.001)
        assertEquals(correctDataSize.toLong(), reader.dataChunkSize)
    }
}
