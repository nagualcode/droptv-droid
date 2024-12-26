package com.br.fred.droptv

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import com.br.fred.droptv.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var exoPlayer: ExoPlayer? = null

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

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        restartStream()
                    }
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

        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            binding.splashOverlay.visibility = android.view.View.GONE
        }, 6000)
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
    }

    override fun onPause() {
        super.onPause()
        releasePlayer()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

 //   override fun onBackPressed() {
 //       super.onBackPressed()
 //       finish()
 //   }

//    override fun onUserLeaveHint() {
//        super.onUserLeaveHint()
//        finish()
//    }
//
}
