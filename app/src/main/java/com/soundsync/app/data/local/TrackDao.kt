package com.soundsync.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Query("SELECT * FROM synced_tracks ORDER BY downloadedAt DESC")
    fun getAllTracksFlow(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM synced_tracks ORDER BY downloadedAt DESC")
    suspend fun getAllTracksList(): List<TrackEntity>

    @Query("SELECT trackId FROM synced_tracks")
    suspend fun getAllTrackIds(): List<Long>

    @Query("SELECT * FROM synced_tracks WHERE trackId = :id LIMIT 1")
    suspend fun getTrackById(id: Long): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Update
    suspend fun update(track: TrackEntity)

    @Query("DELETE FROM synced_tracks WHERE trackId = :id")
    suspend fun deleteById(id: Long)

    @Delete
    suspend fun deleteTracks(tracks: List<TrackEntity>)

    @Query("DELETE FROM synced_tracks")
    suspend fun clearAll()
}
