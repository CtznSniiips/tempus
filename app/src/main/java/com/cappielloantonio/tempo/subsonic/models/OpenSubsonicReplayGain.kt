package com.cappielloantonio.tempo.subsonic.models

import android.os.Parcelable
import androidx.annotation.Keep
import kotlinx.parcelize.Parcelize

@Keep
@Parcelize
data class OpenSubsonicReplayGain(
    var trackGain: Float? = null,
    var trackPeak: Float? = null,
    var albumGain: Float? = null,
    var albumPeak: Float? = null
) : Parcelable
