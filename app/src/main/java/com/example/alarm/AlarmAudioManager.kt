package com.example.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class AlarmAudioManager(private val context: Context) {
    private var mediaPlayer: MediaPlayer? = null

    private fun getVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    fun startAlarmFeedback(soundEnabled: Boolean, volume: Float, vibrationEnabled: Boolean) {
        stopAlarmFeedback()
        if (soundEnabled && volume > 0f) {
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(context, uri)
                    isLooping = true
                    setVolume(volume.coerceIn(0f, 1f), volume.coerceIn(0f, 1f))
                    prepare()
                    start()
                }
            } catch (_: Exception) {
                // Ignore if emulator has no audio output device
            }
        }

        if (vibrationEnabled) {
            try {
                val vibrator = getVibrator()
                if (vibrator != null && vibrator.hasVibrator()) {
                    val pattern = longArrayOf(0, 450, 350, 450, 700)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(pattern, 0)
                    }
                }
            } catch (_: Exception) {
                // Ignore vibration failure
            }
        }
    }

    fun updateVolume(volume: Float) {
        try {
            val v = volume.coerceIn(0f, 1f)
            mediaPlayer?.setVolume(v, v)
        } catch (_: Exception) {
        }
    }

    fun stopAlarmFeedback() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null

        try {
            getVibrator()?.cancel()
        } catch (_: Exception) {
        }
    }
}
