package com.drzenith.kixtra.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
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
        session = MediaSession.Builder(this, App.instance.playerController.player)
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
