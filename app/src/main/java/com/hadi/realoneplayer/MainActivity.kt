package com.videoh.realoneplayer

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.videoh.realoneplayer.databinding.ActivityMainBinding
import com.videoh.realoneplayer.databinding.ItemVideoBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val adapter = VideoAdapter(
        onClick = { openVideo(it) },
        onLongClick = { showItemMenu(it) }
    )
    private var all: List<VideoItem> = emptyList()
    private var resumeChecked = false
    private var pendingDelete: VideoItem? = null

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
            val item = pendingDelete
            pendingDelete = null
            if (granted && item != null) deleteLegacy(item)
            else toast("Izin untuk menghapus file ditolak")
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
        override fun handleOnBackPressed() = closeSearch()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        resumeChecked = savedInstanceState != null

        b.rv.layoutManager = LinearLayoutManager(this)
        b.rv.adapter = adapter
        onBackPressedDispatcher.addCallback(this, backCallback)

        b.btnAbout.setOnClickListener { showAbout() }
        b.btnSearch.setOnClickListener {
            if (b.etSearch.visibility == View.VISIBLE) closeSearch() else openSearch()
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

    private fun applyFilter() {
        val q = b.etSearch.text.toString().trim()
        val list = if (q.isEmpty()) all else all.filter { it.title.contains(q, ignoreCase = true) }
        adapter.submit(list)
        if (list.isEmpty()) {
            showEmpty(if (q.isEmpty()) "Tidak ada video ditemukan." else "Video tidak ditemukan.")
        } else {
            b.tvEmpty.visibility = View.GONE
        }
    }

    private fun showEmpty(msg: String) {
        b.tvEmpty.text = msg
        b.tvEmpty.visibility = View.VISIBLE
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun openSearch() {
        b.tvTitle.visibility = View.GONE
        b.etSearch.visibility = View.VISIBLE
        b.etSearch.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(b.etSearch, InputMethodManager.SHOW_IMPLICIT)
        backCallback.isEnabled = true
    }

    private fun closeSearch() {
        b.etSearch.setText("")
        b.etSearch.visibility = View.GONE
        b.tvTitle.visibility = View.VISIBLE
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(b.etSearch.windowToken, 0)
        backCallback.isEnabled = false
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
            .setNeutralButton("Tampilkan Semua Video") { _, _ ->
                Prefs.clearHidden(this)
                refresh()
                toast("Semua video ditampilkan kembali")
            }
            .show()
    }

    private fun openVideo(item: VideoItem) {
        Playlist.items = adapter.current
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("id", item.id))
    }

    // ------------------------------------------------- tekan & tahan video

    private fun showItemMenu(item: VideoItem) {
        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(arrayOf("Hapus Dari Daftar Putar", "Hapus Juga File Asli nya")) { _, which ->
                when (which) {
                    0 -> {
                        Prefs.hide(this, item.id)
                        refresh()
                        toast("Dihapus dari daftar putar")
                    }
                    1 -> confirmDeleteFile(item)
                }
            }
            .show()
    }

    private fun confirmDeleteFile(item: VideoItem) {
        AlertDialog.Builder(this)
            .setTitle("Hapus file asli?")
            .setMessage("\"${item.title}\" akan dihapus permanen dari penyimpanan HP dan tidak bisa dikembalikan.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ -> deleteOriginal(item) }
            .show()
    }

    private fun deleteOriginal(item: VideoItem) {
        when {
            Build.VERSION.SDK_INT >= 30 -> deleteApi30(item)
            Build.VERSION.SDK_INT == 29 -> deleteApi29(item)
            else -> {
                if (ContextCompat.checkSelfPermission(
                        this, Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    deleteLegacy(item)
                } else {
                    pendingDelete = item
                    writePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
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
            item.path?.let {
                ok = try { File(it).delete() } catch (_: Exception) { false }
            }
        }
        afterDelete(ok)
    }

    private fun afterDelete(ok: Boolean) {
        toast(if (ok) "File dihapus" else "Gagal menghapus file")
        refresh()
    }
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
        holder.b.tvFolder.text = "\uD83D\uDCC1 " + item.folder.ifBlank { "Tanpa folder" }
        holder.b.tvDur.text = fmtTime(item.duration)
        Glide.with(holder.b.ivThumb).load(item.uri).centerCrop().into(holder.b.ivThumb)
        holder.b.root.setOnClickListener { onClick(item) }
        holder.b.root.setOnLongClickListener { onLongClick(item); true }
    }
}
