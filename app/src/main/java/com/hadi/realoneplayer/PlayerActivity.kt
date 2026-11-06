package com.hadi.realoneplayer

import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.hadi.realoneplayer.databinding.ActivityPlayerBinding
import java.io.File
import java.util.Locale

@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private lateinit var player: ExoPlayer
    private lateinit var items: List<VideoItem>
    private var index = 0

    private val handler = Handler(Looper.getMainLooper())
    private var tick = 0
    private var seeking = false
    private var locked = false
    private var controlsVisible = true
    private var exiting = false
    private var autoSubDone = false
    private var userDisabledSub = false

    // Bass / penguat suara hingga 200%
    private var bassOn = false
    private var enhancer: LoudnessEnhancer? = null
    private var bassBoost: BassBoost? = null

    private val hideRunnable = Runnable { setControlsVisible(false) }
    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val pickSubtitle =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val name = queryName(uri) ?: "subtitle.srt"
                val wasPlaying = player.playWhenReady
                playIndex(index, player.currentPosition, uri, name)
                player.playWhenReady = wasPlaying
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        volumeControlStream = AudioManager.STREAM_MUSIC

        items = Playlist.items.ifEmpty { VideoRepo.load(this) }
        Playlist.items = items
        val id = intent.getLongExtra("id", -1L)
        index = items.indexOfFirst { it.id == id }
        if (index < 0) {
            Toast.makeText(this, "Video tidak ditemukan", Toast.LENGTH_SHORT).show()
            exiting = true
            Prefs.clearWatching(this)
            finish()
            return
        }

        setupPlayer()
        setupUi()
        bassOn = Prefs.bassOn(this)
        updateBassIcon()
        applyBass(player.audioSessionId)

        playIndex(index, intent.getLongExtra("pos", 0L))
        handler.post(ticker)
        setControlsVisible(true)
    }

    // ---------------------------------------------------------------- player

    private fun setupPlayer() {
        player = ExoPlayer.Builder(this).build()
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
        player.setHandleAudioBecomingNoisy(true)
        b.playerView.player = player

        // Gaya subtitle: Calibri (Carlito), kuning dengan garis tepi hitam, bawah tengah.
        val typeface = try {
            ResourcesCompat.getFont(this, R.font.calibri)
        } catch (_: Exception) {
            null
        } ?: Typeface.SANS_SERIF
        b.playerView.subtitleView?.apply {
            setApplyEmbeddedStyles(false)
            setApplyEmbeddedFontSizes(false)
            setStyle(
                CaptionStyleCompat(
                    Color.YELLOW, Color.TRANSPARENT, Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, typeface
                )
            )
            // Ukuran otomatis menyesuaikan tinggi layar (~18sp pada layar HP landscape)
            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
            setBottomPaddingFraction(0.06f)
        }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                b.btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                setControlsVisible(controlsVisible)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) updateProgress()
                if (state == Player.STATE_ENDED) {
                    if (index < items.size - 1) playIndex(index + 1) else exitPlayer()
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                applyBass(audioSessionId)
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (autoSubDone || userDisabledSub) return
                val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                if (groups.isNotEmpty()) {
                    autoSubDone = true
                    if (groups.none { it.isSelected }) selectTextGroup(groups[0])
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "Video tidak bisa diputar", Toast.LENGTH_LONG).show()
                exitPlayer()
            }
        })
    }

    private fun playIndex(i: Int, pos: Long = 0L, extraSub: Uri? = null, extraName: String? = null) {
        index = i
        val item = items[i]
        autoSubDone = false
        if (extraSub == null) userDisabledSub = false

        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()

        val builder = MediaItem.Builder().setUri(item.uri).setMediaId(item.id.toString())
        val subs = ArrayList<MediaItem.SubtitleConfiguration>()
        if (extraSub != null) {
            subs.add(subConfig(extraSub, extraName ?: "subtitle.srt"))
        } else {
            findSiblingSubtitle(item)?.let { subs.add(subConfig(Uri.fromFile(it), it.name)) }
        }
        builder.setSubtitleConfigurations(subs)

        player.setMediaItem(builder.build(), pos)
        player.prepare()
        player.playWhenReady = true

        b.tvVideoTitle.text = item.title
        b.seekBar.progress = 0
        Prefs.save(this, item.id, pos, true)
    }

    private fun subConfig(uri: Uri, name: String): MediaItem.SubtitleConfiguration {
        val mime = when (name.substringAfterLast('.').lowercase(Locale.ROOT)) {
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        return MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLabel(name)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
    }

    /** Cari file subtitle dengan nama sama di folder video (jika bisa dibaca). */
    @Suppress("DEPRECATION")
    private fun findSiblingSubtitle(item: VideoItem): File? {
        val path = item.path ?: return null
        val base = path.substringBeforeLast('.', path)
        for (ext in listOf("srt", "ass", "ssa", "vtt")) {
            try {
                val f = File("$base.$ext")
                if (f.exists() && f.canRead()) return f
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun selectTextGroup(g: Tracks.Group) {
        userDisabledSub = false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
            .build()
    }

    // -------------------------------------------------------------------- UI

    private fun setupUi() {
        b.touchLayer.setOnClickListener { setControlsVisible(!controlsVisible) }

        b.btnBack.setOnClickListener { exitPlayer() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (locked) {
                    Toast.makeText(this@PlayerActivity, "Layar terkunci", Toast.LENGTH_SHORT).show()
                    setControlsVisible(true)
                } else exitPlayer()
            }
        })

        b.btnPlay.setOnClickListener {
            if (player.isPlaying) player.pause() else player.play()
            setControlsVisible(true)
        }
        b.btnPrev.setOnClickListener {
            if (player.currentPosition > 3000 || index == 0) player.seekTo(0)
            else playIndex(index - 1)
            setControlsVisible(true)
        }
        b.btnNext.setOnClickListener {
            if (index < items.size - 1) playIndex(index + 1)
            setControlsVisible(true)
        }

        b.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) b.tvCur.text = fmtTime(progress.toLong())
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {
                seeking = true
                handler.removeCallbacks(hideRunnable)
            }

            override fun onStopTrackingTouch(sb: SeekBar?) {
                seeking = false
                player.seekTo((sb?.progress ?: 0).toLong())
                setControlsVisible(true)
            }
        })

        b.btnBass.setOnClickListener {
            bassOn = !bassOn
            Prefs.setBass(this, bassOn)
            applyBass(player.audioSessionId)
            updateBassIcon()
            Toast.makeText(this, if (bassOn) "Bass 200% aktif" else "Bass 200% nonaktif", Toast.LENGTH_SHORT).show()
            setControlsVisible(true)
        }

        b.btnSub.setOnClickListener {
            showSubtitleDialog()
            setControlsVisible(true)
        }

        b.btnLock.setOnClickListener {
            locked = !locked
            b.btnLock.setImageResource(if (locked) R.drawable.ic_lock else R.drawable.ic_unlock)
            setControlsVisible(true)
        }

        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        b.btnVolUp.setOnClickListener {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            setControlsVisible(true)
        }
        b.btnVolDown.setOnClickListener {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            setControlsVisible(true)
        }
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        b.controls.visibility = if (visible && !locked) View.VISIBLE else View.GONE
        b.btnLock.visibility = if (visible) View.VISIBLE else View.GONE
        handler.removeCallbacks(hideRunnable)
        if (visible && player.isPlaying) handler.postDelayed(hideRunnable, 3500)
    }

    private fun updateProgress() {
        val dur = player.duration
        if (dur > 0) {
            b.seekBar.max = dur.toInt()
            b.tvTotal.text = fmtTime(dur)
        }
        val pos = player.currentPosition
        if (!seeking) {
            b.seekBar.progress = pos.toInt()
            b.tvCur.text = fmtTime(pos)
        }
        // simpan posisi tiap ~2 detik
        if (++tick % 4 == 0) saveNow()
    }

    private fun updateBassIcon() {
        b.btnBass.setColorFilter(if (bassOn) Color.RED else Color.WHITE)
    }

    private fun showSubtitleDialog() {
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val labels = ArrayList<String>()
        labels.add(if (userDisabledSub || groups.none { it.isSelected }) "✓ Matikan subtitle" else "Matikan subtitle")
        groups.forEachIndexed { i, g ->
            val f = g.getTrackFormat(0)
            val name = f.label
                ?: f.language?.let { Locale(it).displayLanguage }?.takeIf { it.isNotBlank() }
                ?: "Subtitle ${i + 1}"
            labels.add(if (g.isSelected && !userDisabledSub) "✓ $name" else name)
        }
        labels.add("Cari file subtitle (.srt / .ass / .vtt)…")

        AlertDialog.Builder(this)
            .setTitle("Subtitle")
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which == 0 -> {
                        userDisabledSub = true
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                    }
                    which == labels.size - 1 -> pickSubtitle.launch(arrayOf("*/*"))
                    else -> selectTextGroup(groups[which - 1])
                }
            }
            .show()
    }

    // ------------------------------------------------------------- bass 200%

    private fun applyBass(sessionId: Int) {
        releaseEffects()
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId == 0) return
        try {
            // +6 dB (600 mB) = amplitudo 2x = 200%
            enhancer = LoudnessEnhancer(sessionId).apply {
                setTargetGain(600)
                setEnabled(bassOn)
            }
        } catch (_: Exception) {
        }
        try {
            bassBoost = BassBoost(0, sessionId).apply {
                if (strengthSupported) setStrength(1000.toShort())
                setEnabled(bassOn)
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseEffects() {
        try { enhancer?.release() } catch (_: Exception) {}
        try { bassBoost?.release() } catch (_: Exception) {}
        enhancer = null
        bassBoost = null
    }

    // ------------------------------------------------------------- lifecycle

    private fun saveNow() {
        if (!exiting && ::player.isInitialized && ::items.isInitialized) {
            Prefs.save(this, items[index].id, player.currentPosition, true)
        }
    }

    private fun exitPlayer() {
        exiting = true
        Prefs.clearWatching(this)
        finish()
    }

    override fun onPause() {
        super.onPause()
        saveNow()
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) player.pause()
        saveNow()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releaseEffects()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
