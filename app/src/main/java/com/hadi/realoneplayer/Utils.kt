package com.videoh.realoneplayer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.util.Locale

data class VideoItem(
    val id: Long,
    val title: String,
    val duration: Long,
    val folder: String,
    val path: String?,
    val uri: Uri
)

/** Daftar video yang sedang tampil (dipakai untuk tombol sebelumnya/selanjutnya). */
object Playlist {
    var items: List<VideoItem> = emptyList()
}

/** Format waktu: 00.31 atau 1.52.14 */
fun fmtTime(ms: Long): String {
    if (ms <= 0) return "00.00"
    val t = ms / 1000
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) String.format(Locale.US, "%d.%02d.%02d", h, m, s)
    else String.format(Locale.US, "%02d.%02d", m, s)
}

object VideoRepo {
    @Suppress("DEPRECATION")
    fun load(ctx: Context): List<VideoItem> {
        val list = ArrayList<VideoItem>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATA
        )
        try {
            ctx.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, null, null,
                MediaStore.Video.Media.DISPLAY_NAME + " COLLATE NOCASE ASC"
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val iBucket = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val iData = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                while (c.moveToNext()) {
                    val id = c.getLong(iId)
                    val name = c.getString(iName) ?: "Video $id"
                    list.add(
                        VideoItem(
                            id = id,
                            title = name.substringBeforeLast('.'),
                            duration = c.getLong(iDur),
                            folder = c.getString(iBucket) ?: "",
                            path = c.getString(iData),
                            uri = ContentUris.withAppendedId(
                                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                            )
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return list
    }
}

/** Menyimpan video terakhir yang ditonton agar bisa dilanjutkan. */
object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("realone", Context.MODE_PRIVATE)

    fun save(c: Context, id: Long, pos: Long, watching: Boolean) {
        p(c).edit().putLong("id", id).putLong("pos", pos).putBoolean("watching", watching).apply()
    }

    fun clearWatching(c: Context) {
        p(c).edit().putBoolean("watching", false).apply()
    }

    fun watching(c: Context) = p(c).getBoolean("watching", false)
    fun lastId(c: Context) = p(c).getLong("id", -1L)
    fun lastPos(c: Context) = p(c).getLong("pos", 0L)

    fun bassOn(c: Context) = p(c).getBoolean("bass", false)
    fun setBass(c: Context, on: Boolean) = p(c).edit().putBoolean("bass", on).apply()
}
