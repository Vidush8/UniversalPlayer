package com.example.universalplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MainActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView
    private val videos = mutableListOf<Uri>()
    private var currentIndex = 0
    private var externalSubtitle: Uri? = null

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
        findViewById<Button>(R.id.open_video).setOnClickListener {
            pickVideos.launch(arrayOf("video/*"))
        }
        findViewById<Button>(R.id.next_video).setOnClickListener { nextVideo() }
        findViewById<Button>(R.id.subtitles).setOnClickListener {
            pickSubtitle.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream"))
        }
        player = ExoPlayer.Builder(this).build().also { playerView.player = it }
        playerView.useController = true
        playerView.controllerAutoShow = true
        titleView.text = "Choose a video to begin"
    }

    private fun playCurrent() {
        val p = player ?: return
        if (videos.isEmpty()) return
        val uri = videos[currentIndex]
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

    private fun subtitleMime(uri: Uri): String {
        val name = uri.lastPathSegment?.lowercase().orEmpty()
        return when {
            name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            name.endsWith(".ttml") || name.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
            name.endsWith(".ssa") || name.endsWith(".ass") -> MimeTypes.TEXT_SSA
            else -> MimeTypes.APPLICATION_SUBRIP
        }
    }

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
