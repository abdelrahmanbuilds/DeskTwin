package com.desktwin.app.ui.screens

import android.content.res.Configuration
import android.net.Uri
import android.view.ViewTreeObserver
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.desktwin.app.network.DeskTwinConnection
import com.desktwin.app.ui.theme.tr

private val PlayerSurface = Color(0xFF02060C)

/** Range streaming: only the bytes needed for playback/seek are requested. */
@OptIn(UnstableApi::class)
@Composable
fun VideoStreamScreen(ip: String, port: Int, token: String, fileId: Int, path: String, name: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var zoomed by remember { mutableStateOf(false) }
    var retryAttempt by remember { mutableIntStateOf(0) }

    val player = remember(ip, port, token, fileId, path, retryAttempt) {
        val dataSource = OkHttpDataSource.Factory(DeskTwinConnection.streamingClient())
            .setDefaultRequestProperties(mapOf("X-Device-Token" to token))
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dataSource))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                val streamUri = Uri.parse("https://$ip:$port/files/$fileId/content")
                    .buildUpon()
                    .appendQueryParameter("stream", "1")
                    .appendQueryParameter("path", path)
                    .build()
                setMediaItem(MediaItem.fromUri(streamUri))
                prepare()
                playWhenReady = true
            }
    }
    var playbackState by remember(player) { mutableIntStateOf(player.playbackState) }
    var playbackFailed by remember(player) { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                if (state == Player.STATE_READY) playbackFailed = false
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackFailed = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    DisposableEffect(activity, isLandscape) {
        val window = activity?.window
        val decorView = window?.decorView
        val bars = WindowInsetsCompat.Type.systemBars()
        val insetsController = window?.let { WindowCompat.getInsetsController(it, decorView!!) }
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus && isLandscape) insetsController?.hide(bars)
        }

        if (window != null && decorView != null) {
            WindowCompat.setDecorFitsSystemWindows(window, !isLandscape)
            if (isLandscape) {
                insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                decorView.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
                insetsController?.hide(bars)
            } else {
                insetsController?.show(bars)
            }
        }

        onDispose {
            if (decorView != null && decorView.viewTreeObserver.isAlive) {
                decorView.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            }
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowCompat.getInsetsController(window, window.decorView).show(bars)
            }
        }
    }

    if (isLandscape) {
        Box(Modifier.fillMaxSize()) {
            PlayerStage(
                player = player,
                zoomed = zoomed,
                playbackState = playbackState,
                playbackFailed = playbackFailed,
                onRetry = { retryAttempt++ },
                immersive = true,
                modifier = Modifier.fillMaxSize(),
            )
            Surface(
                shape = RoundedCornerShape(50),
                color = Color.Black.copy(alpha = 0.62f),
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("رجوع", "Back"), tint = Color.White)
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("رجوع", "Back"), tint = MaterialTheme.colorScheme.onSurface)
                }
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                    Text(
                        tr("بث مباشر · لا يُحفظ الفيديو على الهاتف", "Live stream · video is not saved on the phone"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                IconButton(onClick = { zoomed = !zoomed }) {
                    Icon(
                        if (zoomed) Icons.Default.FitScreen else Icons.Default.ZoomIn,
                        contentDescription = if (zoomed) tr("ملاءمة الشاشة", "Fit screen") else tr("تكبير", "Zoom"),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            PlayerStage(
                player = player,
                zoomed = zoomed,
                playbackState = playbackState,
                playbackFailed = playbackFailed,
                onRetry = { retryAttempt++ },
                immersive = false,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerStage(
    player: ExoPlayer,
    zoomed: Boolean,
    playbackState: Int,
    playbackFailed: Boolean,
    onRetry: () -> Unit,
    immersive: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(PlayerSurface), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    useController = true
                    setControllerAutoShow(!immersive)
                    if (immersive) setControllerShowTimeoutMs(2_500)
                    setKeepContentOnPlayerReset(true)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    resizeMode = if (zoomed) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                    if (immersive) hideController()
                }
            },
            update = { view ->
                view.player = player
                view.setControllerAutoShow(!immersive)
                if (immersive) view.setControllerShowTimeoutMs(2_500)
                view.resizeMode = if (zoomed) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                if (immersive) view.hideController()
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (playbackFailed || playbackState != Player.STATE_READY) {
            Surface(
                modifier = Modifier.padding(24.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                tonalElevation = 6.dp,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (playbackFailed) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(30.dp))
                        Text(tr("تعذّر بدء البث", "Couldn't start stream"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            tr("تأكد أن الكمبيوتر متصل وأن الفيديو ما زال متاحًا، ثم أعد المحاولة.", "Make sure the computer is connected and the video is still available, then try again."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = onRetry) { Text(tr("إعادة المحاولة", "Try again")) }
                    } else {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                        Text(tr("جارٍ تجهيز البث…", "Preparing stream…"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}
