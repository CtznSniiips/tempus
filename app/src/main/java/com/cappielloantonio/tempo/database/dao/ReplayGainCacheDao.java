package com.cappielloantonio.tempo.database.dao;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import com.cappielloantonio.tempo.model.ReplayGainCache;

import java.util.List;

@Dao
public interface ReplayGainCacheDao {
    @Query("SELECT * FROM replaygain_cache WHERE media_id = :mediaId")
    ReplayGainCache getOne(String mediaId);

    @Query("SELECT * FROM replaygain_cache WHERE media_id IN (:mediaIds)")
    List<ReplayGainCache> getMany(List<String> mediaIds);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(ReplayGainCache replayGainCache);
}
