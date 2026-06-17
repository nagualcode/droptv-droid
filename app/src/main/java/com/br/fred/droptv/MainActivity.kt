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

        showSplashOverlay()
        
        // A inicialização do player foi removida do onCreate e movida para o onStart
        // respeitando as diretrizes do Android Lifecycle.
    }

    // -------------------------------------------------------------------------
    // LIFECYCLE
    // -------------------------------------------------------------------------

    override fun onStart() {
        super.onStart()
        // API 24+ recomenda inicializar recursos de mídia no onStart
        initializePlayer()
    }

    override fun onResume() {
        super.onResume()
        // Garante que a reprodução inicie/continue quando o app estiver visível
        exoPlayer?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        // Apenas pausa a reprodução, mas não destrói o player ainda
        exoPlayer?.playWhenReady = false
    }

    override fun onStop() {
        super.onStop()
        // API 24+ recomenda liberar os recursos no onStop
        releasePlayer()
        
        // Força a finalização da Activity quando ela sai de cena (TV dormiu ou mudou de app).
        // Isso garante o seu objetivo de "matar" o app para iniciar do zero na próxima vez.
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterNetworkCallback()
        releasePlayer()
        nowExecutor.shutdownNow()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Executado primariamente quando o usuário aperta o botão HOME
        finish()
    }

    // -------------------------------------------------------------------------
    // PLAYER
    // -------------------------------------------------------------------------

    private fun initializePlayer() {
        if (exoPlayer != null) return // Evita recriar se já existir

        exoPlayer = ExoPlayer.Builder(this).build().apply {
            binding.playerView.player = this

            val mediaItem = MediaItem.fromUri(STREAM_URL)
            val mediaSource = HlsMediaSource.Factory(DefaultHttpDataSource.Factory())
                .createMediaSource(mediaItem)

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
                        Player.STATE_ENDED, Player.STATE_IDLE -> {
                            scheduleRestart()
                        }
                        Player.STATE_BUFFERING -> {
                            // Opcional: tratar longos períodos de buffering
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    scheduleRestart()
                }
            })
        }
    }

    private fun releasePlayer() {
        cancelScheduledRestart()
        stopNowPolling()
        exoPlayer?.let { player ->
            try {
                player.stop()
            } catch (_: Exception) {}
            player.release()
        }
        exoPlayer = null
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
                    // Evita disparar reinicializações se o app estiver sendo fechado
                    if (!isFinishing && !isDestroyed) {
                        scheduleRestart(forceImmediate = true)
                    }
                }
            }

            override fun onLost(network: Network) {
                mainHandler.post {
                    if (!isFinishing && !isDestroyed) {
                        scheduleRestart()
                    }
                }
            }
        }
        connectivityManager.registerDefaultNetworkCallback(netCallback!!)
    }

    private fun unregisterNetworkCallback() {
        netCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        netCallback = null
    }

    // --- RESTART / BACKOFF ---

    private fun scheduleRestart(forceImmediate: Boolean = false) {
        cancelScheduledRestart()
        if (isFinishing || isDestroyed) return

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
        if (isFinishing || isDestroyed) return

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

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // -------------------------------------------------------------------------
    // NOW PLAYING
    // -------------------------------------------------------------------------

    private fun startNowPolling() {
        if (nowPollingRunnable != null) return

        nowPollingRunnable = object : Runnable {
            override fun run() {
                if (!binding.nowPlayingBanner.isVisible) {
                    stopNowPolling()
                    return
                }

                nowExecutor.execute {
                    try {
                        fetchAndUpdateNowPlaying()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        if (!isFinishing && !isDestroyed) {
                            mainHandler.postDelayed(this, NOW_POLL_INTERVAL_MS)
                        }
                    }
                }
            }
        }

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

            if (musicFile == lastMusicFile) return
            lastMusicFile = musicFile

            val title = json.optString("title", "")
            val artist = json.optString("artist", "")
            val coverUrl = json.optString("cover_url", "")

            val displayTitle = title.ifBlank { musicFile }

            if (!isFinishing && !isDestroyed) {
                runOnUiThread {
                    updateNowPlayingBanner(displayTitle, artist, coverUrl)
                }
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

    private fun adjustBannerWidthForText(title: String, artist: String) {
        val root = binding.rootContainer
        val longest = maxOf(title.length, artist.length)

        root.post {
            val w = root.width
            if (w <= 0) return@post

            val minFactor = 0.3f
            val maxFactor = 0.9f
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

    private fun loadCoverImage(urlStr: String) {
        binding.coverImage.visibility = View.VISIBLE

        Thread {
            try {
                val url = URL(urlStr)
                url.openStream().use { input ->
                    BufferedInputStream(input).use { bis ->
                        val bmp = BitmapFactory.decodeStream(bis)
                        if (!isFinishing && !isDestroyed) {
                            runOnUiThread {
                                binding.coverImage.setImageBitmap(bmp)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (!isFinishing && !isDestroyed) {
                    runOnUiThread {
                        binding.coverImage.setImageBitmap(null)
                        binding.coverImage.visibility = View.GONE
                    }
                }
            }
        }.start()
    }

    // -------------------------------------------------------------------------
    // REMOTE CONTROL
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
            startNowPolling()
        } else {
            stopNowPolling()
        }
    }
}