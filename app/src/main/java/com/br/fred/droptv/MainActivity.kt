package com.br.fred.droptv

import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.ConnectivityManager.NetworkCallback
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.br.fred.droptv.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.pow
import kotlin.random.Random

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var exoPlayer: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var restartRunnable: Runnable? = null
    private var retryCount = 0

    companion object {
        lateinit var STREAM_URL: String
        lateinit var NOW_JSON_URL: String

        private const val INITIAL_DELAY_MS = 1000L
        private const val MAX_BACKOFF_MS = 5000L   // Maximum retry interval (5 seconds)
        private const val JITTER_MS = 300L         // Small random jitter to avoid sync retries
        private const val NOW_POLL_INTERVAL_MS = 5000L
    }

    private lateinit var connectivityManager: ConnectivityManager
    private var netCallback: NetworkCallback? = null

    // --- Now Playing Banner ---
    private var nowPollingRunnable: Runnable? = null
    private var lastMusicFile: String? = null
    private val nowExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // URLs from strings.xml
        STREAM_URL = getString(R.string.stream_url)
        NOW_JSON_URL = getString(R.string.now_json_url)

        supportActionBar?.hide()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        registerNetworkCallback()

        initializePlayer()
        showSplashOverlay()
    }

    // -------------------------------------------------------------------------
    // PLAYER
    // -------------------------------------------------------------------------

    private fun initializePlayer() {
        releasePlayer()

        exoPlayer = ExoPlayer.Builder(this).build().apply {
            binding.playerView.player = this

            val mediaItem = MediaItem.fromUri(STREAM_URL)
            val mediaSource = HlsMediaSource.Factory(DefaultHttpDataSource.Factory())
                .createMediaSource(mediaItem)

            // Always seek to live edge before preparing
            seekToDefaultPosition()

            setMediaSource(mediaSource)
            playWhenReady = true
            prepare()

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> {
                            if (isPlaying) {
                                retryCount = 0
                                cancelScheduledRestart()
                            }
                        }
                        Player.STATE_ENDED -> {
                            scheduleRestart()
                        }
                        Player.STATE_BUFFERING -> {
                            // Optional: handle long buffering scenarios here
                        }
                        Player.STATE_IDLE -> {
                            scheduleRestart()
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    scheduleRestart()
                }
            })
        }
    }

    private fun showSplashOverlay() {
        binding.splashOverlay.apply {
            setBackgroundResource(R.drawable.droptv)
            visibility = View.VISIBLE
            bringToFront()
        }

        mainHandler.postDelayed({
            binding.splashOverlay.visibility = View.GONE
        }, 4500)
    }

    // --- NETWORK CALLBACK ---

    private fun registerNetworkCallback() {
        netCallback = object : NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post {
                    scheduleRestart(forceImmediate = true)
                }
            }

            override fun onLost(network: Network) {
                mainHandler.post {
                    scheduleRestart()
                }
            }
        }
        connectivityManager.registerDefaultNetworkCallback(netCallback!!)
    }

    private fun unregisterNetworkCallback() {
        netCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        netCallback = null
    }

    // --- RESTART / BACKOFF ---

    private fun scheduleRestart(forceImmediate: Boolean = false) {
        cancelScheduledRestart()
        if (exoPlayer == null) return

        val delay = if (!isNetworkAvailable()) {
            INITIAL_DELAY_MS
        } else {
            if (forceImmediate) {
                0L
            } else {
                retryCount++
                val backoff = (2.0.pow(retryCount.toDouble()) * 1000).toLong()
                val limited = backoff.coerceAtMost(MAX_BACKOFF_MS)
                val jitter = Random.nextLong(0, JITTER_MS)
                (limited + jitter)
            }
        }

        restartRunnable = Runnable { restartStream() }.also {
            mainHandler.postDelayed(it, delay)
        }
    }

    private fun cancelScheduledRestart() {
        restartRunnable?.let {
            mainHandler.removeCallbacks(it)
            restartRunnable = null
        }
    }

    private fun restartStream() {
        exoPlayer?.let { player ->
            try {
                player.playWhenReady = false
                player.stop()
                player.clearMediaItems()

                val mediaItem = MediaItem.fromUri(STREAM_URL)
                val mediaSource = HlsMediaSource.Factory(DefaultHttpDataSource.Factory())
                    .createMediaSource(mediaItem)

                player.setMediaSource(mediaSource)
                player.seekToDefaultPosition()
                player.prepare()
                player.playWhenReady = true
            } catch (_: Exception) {
                scheduleRestart()
            }
        } ?: run {
            initializePlayer()
        }
    }

    private fun releasePlayer() {
        cancelScheduledRestart()
        stopNowPolling()
        exoPlayer?.let { player ->
            try {
                player.stop()
            } catch (_: Exception) {
            }
            player.release()
        }
        exoPlayer = null
    }

    override fun onPause() {
        super.onPause()
        releasePlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterNetworkCallback()
        releasePlayer()
        nowExecutor.shutdownNow()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        finish()
    }

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // -------------------------------------------------------------------------
    // NOW PLAYING: JSON polling only when banner is active
    // -------------------------------------------------------------------------

    private fun startNowPolling() {
        if (nowPollingRunnable != null) return

        nowPollingRunnable = object : Runnable {
            override fun run() {
                if (!binding.nowPlayingBanner.isVisible) {
                    // If the banner is no longer visible, stop polling
                    stopNowPolling()
                    return
                }

                // Run JSON fetch on the single-thread executor
                nowExecutor.execute {
                    try {
                        fetchAndUpdateNowPlaying()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        // Schedule next polling run on the main thread
                        mainHandler.postDelayed(this, NOW_POLL_INTERVAL_MS)
                    }
                }
            }
        }

        // First run immediately
        mainHandler.post(nowPollingRunnable!!)
    }

    private fun stopNowPolling() {
        nowPollingRunnable?.let {
            mainHandler.removeCallbacks(it)
        }
        nowPollingRunnable = null
    }

    private fun fetchAndUpdateNowPlaying() {
        try {
            val url = URL(NOW_JSON_URL)
            val connection = url.openConnection()
            connection.connect()

            val reader = BufferedReader(
                InputStreamReader(connection.getInputStream(), Charsets.UTF_8)
            )
            val jsonString = reader.use { it.readText() }
            val json = JSONObject(jsonString)

            val musicFile = json.optString("music_file", "")
            if (musicFile.isEmpty()) return

            // Update only if the track changed
            if (musicFile == lastMusicFile) return
            lastMusicFile = musicFile

            val title = json.optString("title", "")
            val artist = json.optString("artist", "")
            val coverUrl = json.optString("cover_url", "")

            // Fallback same as Roku: if title is blank, use music_file
            val displayTitle = title.ifBlank { musicFile }

            runOnUiThread {
                updateNowPlayingBanner(displayTitle, artist, coverUrl)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateNowPlayingBanner(title: String, artist: String, coverUrl: String?) {
        binding.titleText.text = title
        binding.artistText.text = artist

        adjustBannerWidthForText(title, artist)

        if (!coverUrl.isNullOrEmpty()) {
            loadCoverImage(coverUrl)
        } else {
            binding.coverImage.setImageBitmap(null)
            binding.coverImage.visibility = View.GONE
        }
    }

    // Simple width adjustment based on text size (Roku-style behavior)
    private fun adjustBannerWidthForText(title: String, artist: String) {
        val root = binding.rootContainer
        val longest = maxOf(title.length, artist.length)

        root.post {
            val w = root.width
            if (w <= 0) return@post

            val minFactor = 0.3f   // 30% of the screen width
            val maxFactor = 0.9f   // up to 90% of the screen width
            val baseChars = 24f

            val scale = (longest / baseChars).coerceAtLeast(1f)
            var factor = minFactor * scale
            if (factor > maxFactor) factor = maxFactor

            val newWidth = (w * factor).toInt()
            val params = binding.nowPlayingBanner.layoutParams as android.widget.FrameLayout.LayoutParams
            params.width = newWidth
            params.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            params.topMargin = (resources.displayMetrics.heightPixels * 0.05f).toInt()
            binding.nowPlayingBanner.layoutParams = params
        }
    }

    // Ephemeral cover download (no disk cache)
    private fun loadCoverImage(urlStr: String) {
        binding.coverImage.visibility = View.VISIBLE

        Thread {
            try {
                val url = URL(urlStr)
                url.openStream().use { input ->
                    BufferedInputStream(input).use { bis ->
                        val bmp = BitmapFactory.decodeStream(bis)
                        runOnUiThread {
                            binding.coverImage.setImageBitmap(bmp)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    binding.coverImage.setImageBitmap(null)
                    binding.coverImage.visibility = View.GONE
                }
            }
        }.start()
    }

    // -------------------------------------------------------------------------
    // REMOTE CONTROL: OK = toggle banner
    // -------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BUTTON_SELECT -> {
                    toggleNowPlayingBanner()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun toggleNowPlayingBanner() {
        val visible = !binding.nowPlayingBanner.isVisible
        binding.nowPlayingBanner.visibility = if (visible) View.VISIBLE else View.GONE

        if (visible) {
            // When showing, keep the last artwork/text for the current track (if any)
            startNowPolling()
        } else {
            // When hiding, stop polling but keep the current artwork in memory
            stopNowPolling()
            // Do NOT clear coverImage here, so the artwork is preserved
            // while the same track is still playing
        }
    }
}
