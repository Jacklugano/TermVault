package com.jacklugano.termvault.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun authModeToString(v: AuthMode): String = v.name

    @TypeConverter
    fun stringToAuthMode(v: String): AuthMode = AuthMode.valueOf(v)

    @TypeConverter
    fun forwardTypeToString(v: ForwardType): String = v.name

    @TypeConverter
    fun stringToForwardType(v: String): ForwardType = ForwardType.valueOf(v)
}

@Database(
    entities = [
        HostEntity::class,
        SnippetEntity::class,
        KnownHostEntity::class,
        PortForwardEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hostDao(): HostDao
    abstract fun snippetDao(): SnippetDao
    abstract fun knownHostDao(): KnownHostDao
    abstract fun portForwardDao(): PortForwardDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE hosts ADD COLUMN openVpnProfile TEXT NOT NULL DEFAULT ''"
                )
            }
        }
    }
}
