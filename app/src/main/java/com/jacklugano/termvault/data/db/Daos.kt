package com.jacklugano.termvault.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun getById(id: Long): HostEntity?

    @Query("SELECT * FROM hosts WHERE id != :excludeId ORDER BY name COLLATE NOCASE")
    suspend fun getAllExcept(excludeId: Long): List<HostEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(host: HostEntity): Long

    @Delete
    suspend fun delete(host: HostEntity)
}

@Dao
interface SnippetDao {
    @Query("SELECT * FROM snippets ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeAll(): Flow<List<SnippetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snippet: SnippetEntity): Long

    @Delete
    suspend fun delete(snippet: SnippetEntity)
}

@Dao
interface KnownHostDao {
    @Query("SELECT * FROM known_hosts ORDER BY hostname, port")
    fun observeAll(): Flow<List<KnownHostEntity>>

    @Query("SELECT * FROM known_hosts WHERE hostname = :hostname AND port = :port")
    suspend fun findFor(hostname: String, port: Int): List<KnownHostEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: KnownHostEntity): Long

    @Delete
    suspend fun delete(entry: KnownHostEntity)

    @Query("DELETE FROM known_hosts WHERE hostname = :hostname AND port = :port")
    suspend fun deleteAllFor(hostname: String, port: Int)
}

@Dao
interface PortForwardDao {
    @Query("SELECT * FROM port_forwards WHERE hostId = :hostId ORDER BY id")
    fun observeForHost(hostId: Long): Flow<List<PortForwardEntity>>

    @Query("SELECT * FROM port_forwards WHERE hostId = :hostId ORDER BY id")
    suspend fun getForHost(hostId: Long): List<PortForwardEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(forward: PortForwardEntity): Long

    @Delete
    suspend fun delete(forward: PortForwardEntity)
}
