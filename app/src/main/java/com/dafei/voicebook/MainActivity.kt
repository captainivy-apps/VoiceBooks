package com.dafei.voicebook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dafei.voicebook.domain.playback.PlaybackController
import com.dafei.voicebook.domain.playback.PlaybackState
import com.dafei.voicebook.navigation.VoicebookNavGraph
import com.dafei.voicebook.ui.theme.VoicebookTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoicebookTheme {
                VoicebookNavGraph()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val playback = PlaybackController.state.value
        val sessionActive = playback.bookId.isNotEmpty() &&
            (playback.playbackState == PlaybackState.LOADING ||
                playback.playbackState == PlaybackState.PLAYING ||
                playback.playbackState == PlaybackState.PAUSED ||
                playback.playbackState == PlaybackState.ERROR)
        if (sessionActive) {
            PlaybackController.clearPlayerScreenDismissed()
        }
    }
}
