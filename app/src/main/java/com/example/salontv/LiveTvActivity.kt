package com.example.salontv

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Télévision en direct intégrée : lit la playlist M3U de la Freebox
 * dans un lecteur Media3 natif, sans quitter le launcher.
 */
@UnstableApi
class LiveTvActivity : Activity() {

    private data class Channel(val name: String, val url: String)

    private lateinit var channelList: ListView
    private lateinit var status: TextView
    private lateinit var playerView: PlayerView

    private var player: ExoPlayer? = null
    private var channels: List<Channel> = emptyList()

    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF0B1220.toInt())
        }

        channelList = ListView(this).apply {
            divider = ColorDrawable(0x22FFFFFF)
            dividerHeight = 1
            selector = ColorDrawable(0xFFE60012.toInt())
            isFocusable = true
            isFocusableInTouchMode = true
        }

        root.addView(
            channelList,
            LinearLayout.LayoutParams(
                (320 * density).toInt(),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val right = FrameLayout(this)

        playerView = PlayerView(this).apply {
            useController = false
            isFocusable = false
        }

        right.addView(
            playerView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(
                (24 * density).toInt(),
                (24 * density).toInt(),
                (24 * density).toInt(),
                (24 * density).toInt()
            )
            text = "Chargement de la liste des chaînes…"
        }

        right.addView(
            status,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(
            right,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            )
        )

        setContentView(root)

        loadPlaylist()
    }

    private fun loadPlaylist() {
        worker.execute {
            try {
                val text = download(PLAYLIST_URL)
                val parsed = parsePlaylist(text)

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    if (parsed.isEmpty()) {
                        showStatus(
                            "Aucune chaîne trouvée dans la playlist " +
                                "de la Freebox."
                        )
                    } else {
                        showChannels(parsed)
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread

                    showStatus(
                        "Playlist de la Freebox inaccessible. " +
                            "Vérifie que la box et ce lecteur sont " +
                            "sur le même réseau.\n\n" +
                            (error.message ?: "")
                    )
                }
            }
        }
    }

    private fun showChannels(list: List<Channel>) {
        channels = list

        channelList.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            list.map { it.name }
        )

        channelList.setOnItemClickListener { _, _, position, _ ->
            play(channels[position])
        }

        channelList.requestFocus()
        showStatus("Choisis une chaîne dans la liste.")
    }

    private fun play(channel: Channel) {
        val exo = player ?: ExoPlayer.Builder(this).build().also {
            player = it
            playerView.player = it

            it.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING ->
                            showStatus("Chargement…")

                        Player.STATE_READY ->
                            status.visibility = android.view.View.GONE

                        else -> Unit
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    showStatus(
                        "Cette chaîne n'a pas pu être lue dans le " +
                            "lecteur intégré (" +
                            error.errorCodeName + ")."
                    )
                }
            })
        }

        showStatus("Chargement de ${channel.name}…")

        exo.setMediaItem(MediaItem.fromUri(channel.url))
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = android.view.View.VISIBLE
    }

    private fun download(address: String): String {
        val connection = URL(address).openConnection() as HttpURLConnection

        connection.connectTimeout = 8000
        connection.readTimeout = 8000

        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException(
                    "Réponse HTTP " + connection.responseCode
                )
            }

            return connection.inputStream
                .bufferedReader()
                .use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parsePlaylist(text: String): List<Channel> {
        val result = mutableListOf<Channel>()
        var pendingName: String? = null

        text.lineSequence().forEach { raw ->
            val line = raw.trim()

            when {
                line.startsWith("#EXTINF") -> {
                    pendingName = line
                        .substringAfterLast(',', "")
                        .trim()
                        .ifEmpty { null }
                }

                line.isNotEmpty() && !line.startsWith("#") -> {
                    result.add(
                        Channel(pendingName ?: line, line)
                    )
                    pendingName = null
                }
            }
        }

        return result
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val PLAYLIST_URL =
            "http://mafreebox.freebox.fr/freeboxtv/playlist.m3u"
    }
}
