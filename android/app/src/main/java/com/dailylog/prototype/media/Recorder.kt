package com.dailylog.prototype.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Records the voice log to one cache file (AAC in .m4a). The file is deleted after upload or cancel. */
class Recorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    val file: File get() = File(context.cacheDir, "voice-log.m4a")

    fun start(maxSeconds: Int = 180) {
        stopQuietly()
        file.delete()
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioSamplingRate(16_000)
        r.setAudioChannels(1)
        r.setAudioEncodingBitRate(64_000)
        r.setMaxDuration(maxSeconds * 1000)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
        recorder = r
    }

    /** 0..1, for the waveform. */
    fun level(): Float = (recorder?.runCatching { maxAmplitude }?.getOrNull() ?: 0) / 32767f

    /** Returns the recording, or null if it was too short to keep. */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        return if (ok && file.length() > 0) file else null.also { file.delete() }
    }

    fun cancel() {
        stopQuietly()
        file.delete()
    }

    private fun stopQuietly() {
        recorder?.let { r ->
            runCatching { r.stop() }
            r.release()
        }
        recorder = null
    }
}
