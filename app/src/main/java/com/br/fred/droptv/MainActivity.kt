package com.br.fred.droptv
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.br.fred.droptv.databinding.ActivityMainBinding
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var exoPlayer: ExoPlayer? = null
    private var watchdogJob: Job? = null

    companion object {
        lateinit var STREAM_URL: String
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
        }

        startPlaybackWatchdog()
    }

    private fun showSplashOverlay() {

        binding.splashOverlay.apply {
            setBackgroundResource(R.drawable.droptv)
            visibility = android.view.View.VISIBLE
            bringToFront()
        }

        Handler(Looper.getMainLooper()).postDelayed({
            binding.splashOverlay.visibility = android.view.View.GONE
        }, 3000)
    }


    private var lastPlaybackPosition: Long = 0
    private val playbackWatchdogInterval: Long = 8000L

    @OptIn(UnstableApi::class)
    private fun startPlaybackWatchdog() {
        watchdogJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                exoPlayer?.let { player ->
                    val currentPosition = player.currentPosition
                    if (currentPosition == lastPlaybackPosition) {
                        restartStream()
                    } else {
                        lastPlaybackPosition = currentPosition
                    }
                }
                delay(playbackWatchdogInterval)
            }
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
        }
    }

    private fun releasePlayer() {
        exoPlayer?.release()
        exoPlayer = null
        watchdogJob?.cancel()
    }

    override fun onPause() {
        super.onPause()
        releasePlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onBackPressed() {
        super.onBackPressed()
        finish()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        finish()
    }
}
