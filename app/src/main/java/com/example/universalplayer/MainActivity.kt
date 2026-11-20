package com.example.universalplayer

import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private lateinit var controlsRow: View
    private lateinit var rootLayout: LinearLayout
    private lateinit var fullScreenButton: Button
    private lateinit var gestureHint: TextView
    private val videos = mutableListOf<Uri>()
    private var currentIndex = 0
    private var externalSubtitle: Uri? = null
    private var selectedSubtitleFor: Uri? = null
    private var subtitleScanReportedFor: Uri? = null
    private var isFullScreen = false

    private var touchStartX = 0f
    private var touchStartY = 0f
    private var gestureStarted = false
    private var gestureSide = GestureSide.NONE
    private var initialGestureValue = 0f
    private var lastHintAt = 0L

    private enum class GestureSide { NONE, BRIGHTNESS, VOLUME }

    private val pickVideos = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        videos.clear()
        videos.addAll(uris)
        currentIndex = 0
        uris.forEach { uri ->
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        }
        playCurrent()
    }

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            externalSubtitle = uri
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            if (videos.isNotEmpty()) playCurrent()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.player_view)
        titleView = findViewById(R.id.title)
        controlsRow = findViewById(R.id.controls_row)
        rootLayout = findViewById(R.id.root_layout)
        fullScreenButton = findViewById(R.id.full_screen)
        gestureHint = findViewById(R.id.gesture_hint)

        findViewById<Button>(R.id.open_video).setOnClickListener {
            pickVideos.launch(arrayOf("video/*"))
        }
        findViewById<Button>(R.id.next_video).setOnClickListener { nextVideo() }
        findViewById<Button>(R.id.subtitles).setOnClickListener {
            pickSubtitle.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream"))
        }
        fullScreenButton.setOnClickListener { setFullScreen(!isFullScreen) }

        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            playerView.player = exoPlayer
            exoPlayer.addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    selectEmbeddedSubtitleIfPresent(tracks)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        selectEmbeddedSubtitleIfPresent(exoPlayer.currentTracks)
                    }
                }
            })
        }
        playerView.useController = true
        playerView.controllerAutoShow = true
        playerView.setOnTouchListener { view, event -> handlePlayerTouch(view, event) }
        titleView.text = "Choose a video to begin"
    }

    private fun playCurrent() {
        val p = player ?: return
        if (videos.isEmpty()) return
        val uri = videos[currentIndex]
        selectedSubtitleFor = null
        subtitleScanReportedFor = null
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()

        val item = MediaItem.Builder().setUri(uri)
        externalSubtitle?.let { subtitleUri ->
            val mime = subtitleMime(subtitleUri)
            item.setSubtitleConfigurations(
                listOf(MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                    .setMimeType(mime)
                    .setLanguage("und")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build())
            )
        }
        p.setMediaItem(item.build())
        p.prepare()
        p.playWhenReady = true
        titleView.text = "Video ${currentIndex + 1} of ${videos.size}"
    }

    private fun selectEmbeddedSubtitleIfPresent(tracks: Tracks) {
        val uri = videos.getOrNull(currentIndex) ?: return
        if (selectedSubtitleFor == uri) return
        val availableTracks = buildList {
            tracks.groups.forEach { group ->
                if (group.type != C.TRACK_TYPE_TEXT) return@forEach
                for (trackIndex in 0 until group.length) {
                    val format = group.getTrackFormat(trackIndex)
                    val label = format.label?.takeIf { it.isNotBlank() }
                        ?: format.language?.takeIf { it.isNotBlank() && it != "und" }
                        ?: "Subtitle ${size + 1}"
                    add(SubtitleTrack(
                        group,
                        trackIndex,
                        (format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0,
                        label
                    ))
                }
            }
        }
        if (availableTracks.isEmpty()) {
            val p = player
            if (p?.playbackState == Player.STATE_READY && subtitleScanReportedFor != uri) {
                subtitleScanReportedFor = uri
                Toast.makeText(this, "No embedded subtitle tracks were detected in this video", Toast.LENGTH_LONG).show()
            }
            return
        }

        subtitleScanReportedFor = uri
        selectedSubtitleFor = uri
        val hasSelectedTrack = tracks.groups.any { group ->
            group.type == C.TRACK_TYPE_TEXT && (0 until group.length).any { index -> group.isTrackSelected(index) }
        }
        if (hasSelectedTrack) return

        val p = player ?: return
        val chosen = availableTracks.firstOrNull { it.isDefault } ?: availableTracks.first()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .addOverride(androidx.media3.common.TrackSelectionOverride(chosen.group.mediaTrackGroup, chosen.trackIndex))
            .build()
        Toast.makeText(this, "Playing subtitle track: ${chosen.label}", Toast.LENGTH_LONG).show()
    }

    private data class SubtitleTrack(
        val group: Tracks.Group,
        val trackIndex: Int,
        val isDefault: Boolean,
        val label: String
    )

    private fun subtitleMime(uri: Uri): String {
        val name = uri.lastPathSegment?.lowercase().orEmpty()
        return when {
            name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            name.endsWith(".ttml") || name.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
            name.endsWith(".ssa") || name.endsWith(".ass") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

    private fun setFullScreen(enabled: Boolean) {
        isFullScreen = enabled
        titleView.visibility = if (enabled) View.GONE else View.VISIBLE
        controlsRow.visibility = if (enabled) View.GONE else View.VISIBLE
        rootLayout.setPadding(if (enabled) 0 else dp(16), if (enabled) 0 else dp(16), if (enabled) 0 else dp(16), if (enabled) 0 else dp(16))
        fullScreenButton.text = if (enabled) "Exit full screen" else "Full screen"
        val insets = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            insets.hide(WindowInsetsCompat.Type.systemBars())
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun handlePlayerTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                gestureStarted = false
                gestureSide = GestureSide.NONE
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaY = touchStartY - event.y
                val deltaX = event.x - touchStartX
                if (!gestureStarted && abs(deltaY) > dp(18) && abs(deltaY) > abs(deltaX) * 1.2f) {
                    gestureStarted = true
                    gestureSide = if (touchStartX < view.width / 2f) GestureSide.BRIGHTNESS else GestureSide.VOLUME
                    initialGestureValue = when (gestureSide) {
                        GestureSide.BRIGHTNESS -> currentBrightness()
                        GestureSide.VOLUME -> currentVolumeFraction()
                        GestureSide.NONE -> 0f
                    }
                }
                if (!gestureStarted) return false
                val change = deltaY / view.height.coerceAtLeast(1) * 1.5f
                val value = (initialGestureValue + change).coerceIn(0f, 1f)
                when (gestureSide) {
                    GestureSide.BRIGHTNESS -> setBrightness(value)
                    GestureSide.VOLUME -> setVolume(value)
                    GestureSide.NONE -> Unit
                }
                showGestureHint(value)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val consumed = gestureStarted
                gestureStarted = false
                gestureSide = GestureSide.NONE
                return consumed
            }
        }
        return gestureStarted
    }

    private fun currentBrightness(): Float {
        val appBrightness = window.attributes.screenBrightness
        if (appBrightness >= 0f) return appBrightness
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (_: Settings.SettingNotFoundException) {
            0.5f
        }
    }

    private fun setBrightness(value: Float) {
        val attributes = window.attributes
        attributes.screenBrightness = value.coerceIn(0.01f, 1f)
        window.attributes = attributes
    }

    private fun currentVolumeFraction(): Float {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    private fun setVolume(value: Float) {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (value * max).roundToInt().coerceIn(0, max), 0)
    }

    private fun showGestureHint(value: Float) {
        val now = System.currentTimeMillis()
        if (now - lastHintAt < 90) return
        lastHintAt = now
        val percent = (value * 100).roundToInt()
        gestureHint.text = if (gestureSide == GestureSide.BRIGHTNESS) "Brightness $percent%" else "Volume $percent%"
        gestureHint.visibility = View.VISIBLE
        gestureHint.removeCallbacks(hideGestureHint)
        gestureHint.postDelayed(hideGestureHint, 850)
    }

    private val hideGestureHint = Runnable { gestureHint.visibility = View.GONE }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun nextVideo() {
        if (videos.isEmpty()) {
            Toast.makeText(this, "Choose videos first", Toast.LENGTH_SHORT).show()
            return
        }
        currentIndex = (currentIndex + 1) % videos.size
        playCurrent()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
