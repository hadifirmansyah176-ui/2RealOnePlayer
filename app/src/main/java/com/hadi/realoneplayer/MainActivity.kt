package com.videoh.realoneplayer

import android.Manifest
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
    private val adapter = VideoAdapter { openVideo(it) }
    private val folderAdapter = FolderAdapter { enterFolder(it) }
    private var all: List<VideoItem> = emptyList()
    private var currentFolder: FolderItem? = null
    private var searchOpen = false
    private var resumeChecked = false

    private val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) onGranted() else showEmpty("Izin akses video diperlukan untuk menampilkan daftar video.")
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

        // Fitur sembunyikan video sudah tidak ada: pastikan semua video tampil
        Prefs.clearHidden(this)

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
        all = VideoRepo.load(this)
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

    private fun openVideo(item: VideoItem) {
        Playlist.items = adapter.current
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("id", item.id))
    }
}

class VideoAdapter(private val onClick: (VideoItem) -> Unit) :
    RecyclerView.Adapter<VideoAdapter.VH>() {

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
