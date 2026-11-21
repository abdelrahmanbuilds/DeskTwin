package com.desktwin.app.ui.theme

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

/** Small, original synthesized signature tones for the theme Easter eggs. */
object ThemeSoundPlayer {
    private const val SAMPLE_RATE = 22_050
    private val mainHandler = Handler(Looper.getMainLooper())
    private var customPlayer: MediaPlayer? = null
    private val playback = Executors.newSingleThreadExecutor { task ->
        Thread(task, "desktwin-theme-sound").apply { isDaemon = true }
    }

    fun play(themeId: String) {
        mainHandler.post { stopCustomPlayback() }
        playBuiltIn(themeId)
    }

    fun play(themeId: String, context: Context, customAudioUri: String?) {
        if (customAudioUri.isNullOrBlank()) {
            play(themeId)
            return
        }
        mainHandler.post {
            stopCustomPlayback()
            val player = MediaPlayer()
            runCatching {
                player.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                player.setDataSource(context.applicationContext, Uri.parse(customAudioUri))
                player.setOnPreparedListener { prepared ->
                    if (customPlayer === prepared) runCatching { prepared.start() }
                    else runCatching { prepared.release() }
                }
                player.setOnCompletionListener { completed ->
                    if (customPlayer === completed) customPlayer = null
                    completed.release()
                }
                player.setOnErrorListener { failed, _, _ ->
                    if (customPlayer === failed) {
                        customPlayer = null
                        failed.release()
                        playBuiltIn(themeId)
                    }
                    true
                }
                customPlayer = player
                player.prepareAsync()
            }.onFailure {
                if (customPlayer === player) customPlayer = null
                runCatching { player.release() }
                playBuiltIn(themeId)
            }
        }
    }

    fun stop() {
        mainHandler.post { stopCustomPlayback() }
    }

    private fun stopCustomPlayback() {
        val previous = customPlayer ?: return
        customPlayer = null
        runCatching { previous.stop() }
        runCatching { previous.release() }
    }

    private fun playBuiltIn(themeId: String) {
        playback.execute {
            runCatching {
                val notes = when (themeId) {
                    "cosmic" -> listOf(784.0, 1174.7, 1568.0, 1046.5)
                    "code-studio" -> listOf(1318.5, 1760.0, 1318.5, 880.0)
                    "ember" -> listOf(392.0, 493.9, 587.3, 493.9)
                    "orchard" -> listOf(523.3, 587.3, 698.5, 784.0)
                    "apple-monochrome" -> listOf(659.3, 830.6, 987.8, 1318.5)
                    else -> listOf(523.3, 659.3, 784.0, 1046.5)
                }
                val pcm = synthesize(themeId, notes)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                try {
                    val written = track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                    if (written > 0) {
                        track.play()
                        Thread.sleep((pcm.size * 1_000L / SAMPLE_RATE) + 80L)
                    }
                } finally {
                    track.release()
                }
            }
        }
    }

    private fun synthesize(themeId: String, notes: List<Double>): ShortArray {
        val durationMs = if (themeId == "cosmic") 720 else 570
        val count = SAMPLE_RATE * durationMs / 1_000
        val pcm = ShortArray(count)
        val noteDuration = durationMs.toDouble() / notes.size
        for (index in pcm.indices) {
            val timeMs = index * 1_000.0 / SAMPLE_RATE
            val noteIndex = (timeMs / noteDuration).toInt().coerceAtMost(notes.lastIndex)
            val localTime = (timeMs - noteIndex * noteDuration) / 1_000.0
            val progress = (timeMs % noteDuration) / noteDuration
            val attack = (progress / 0.16).coerceIn(0.0, 1.0)
            val release = ((1.0 - progress) / 0.34).coerceIn(0.0, 1.0)
            val envelope = attack * release
            val frequency = notes[noteIndex]
            val fundamental = sin(2.0 * PI * frequency * localTime)
            val overtone = when (themeId) {
                "code-studio" -> sin(2.0 * PI * frequency * 3.0 * localTime) * 0.28
                "ember" -> sin(2.0 * PI * frequency * 2.0 * localTime) * 0.08
                else -> sin(2.0 * PI * frequency * 2.01 * localTime) * 0.18
            }
            val airy = when (themeId) {
                "cosmic" -> sin(2.0 * PI * frequency * 3.02 * localTime) * 0.16
                "apple-monochrome" -> sin(2.0 * PI * frequency * 4.01 * localTime) * 0.08
                else -> 0.0
            }
            val amplitude = when (themeId) {
                "code-studio" -> 0.19
                "ember" -> 0.16
                else -> 0.17
            }
            pcm[index] = ((fundamental + overtone + airy) * envelope * amplitude * Short.MAX_VALUE)
                .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return pcm
    }
}
