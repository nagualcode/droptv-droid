package com.br.fred.droptv

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.ConnectivityManager.NetworkCallback
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.br.fred.droptv.databinding.ActivityMainBinding
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
        private const val INITIAL_DELAY_MS = 1000L
        private const val MAX_BACKOFF_MS = 5000L // Maximum retry interval (5 seconds)
        private const val JITTER_MS = 300L // Small random jitter to avoid sync retries
    }

    private lateinit var connectivityManager: ConnectivityManager
    private var netCallback: NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        STREAM_URL = getString(R.string.stream_url)

        supportActionBar?.hide()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        registerNetworkCallback()

        initializePlayer()
        showSplashOverlay()
    }

    private fun initializePlayer() {
        // Release previous player if exists
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
                            // If playback is actually running, reset retry counter
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
                            // Player has no media source; schedule restart
                            scheduleRestart()
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    // Schedule restart attempt on any playback error
                    scheduleRestart()
                }
            })
        }
    }

    private fun showSplashOverlay() {
        binding.splashOverlay.apply {
            setBackgroundResource(R.drawable.droptv)
            visibility = android.view.View.VISIBLE
            bringToFront()
        }

        mainHandler.postDelayed({
            binding.splashOverlay.visibility = android.view.View.GONE
        }, 4500)
    }

    // --- NETWORK CALLBACK ---
    private fun registerNetworkCallback() {
        netCallback = object : NetworkCallback() {
            override fun onAvailable(network: Network) {
                // When network is available again, force immediate restart
                mainHandler.post {
                    scheduleRestart(forceImmediate = true)
                }
            }

            override fun onLost(network: Network) {
                // When network is lost, keep trying periodically
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
            } catch (_: Exception) { }
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

        restartRunnable = Runnable {
            restartStream()
        }.also {
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
        exoPlayer?.let { player ->
            try {
                player.stop()
            } catch (_: Exception) { }
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
}
