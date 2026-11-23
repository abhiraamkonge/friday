package com.jarvis.agent

import kotlin.math.*

/**
 * Biometric Voiceprint & Speaker Recognition Engine for Jarvis.
 * Extracts acoustic features (MFCCs, Mel filterbank energies, Pitch F0,
 * Spectral Centroid, Spectral Rolloff, Zero-Crossing Rate) and verifies speaker identity.
 */
object VoiceprintEngine {

    const val SAMPLE_RATE = 16000
    const val FRAME_SIZE = 400  // 25ms at 16kHz
    const val FRAME_STEP = 160  // 10ms at 16kHz
    const val FFT_SIZE = 512    // Radix-2 FFT size
    const val NUM_MEL_FILTERS = 24
    const val NUM_MFCC = 13

    data class Voiceprint(
        val vector: FloatArray, // 32 normalized acoustic features
        val avgPitchHz: Float   // Fundamental frequency in Hz
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Voiceprint) return false
            return vector.contentEquals(other.vector) && avgPitchHz == other.avgPitchHz
        }

        override fun hashCode(): Int {
            var result = vector.contentHashCode()
            result = 31 * result + avgPitchHz.hashCode()
            return result
        }
    }

    data class MatchResult(
        val matched: Boolean,
        val score: Float,
        val details: String
    )

    private val melFilterBank: Array<FloatArray> by lazy {
        val lowMel = hzToMel(100.0)
        val highMel = hzToMel((SAMPLE_RATE / 2).toDouble())
        val melStep = (highMel - lowMel) / (NUM_MEL_FILTERS + 1)
        val binFrequencies = DoubleArray(NUM_MEL_FILTERS + 2) { i ->
            melToHz(lowMel + i * melStep)
        }
        val binIndices = IntArray(NUM_MEL_FILTERS + 2) { i ->
            ((binFrequencies[i] * FFT_SIZE) / SAMPLE_RATE).toInt().coerceIn(0, FFT_SIZE / 2)
        }

        Array(NUM_MEL_FILTERS) { m ->
            val filter = FloatArray(FFT_SIZE / 2 + 1)
            val left = binIndices[m]
            val center = binIndices[m + 1]
            val right = binIndices[m + 2]

            for (k in left..center) {
                if (center > left) filter[k] = (k - left).toFloat() / (center - left)
            }
            for (k in center..right) {
                if (right > center) filter[k] = (right - k).toFloat() / (right - center)
            }
            filter
        }
    }

    private fun hzToMel(hz: Double): Double = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double): Double = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    /**
     * Extracts a biometric voiceprint from 16kHz 16-bit mono PCM audio.
     */
    fun extractVoiceprint(pcmBytes: ByteArray): Voiceprint? {
        if (pcmBytes.size < 3200) return null // Need at least 100ms of audio
        val shorts = ShortArray(pcmBytes.size / 2)
        for (i in shorts.indices) {
            val b1 = pcmBytes[i * 2].toInt() and 0xFF
            val b2 = pcmBytes[i * 2 + 1].toInt()
            shorts[i] = ((b2 shl 8) or b1).toShort()
        }
        return extractVoiceprint(shorts)
    }

    fun extractVoiceprint(shorts: ShortArray): Voiceprint? {
        if (shorts.size < FRAME_SIZE) return null

        // Apply pre-emphasis filter: y[n] = x[n] - 0.97 * x[n-1]
        val preEmph = FloatArray(shorts.size)
        preEmph[0] = shorts[0].toFloat()
        for (i in 1 until shorts.size) {
            preEmph[i] = shorts[i] - 0.97f * shorts[i - 1]
        }

        val numFrames = (preEmph.size - FRAME_SIZE) / FRAME_STEP + 1
        if (numFrames < 8) return null

        val mfccFrames = ArrayList<FloatArray>()
        val pitchFrames = ArrayList<Float>()
        val centroidFrames = ArrayList<Float>()
        val rolloffFrames = ArrayList<Float>()
        val zcrFrames = ArrayList<Float>()

        val window = FloatArray(FRAME_SIZE) { i ->
            (0.54 - 0.46 * cos(2.0 * PI * i / (FRAME_SIZE - 1))).toFloat()
        }

        val real = FloatArray(FFT_SIZE)
        val imag = FloatArray(FFT_SIZE)

        for (f in 0 until numFrames) {
            val offset = f * FRAME_STEP
            val rawFrame = FloatArray(FRAME_SIZE) { i -> shorts[offset + i].toFloat() }

            // Voice Activity Detection (RMS energy)
            val energy = computeEnergy(rawFrame)
            if (energy < 120.0f) continue // Discard silence/background noise

            val windowedFrame = FloatArray(FRAME_SIZE) { i ->
                preEmph[offset + i] * window[i]
            }

            // Pitch estimation via normalized autocorrelation
            val pitch = estimatePitch(rawFrame)
            if (pitch > 0f) {
                pitchFrames.add(pitch)
            }

            // Zero Crossing Rate
            zcrFrames.add(computeZcr(rawFrame))

            // Zero-pad to 512 points for FFT
            real.fill(0f)
            imag.fill(0f)
            for (i in 0 until FRAME_SIZE) {
                real[i] = windowedFrame[i]
            }

            fft(real, imag)

            // Compute power spectrum
            val powerSpectrum = FloatArray(FFT_SIZE / 2 + 1)
            for (i in powerSpectrum.indices) {
                powerSpectrum[i] = (real[i] * real[i] + imag[i] * imag[i]) / FFT_SIZE
            }

            // Mel Filterbank
            val logMelEnergies = FloatArray(NUM_MEL_FILTERS)
            for (m in 0 until NUM_MEL_FILTERS) {
                val filter = melFilterBank[m]
                var sum = 0.0f
                for (k in filter.indices) {
                    sum += powerSpectrum[k] * filter[k]
                }
                logMelEnergies[m] = ln(max(sum, 1e-5f))
            }

            // 13 MFCC coefficients via DCT-II
            mfccFrames.add(computeMfcc(logMelEnergies))

            // Spectral Centroid & Rolloff
            val (centroid, rolloff) = computeSpectralStats(powerSpectrum)
            centroidFrames.add(centroid)
            rolloffFrames.add(rolloff)
        }

        if (mfccFrames.size < 6) return null // Not enough voiced speech

        // Aggregate statistics across voiced frames
        val vector = FloatArray(32)

        // 1. MFCC means (13 dimensions, scaled by 1/20.0)
        val mfccMeans = FloatArray(NUM_MFCC)
        for (i in 0 until NUM_MFCC) {
            var sum = 0.0f
            for (frame in mfccFrames) sum += frame[i]
            mfccMeans[i] = sum / mfccFrames.size
            vector[i] = mfccMeans[i] / 20.0f
        }

        // 2. MFCC standard deviations (13 dimensions, scaled by 1/10.0)
        for (i in 0 until NUM_MFCC) {
            var sumSq = 0.0f
            for (frame in mfccFrames) {
                val diff = frame[i] - mfccMeans[i]
                sumSq += diff * diff
            }
            val std = sqrt(sumSq / mfccFrames.size)
            vector[13 + i] = std / 10.0f
        }

        // 3. Pitch mean & std (2 dimensions, scaled by 1/400.0 and 1/50.0)
        val meanPitch = if (pitchFrames.isNotEmpty()) pitchFrames.average().toFloat() else 0f
        val pitchStd = if (pitchFrames.size > 1) {
            sqrt(pitchFrames.map { (it - meanPitch) * (it - meanPitch) }.average()).toFloat()
        } else 0f
        vector[26] = meanPitch / 400.0f
        vector[27] = pitchStd / 50.0f

        // 4. Spectral Centroid mean & std (2 dimensions, scaled by 1/4000.0 and 1/1000.0)
        val meanCentroid = centroidFrames.average().toFloat()
        val centroidStd = if (centroidFrames.size > 1) {
            sqrt(centroidFrames.map { (it - meanCentroid) * (it - meanCentroid) }.average()).toFloat()
        } else 0f
        vector[28] = meanCentroid / 4000.0f
        vector[29] = centroidStd / 1000.0f

        // 5. Spectral Rolloff (1 dimension, scaled by 1/8000.0)
        vector[30] = rolloffFrames.average().toFloat() / 8000.0f

        // 6. Zero-Crossing Rate (1 dimension)
        vector[31] = zcrFrames.average().toFloat()

        // L2-normalize the 32-dimensional vector
        var normSq = 0.0
        for (v in vector) normSq += v * v
        val norm = max(sqrt(normSq).toFloat(), 1e-6f)
        for (i in vector.indices) {
            vector[i] /= norm
        }

        return Voiceprint(vector, meanPitch)
    }

    /**
     * Verifies if sample audio matches the enrolled owner's voiceprint.
     */
    fun verify(
        enrolled: Voiceprint,
        samplePcm: ByteArray,
        threshold: Float = 0.74f
    ): MatchResult {
        val sampleVp = extractVoiceprint(samplePcm)
            ?: return MatchResult(
                matched = false,
                score = 0.0f,
                details = "Speech audio was too short or quiet to verify identity"
            )

        // 1. Cosine similarity of normalized acoustic embeddings
        var dot = 0.0f
        for (i in enrolled.vector.indices) {
            dot += enrolled.vector[i] * sampleVp.vector[i]
        }
        val cosSim = dot.coerceIn(-1.0f, 1.0f)

        // 2. Pitch congruency (vocal cord physical alignment)
        val pitchDiff = abs(enrolled.avgPitchHz - sampleVp.avgPitchHz)
        val pitchMatch = if (enrolled.avgPitchHz > 40f && sampleVp.avgPitchHz > 40f) {
            (1.0f - pitchDiff / 50.0f).coerceIn(0.0f, 1.0f)
        } else {
            0.80f // Neutral when unvoiced
        }

        // 3. Composite biometric score
        val compositeScore = (0.65f * cosSim + 0.35f * pitchMatch).coerceIn(0.0f, 1.0f)

        val matched = compositeScore >= threshold
        val matchPercent = (compositeScore * 100).roundToInt()
        val requiredPercent = (threshold * 100).roundToInt()

        val details = if (matched) {
            "Identity Confirmed: Sir (Match: $matchPercent%)"
        } else {
            "Voice Mismatch (Match: $matchPercent%, Required: $requiredPercent%)"
        }

        return MatchResult(matched, compositeScore, details)
    }

    private fun computeEnergy(frame: FloatArray): Float {
        var sum = 0.0
        for (s in frame) sum += s * s
        return sqrt(sum / frame.size).toFloat()
    }

    private fun computeZcr(frame: FloatArray): Float {
        var crossings = 0
        for (i in 1 until frame.size) {
            if ((frame[i] >= 0 && frame[i - 1] < 0) || (frame[i] < 0 && frame[i - 1] >= 0)) {
                crossings++
            }
        }
        return crossings.toFloat() / frame.size
    }

    private fun estimatePitch(frame: FloatArray): Float {
        val minLag = 40  // 400Hz at 16kHz
        val maxLag = 266 // 60Hz at 16kHz
        var r0 = 0.0
        for (s in frame) r0 += s * s
        if (r0 < 1e-4) return 0f

        var maxCorr = 0.0
        var bestLag = 0
        for (lag in minLag..maxLag) {
            var corr = 0.0
            val len = frame.size - lag
            for (i in 0 until len) {
                corr += frame[i] * frame[i + lag]
            }
            if (corr > maxCorr) {
                maxCorr = corr
                bestLag = lag
            }
        }

        val normR = maxCorr / r0
        return if (normR > 0.28 && bestLag > 0) (SAMPLE_RATE.toFloat() / bestLag) else 0f
    }

    private fun computeSpectralStats(powerSpectrum: FloatArray): Pair<Float, Float> {
        val half = powerSpectrum.size
        var totalPower = 0.0
        var weightedSum = 0.0
        val freqBin = SAMPLE_RATE.toFloat() / FFT_SIZE

        for (i in 0 until half) {
            val p = powerSpectrum[i]
            totalPower += p
            weightedSum += i * freqBin * p
        }

        if (totalPower < 1e-6) return Pair(0f, 0f)

        val centroid = (weightedSum / totalPower).toFloat()

        // 85% energy rolloff
        val threshold = totalPower * 0.85
        var cumSum = 0.0
        var rolloff = 0f
        for (i in 0 until half) {
            cumSum += powerSpectrum[i]
            if (cumSum >= threshold) {
                rolloff = i * freqBin
                break
            }
        }

        return Pair(centroid, rolloff)
    }

    private fun computeMfcc(logMelEnergies: FloatArray): FloatArray {
        val mfcc = FloatArray(NUM_MFCC)
        val numFilters = logMelEnergies.size
        for (k in 0 until NUM_MFCC) {
            var sum = 0.0
            for (n in 0 until numFilters) {
                sum += logMelEnergies[n] * cos(PI * k * (n + 0.5) / numFilters)
            }
            mfcc[k] = sum.toFloat()
        }
        return mfcc
    }

    // Radix-2 Cooley-Tukey Fast Fourier Transform
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tempR = real[i]; real[i] = real[j]; real[j] = tempR
                val tempI = imag[i]; imag[i] = imag[j]; imag[j] = tempI
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        var len = 2
        while (len <= n) {
            val half = len shr 1
            val angle = -2.0 * PI / len
            val wStepR = cos(angle).toFloat()
            val wStepI = sin(angle).toFloat()
            var i = 0
            while (i < n) {
                var wR = 1.0f
                var wI = 0.0f
                for (m in 0 until half) {
                    val pos = i + m
                    val match = pos + half
                    val uR = real[pos]
                    val uI = imag[pos]
                    val vR = real[match] * wR - imag[match] * wI
                    val vI = real[match] * wI + imag[match] * wR
                    real[pos] = uR + vR
                    imag[pos] = uI + vI
                    real[match] = uR - vR
                    imag[match] = uI - vI
                    val nextWR = wR * wStepR - wI * wStepI
                    val nextWI = wR * wStepI + wI * wStepR
                    wR = nextWR
                    wI = nextWI
                }
                i += len
            }
            len = len shl 1
        }
    }

    fun serialize(vp: Voiceprint): String {
        val sb = StringBuilder()
        sb.append(vp.avgPitchHz).append(";")
        sb.append(vp.vector.joinToString(","))
        return sb.toString()
    }

    fun deserialize(str: String): Voiceprint? {
        if (str.isBlank()) return null
        return try {
            val parts = str.split(";")
            if (parts.size < 2) return null
            val avgPitch = parts[0].toFloat()
            val vec = parts[1].split(",").map { it.toFloat() }.toFloatArray()
            if (vec.size != 32) return null
            Voiceprint(vec, avgPitch)
        } catch (e: Exception) {
            null
        }
    }
}
