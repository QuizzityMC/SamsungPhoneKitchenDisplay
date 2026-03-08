package com.kitchendisplay.app.services

import android.media.MediaPlayer
import android.media.MediaRecorder
import java.io.File

/**
 * Thin wrapper around [MediaRecorder] for recording voice messages
 * and [MediaPlayer] for playing them back.
 */
class AudioRecorderHelper {

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private var currentOutputPath: String? = null

    /** @return The file path of the recorded audio, or null if recording not started. */
    val outputPath: String? get() = currentOutputPath

    // ──────────────── Recording ────────────────

    /**
     * Starts recording to [outputFile].
     * @throws IOException if the recorder cannot be initialised.
     */
    fun startRecording(outputFile: File) {
        stopRecording()
        currentOutputPath = outputFile.absolutePath
        @Suppress("DEPRECATION")
        recorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128000)
            setOutputFile(outputFile.absolutePath)
            prepare()
            start()
        }
    }

    /** Stops an active recording and returns the output file path. */
    fun stopRecording(): String? {
        recorder?.apply {
            try {
                stop()
            } catch (_: RuntimeException) {
                // stop() throws if called too quickly after start()
            }
            release()
        }
        recorder = null
        return currentOutputPath
    }

    // ──────────────── Playback ────────────────

    fun playFile(filePath: String, onCompletion: () -> Unit = {}) {
        stopPlayback()
        player = MediaPlayer().apply {
            setDataSource(filePath)
            prepare()
            setOnCompletionListener { onCompletion() }
            start()
        }
    }

    fun stopPlayback() {
        player?.release()
        player = null
    }

    fun release() {
        stopRecording()
        stopPlayback()
    }
}

