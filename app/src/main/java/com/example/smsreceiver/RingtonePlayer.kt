package com.example.smsreceiver

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.annotation.RawRes

/**
 * 内置提示音播放器：10 个铃声全部打包在 res/raw，不依赖系统铃声、不需要额外权限。
 * 收到转发的短信时由前台服务调用 play()；设置页试听时调用 preview()。
 */
object RingtonePlayer {

    data class Ringtone(val name: String, @RawRes val resId: Int)

    val RINGTONES = listOf(
        Ringtone("经典提示音", R.raw.ringtone_01),
        Ringtone("清脆双音", R.raw.ringtone_02),
        Ringtone("上行音阶", R.raw.ringtone_03),
        Ringtone("下行音阶", R.raw.ringtone_04),
        Ringtone("叮咚门铃", R.raw.ringtone_05),
        Ringtone("太空通讯", R.raw.ringtone_06),
        Ringtone("柔和琶音", R.raw.ringtone_07),
        Ringtone("急促三连", R.raw.ringtone_08),
        Ringtone("木琴跳跃", R.raw.ringtone_09),
        Ringtone("电子脉冲", R.raw.ringtone_10)
    )

    fun nameOf(index: Int): String =
        RINGTONES.getOrNull(index.coerceIn(RINGTONES.indices))?.name ?: RINGTONES[0].name

    @Volatile
    private var current: MediaPlayer? = null

    /** 收到短信时播放。手机处于完全静音时不打扰用户。 */
    fun play(context: Context, index: Int) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am?.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        start(context, index, preview = false)
    }

    /** 设置页点击试听：即使手机静音也播放，方便挑选。 */
    fun preview(context: Context, index: Int) = start(context, index, preview = true)

    private fun start(context: Context, index: Int, preview: Boolean) {
        val safeIndex = index.coerceIn(RINGTONES.indices)
        val resId = RINGTONES[safeIndex].resId
        synchronized(this) {
            current?.let { old ->
                runCatching {
                    if (old.isPlaying) old.stop()
                    old.release()
                }
            }
            current = null
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(if (preview) AudioAttributes.USAGE_MEDIA
                                  else AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                val uri = Uri.parse("android.resource://${context.packageName}/$resId")
                mp.setDataSource(context, uri)
                mp.setOnPreparedListener { it.start() }
                mp.setOnCompletionListener { finished ->
                    runCatching { finished.release() }
                    synchronized(this) { if (current === finished) current = null }
                }
                mp.setOnErrorListener { mpError, _, _ ->
                    runCatching { mpError.release() }
                    synchronized(this) { if (current === mpError) current = null }
                    true
                }
                current = mp
                mp.prepareAsync()
            } catch (e: Exception) {
                current = null
            }
        }
    }

    fun stop() {
        synchronized(this) {
            current?.let { mp ->
                runCatching {
                    if (mp.isPlaying) mp.stop()
                    mp.release()
                }
            }
            current = null
        }
    }
}
