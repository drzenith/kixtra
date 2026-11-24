package com.drzenith.kixtra.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.session.MediaSessionService
import com.drzenith.kixtra.App
import com.drzenith.kixtra.MainActivity

/** Exposes the shared player as a MediaSession: lock-screen controls + background audio. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        session = MediaSession.Builder(this, SessionPlayer(App.instance.playerController.player))
            .setSessionActivity(pending)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        App.instance.playerController.stop()
        stopSelf()
    }

    override fun onDestroy() {
        // The player belongs to the app, not the service; only release the session.
        session?.release()
        session = null
        super.onDestroy()
    }
}

/**
 * The player as seen by the system (picture-in-picture, notification, lock screen): without the
 * skip back/forward and previous/next commands, so those windows show just play/pause (and a seek
 * bar where the system has one). The in-app player keeps its own ±10s buttons.
 */
private class SessionPlayer(player: Player) : ForwardingPlayer(player) {
    private val hidden = intArrayOf(
        Player.COMMAND_SEEK_BACK,
        Player.COMMAND_SEEK_FORWARD,
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
    )

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().removeAll(*hidden).build()

    override fun isCommandAvailable(command: Int): Boolean =
        command !in hidden && super.isCommandAvailable(command)
}
