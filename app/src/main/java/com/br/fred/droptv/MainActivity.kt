package com.br.fred.droptv

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var exoPlayer: ExoPlayer? = null
    private var retryCount = 0

    companion object {
        lateinit var STREAM_URL: String
        private const val INITIAL_DELAY = 1000L
    }

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        STREAM_URL = getString(R.string.stream_url)

        supportActionBar?.hide()
        setupView()
        initializePlayer()
        showSplashOverlay()
    }

    private fun setupView() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }

    @UnstableApi
    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            binding.playerView.player = this
            val mediaSource = HlsMediaSource.Factory(DefaultHttpDataSource.Factory())
                .createMediaSource(MediaItem.fromUri(STREAM_URL))
            setMediaSource(mediaSource)
            playWhenReady = true
            prepare()

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
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

        Handler(Looper.getMainLooper()).postDelayed({
            binding.splashOverlay.visibility = android.view.View.GONE
        }, 4500)
    }

    @UnstableApi
    private fun attemptRestartStream() {
        if (isNetworkAvailable()) {
            retryCount++
            Handler(Looper.getMainLooper()).postDelayed({
                restartStream()
            }, getRetryDelay())
        } else {

            Handler(Looper.getMainLooper()).postDelayed({
                attemptRestartStream()
            }, INITIAL_DELAY)
        }
    }

    @UnstableApi
    private fun restartStream() {
        exoPlayer?.let {
            it.stop()
            it.clearMediaItems()
            val mediaSource = HlsMediaSource.Factory(DefaultHttpDataSource.Factory())
                .createMediaSource(MediaItem.fromUri(STREAM_URL))
            it.setMediaSource(mediaSource)
            it.prepare()
            it.playWhenReady = true
            retryCount = 0
        }
    }

    private fun getRetryDelay(): Long {
        return (2.0.pow(retryCount.toDouble()) * 1000).toLong()
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


    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        finish()
    }
}

