package com.br.fred.droptv

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var exoPlayer: ExoPlayer? = null
    private var retryCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var connectivityManager: ConnectivityManager
    private var isNetworkConnected = false

    companion object {
        lateinit var STREAM_URL: String
        private const val INITIAL_DELAY = 1000L
        private const val MAX_RETRY_DELAY = 5_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        STREAM_URL = getString(R.string.stream_url)
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        supportActionBar?.hide()
        setupView()
        registerNetworkCallback()
        initializePlayer()
        showSplashOverlay()
    }

    private fun setupView() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    private fun createMediaSource(): HlsMediaSource {
        val dataSourceFactory = DefaultHttpDataSource.Factory().apply {
            setAllowCrossProtocolRedirects(true)
            setDefaultRequestProperties(
                mapOf("Cache-Control" to "no-cache", "Pragma" to "no-cache")
            )
        }
        return HlsMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(STREAM_URL))
    }

    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            binding.playerView.player = this
            val mediaSource = createMediaSource()
            setMediaSource(mediaSource)
            playWhenReady = true
            prepare()

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) {
                        attemptRestartStream()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    attemptRestartStream()
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
        handler.postDelayed({
            binding.splashOverlay.visibility = android.view.View.GONE
        }, 4500)
    }

    private fun attemptRestartStream() {
        if (isNetworkConnected) {
            retryCount++
            handler.postDelayed({
                restartStream()
            }, getRetryDelay())
        } else {
            handler.postDelayed({
                attemptRestartStream()
            }, INITIAL_DELAY)
        }
    }

    private fun restartStream() {
        exoPlayer?.let {
            it.stop()
            it.clearMediaItems()
            val mediaSource = createMediaSource()
            it.setMediaSource(mediaSource)
            it.prepare()
            it.seekToDefaultPosition()
            it.playWhenReady = true
            retryCount = 0
        }
    }

    private fun getRetryDelay(): Long {
        val delay = (2.0.pow(retryCount.toDouble()) * 1000).toLong()
        return minOf(delay, MAX_RETRY_DELAY)
    }

    private fun releasePlayer() {
        exoPlayer?.release()
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

    private fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isNetworkConnected = true

                if (exoPlayer == null) {
                    initializePlayer()
                }
            }

            override fun onLost(network: Network) {
                isNetworkConnected = false
            }
        })
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        finishAndRemoveTask()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releasePlayer()
        connectivityManager.unregisterNetworkCallback(ConnectivityManager.NetworkCallback())
        super.onDestroy()
    }
}
