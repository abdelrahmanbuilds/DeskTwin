package com.desktwin.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@Composable
fun DeskTwinIntro(videoUri: String, onFinished: () -> Unit, onPlaybackError: () -> Unit) {
    val context = LocalContext.current
    val latestOnFinished by rememberUpdatedState(onFinished)
    val player = remember(context, videoUri) {
        ExoPlayer.Builder(context)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build().apply {
            volume = 1f
            playbackParameters = PlaybackParameters(1f)
            val selectedUri = Uri.parse(videoUri)
            setMediaItem(MediaItem.fromUri(selectedUri))
            prepare()
        }
    }

    LaunchedEffect(player) {
        player.play()
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                onPlaybackError()
                latestOnFinished()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) latestOnFinished()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    Dialog(
        onDismissRequest = onFinished,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).also { view ->
                        view.useController = false
                        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        view.player = player
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize()
            )
            TextButton(onClick = onFinished, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                Text("Skip", color = Color.White)
            }
        }
    }
}
