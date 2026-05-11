package com.cappielloantonio.tempo.service

import androidx.core.content.ContextCompat
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.repository.AutomotiveRepository
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

@UnstableApi
class MediaService : BaseMediaService(), SessionAvailabilityListener {
    private val automotiveRepository = AutomotiveRepository()
    private lateinit var castPlayer: CastPlayer

    @Suppress("DEPRECATION")
    private fun initializeCastPlayer() {
        if (GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
        ) {
            CastContext.getSharedInstance(this, ContextCompat.getMainExecutor(this))
                .addOnSuccessListener { castContext ->
                    castPlayer = CastPlayer(castContext)
                    castPlayer.setSessionAvailabilityListener(this@MediaService)
                    // Do NOT call initializePlayerListener here. The listener is
                    // installed by setPlayer() only when a Cast session actually
                    // becomes available, preventing the crossfadeManager from being
                    // silently re-attached to castPlayer while the user is playing
                    // locally (Bug: crossfade volume changes would target castPlayer
                    // rather than the local ExoPlayer).
                    if (castPlayer.isCastSessionAvailable)
                        setPlayer(mediaLibrarySession.player, castPlayer)
                }
        }
    }

    override fun getMediaLibrarySessionCallback(): MediaLibrarySession.Callback {
        return MediaLibrarySessionCallback(this, automotiveRepository)
    }

    override fun playerInitHook() {
        super.playerInitHook()
        initializeCastPlayer()
        if (this::castPlayer.isInitialized && castPlayer.isCastSessionAvailable)
            setPlayer(null, castPlayer)
    }

    override fun releasePlayers() {
        if (this::castPlayer.isInitialized) {
            castPlayer.setSessionAvailabilityListener(null)
            castPlayer.release()
        }
        automotiveRepository.deleteMetadata()
        super.releasePlayers()
    }

    override fun onCastSessionAvailable() {
        setPlayer(exoplayer, castPlayer)
    }

    override fun onCastSessionUnavailable() {
        setPlayer(castPlayer, exoplayer)
    }
}
