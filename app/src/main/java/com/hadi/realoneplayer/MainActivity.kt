package com.hadi.realoneplayer

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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.hadi.realoneplayer.databinding.ActivityMainBinding
import com.hadi.realoneplayer.databinding.ItemVideoBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val adapter = VideoAdapter { openVideo(it) }
    private var all: List<VideoItem> = emptyList()
    private var resumeChecked = false

    private val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) onGranted() else showEmpty("Izin akses video diperlukan untuk menampilkan daftar video.")
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
        all = VideoRepo.load(this)
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
        holder.b.tvFolder.text = item.folder
        holder.b.tvDur.text = fmtTime(item.duration)
        Glide.with(holder.b.ivThumb).load(item.uri).centerCrop().into(holder.b.ivThumb)
        holder.b.root.setOnClickListener { onClick(item) }
    }
}
