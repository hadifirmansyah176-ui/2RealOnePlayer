package com.videoh.realoneplayer

import android.Manifest
import android.app.RecoverableSecurityException
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.annotation.RequiresApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.videoh.realoneplayer.databinding.ActivityMainBinding
import com.videoh.realoneplayer.databinding.ItemFolderBinding
import com.videoh.realoneplayer.databinding.ItemVideoBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val adapter = VideoAdapter(
        onClick = { openVideo(it) },
        onLongClick = { showItemMenu(it) }
    )
    private val folderAdapter = FolderAdapter { enterFolder(it) }
    private var all: List<VideoItem> = emptyList()
    private var currentFolder: FolderItem? = null
    private var searchOpen = false
    private var resumeChecked = false
    private var pendingDelete: VideoItem? = null
    private var afterWritePerm: (() -> Unit)? = null

    private val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) onGranted() else showEmpty("Izin akses video diperlukan untuk menampilkan daftar video.")
        }

    // Izin tulis (khusus Android 9 ke bawah) untuk menghapus file asli
    private val writePermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = afterWritePerm
            afterWritePerm = null
            if (granted) action?.invoke() else toast("Izin untuk menghapus file ditolak")
        }

    // Konfirmasi penghapusan dari sistem (Android 10 ke atas)
    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            val item = pendingDelete
            pendingDelete = null
            if (item == null) return@registerForActivityResult
            if (res.resultCode == RESULT_OK) {
                if (Build.VERSION.SDK_INT == 29) deleteApi29(item) else afterDelete(true)
            } else {
                toast("Penghapusan dibatalkan")
            }
        }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (searchOpen) closeSearch() else if (currentFolder != null) exitFolder()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        resumeChecked = savedInstanceState != null

        b.rv.layoutManager = LinearLayoutManager(this)
        b.rv.adapter = folderAdapter
        onBackPressedDispatcher.addCallback(this, backCallback)

        b.btnUp.setOnClickListener { exitFolder() }
        b.btnAbout.setOnClickListener { showAbout() }
        b.btnSearch.setOnClickListener {
            if (searchOpen) closeSearch() else openSearch()
        }
        b.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter()
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        if (hasPermission()) onGranted() else permLauncher.launch(permission)
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission() && resumeChecked) refresh()
    }

    private fun hasPermission() =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun onGranted() {
        refresh()
        if (!resumeChecked) {
            resumeChecked = true
            // Jika sebelumnya keluar mendadak saat menonton, lanjutkan video terakhir.
            if (Prefs.watching(this) && Prefs.lastId(this) != -1L) {
                val intent = Intent(this, PlayerActivity::class.java)
                    .putExtra("id", Prefs.lastId(this))
                    .putExtra("pos", Prefs.lastPos(this))
                startActivity(intent)
            }
        }
    }

    private fun refresh() {
        val hidden = Prefs.hidden(this)
        all = VideoRepo.load(this).filter { it.id.toString() !in hidden }
        applyFilter()
    }

    private fun keyOf(v: VideoItem): String =
        v.path?.substringBeforeLast('/', "")?.ifEmpty { null } ?: v.folder

    private fun applyFilter() {
        val q = b.etSearch.text.toString().trim()
        val folder = currentFolder

        // Tampilan awal: hanya daftar folder
        if (folder == null && q.isEmpty()) {
            val folders = all.groupBy { keyOf(it) }
                .map { (key, list) ->
                    FolderItem(key, list.first().folder.ifBlank { "Tanpa folder" }, list.size)
                }
                .sortedBy { it.name.lowercase() }
            if (b.rv.adapter !== folderAdapter) b.rv.adapter = folderAdapter
            folderAdapter.submit(folders)
            if (folders.isEmpty()) showEmpty("Tidak ada video ditemukan.") else b.tvEmpty.visibility = View.GONE
            return
        }

        // Di dalam folder (atau hasil pencarian): daftar video
        val base = if (folder != null) all.filter { keyOf(it) == folder.key } else all
        if (folder != null && base.isEmpty()) {
            exitFolder()
            return
        }
        val list = if (q.isEmpty()) base else base.filter { it.title.contains(q, ignoreCase = true) }
        if (b.rv.adapter !== adapter) b.rv.adapter = adapter
        adapter.submit(list)
        if (list.isEmpty()) {
            showEmpty(if (q.isEmpty()) "Tidak ada video ditemukan." else "Video tidak ditemukan.")
        } else {
            b.tvEmpty.visibility = View.GONE
        }
    }

    private fun enterFolder(f: FolderItem) {
        currentFolder = f
        updateTitle()
        applyFilter()
    }

    private fun exitFolder() {
        currentFolder = null
        updateTitle()
        applyFilter()
    }

    private fun updateTitle() {
        val f = currentFolder
        b.tvTitle.text = f?.name ?: "Folder"
        val dp = resources.displayMetrics.density
        b.tvTitle.setPadding((if (f != null) 56 * dp else 20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
        b.tvTitle.visibility = if (searchOpen) View.GONE else View.VISIBLE
        b.btnUp.visibility = if (f != null && !searchOpen) View.VISIBLE else View.GONE
        backCallback.isEnabled = searchOpen || f != null
    }

    private fun showEmpty(msg: String) {
        b.tvEmpty.text = msg
        b.tvEmpty.visibility = View.VISIBLE
    }

    private fun openSearch() {
        searchOpen = true
        b.etSearch.visibility = View.VISIBLE
        updateTitle()
        b.etSearch.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(b.etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        searchOpen = false
        b.etSearch.setText("")
        b.etSearch.visibility = View.GONE
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(b.etSearch.windowToken, 0)
        updateTitle()
        applyFilter()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle("Tentang")
            .setMessage(
                "Aplikasi RealOnePlayer ini hanya untuk hiburan saja tidak diperjual belikan.\n\n" +
                    "Versi Saat ini : V.1.0\n" +
                    "by.Anonim 11/2026"
            )
            .setPositiveButton("Tutup", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------- tekan & tahan video

    private fun showItemMenu(item: VideoItem) {
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(arrayOf("Hapus Dari Daftar Putar", "Hapus Juga File Asli nya", "Info")) { _, which ->
                when (which) {
                    0 -> {
                        Prefs.hide(this, item.id)
                        refresh()
                        toast("Dihapus dari daftar putar")
                    }
                    1 -> confirmDeleteFile(item)
                    2 -> showInfo(item)
                }
            }
            .show()
    }

    // ----- Info (detail video)

    private fun showInfo(v: VideoItem) {
        val df = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val res = resolutionOf(v)
        val msg = buildString {
            append("Nama: ").append(v.fileName).append("\n\n")
            append("Lokasi: ").append(v.path?.substringBeforeLast('/', "") ?: v.folder).append("\n\n")
            append("Ukuran: ").append(Formatter.formatFileSize(this@MainActivity, v.size)).append("\n")
            append("Durasi: ").append(fmtTime(v.duration)).append("\n")
            if (res != null) append("Resolusi: ").append(res).append("\n")
            append("Format: ").append(v.mime ?: v.fileName.substringAfterLast('.', "-").uppercase()).append("\n")
            if (v.modified > 0) append("Diubah: ").append(df.format(Date(v.modified * 1000)))
        }.trimEnd()
        AlertDialog.Builder(this)
            .setTitle("Informasi Video")
            .setMessage(msg)
            .setPositiveButton("Tutup", null)
            .show()
    }

    private fun resolutionOf(v: VideoItem): String? = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(this, v.uri)
        val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        r.release()
        if (w <= 0 || h <= 0) null else if (rot == 90 || rot == 270) "$h x $w" else "$w x $h"
    } catch (_: Exception) {
        null
    }

    // ----- Hapus juga file asli

    private fun confirmDeleteFile(item: VideoItem) {
        AlertDialog.Builder(this)
            .setTitle("Hapus")
            .setMessage("Berkas berikut akan dihapus secara permanen.\n\n${item.fileName}")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Oke") { _, _ -> deleteOriginal(item) }
            .show()
    }

    private fun deleteOriginal(item: VideoItem) {
        when {
            Build.VERSION.SDK_INT >= 30 -> deleteApi30(item)
            Build.VERSION.SDK_INT == 29 -> deleteApi29(item)
            else -> withWritePermission { deleteLegacy(item) }
        }
    }

    @RequiresApi(30)
    private fun deleteApi30(item: VideoItem) {
        try {
            val pi = MediaStore.createDeleteRequest(contentResolver, listOf(item.uri))
            pendingDelete = item
            deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } catch (e: Exception) {
            toast("Gagal menghapus file")
        }
    }

    @RequiresApi(29)
    private fun deleteApi29(item: VideoItem) {
        try {
            afterDelete(contentResolver.delete(item.uri, null, null) > 0)
        } catch (e: RecoverableSecurityException) {
            pendingDelete = item
            deleteLauncher.launch(
                IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()
            )
        } catch (e: Exception) {
            toast("Gagal menghapus file")
        }
    }

    @Suppress("DEPRECATION")
    private fun deleteLegacy(item: VideoItem) {
        var ok = false
        try {
            ok = contentResolver.delete(item.uri, null, null) > 0
        } catch (_: Exception) {
        }
        if (!ok) {
            item.path?.let { ok = try { File(it).delete() } catch (_: Exception) { false } }
        }
        afterDelete(ok)
    }

    private fun afterDelete(ok: Boolean) {
        toast(if (ok) "File dihapus" else "Gagal menghapus file")
        refresh()
    }

    private fun withWritePermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            afterWritePerm = action
            writePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun openVideo(item: VideoItem) {
    val visibleVideos = adapter.current
    AlertDialog.Builder(this)
        .setTitle("Pilih cara memutar")
        .setItems(
            arrayOf("Putar semua video", "Putar video ini saja")
        ) { _, which ->
            Playlist.items =
                if (which == 0) visibleVideos else listOf(item)

            startActivity(
                Intent(this, PlayerActivity::class.java)
                    .putExtra("id", item.id)
            )
        }
        .show()
}

class VideoAdapter(
    private val onClick: (VideoItem) -> Unit,
    private val onLongClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.VH>() {

    private var data: List<VideoItem> = emptyList()
    val current: List<VideoItem> get() = data

    fun submit(list: List<VideoItem>) {
        data = list
        notifyDataSetChanged()
    }

    class VH(val b: ItemVideoBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = data.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = data[position]
        holder.b.tvName.text = item.title
        holder.b.tvDur.text = fmtTime(item.duration)
        Glide.with(holder.b.ivThumb).load(item.uri).centerCrop().into(holder.b.ivThumb)
        holder.b.root.setOnClickListener { onClick(item) }
        holder.b.root.setOnLongClickListener { onLongClick(item); true }
    }
}

data class FolderItem(val key: String, val name: String, val count: Int)

class FolderAdapter(private val onClick: (FolderItem) -> Unit) :
    RecyclerView.Adapter<FolderAdapter.VH>() {

    private var data: List<FolderItem> = emptyList()

    fun submit(list: List<FolderItem>) {
        data = list
        notifyDataSetChanged()
    }

    class VH(val b: ItemFolderBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = data.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val f = data[position]
        holder.b.tvFolderName.text = f.name
        holder.b.tvFolderCount.text = "${f.count} video"
        holder.b.root.setOnClickListener { onClick(f) }
    }
}
