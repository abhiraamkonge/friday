package com.jarvis.agent

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-fidelity 16kHz 16-bit mono PCM audio recorder for voiceprint biometrics and verification.
 */
class VoiceRecorder {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private val isRecording = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private val bufferStream = ByteArrayOutputStream()

    fun start(): Boolean {
        if (isRecording.get()) return true

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("VoiceRecorder", "AudioRecord initialization failed")
                return false
            }

            audioRecord?.startRecording()
            isRecording.set(true)
            bufferStream.reset()

            recordThread = Thread({
                val buffer = ByteArray(2048)
                while (isRecording.get()) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        synchronized(bufferStream) {
                            bufferStream.write(buffer, 0, read)
                        }
                    }
                }
            }, "JarvisVoiceRecorder").apply { start() }

            return true

        } catch (e: Exception) {
            Log.e("VoiceRecorder", "Start recording error", e)
            stop()
            return false
        }
    }

    fun stop(): ByteArray {
        if (!isRecording.getAndSet(false)) {
            synchronized(bufferStream) { return bufferStream.toByteArray() }
        }

        try {
            recordThread?.join(500)
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w("VoiceRecorder", "Stop recording error", e)
        } finally {
            audioRecord = null
            recordThread = null
        }

        synchronized(bufferStream) {
            return bufferStream.toByteArray()
        }
    }
}
