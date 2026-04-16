package com.cappielloantonio.tempo.model

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.jvm.JvmOverloads

@Keep
@Entity(tableName = "replaygain_cache")
data class ReplayGainCache @JvmOverloads constructor(
    @PrimaryKey
    @ColumnInfo(name = "media_id")
    var mediaId: String,
    @ColumnInfo(name = "track_gain")
    var trackGain: Float = 0f,
    @ColumnInfo(name = "album_gain")
    var albumGain: Float = 0f,
    @ColumnInfo(name = "track_peak")
    var trackPeak: Float = 0f,
    @ColumnInfo(name = "album_peak")
    var albumPeak: Float = 0f,
    @ColumnInfo(name = "updated_at")
    var updatedAt: Long = System.currentTimeMillis()
)
