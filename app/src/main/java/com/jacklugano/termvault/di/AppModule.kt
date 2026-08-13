package com.jacklugano.termvault.di

import android.content.Context
import androidx.room.Room
import com.jacklugano.termvault.data.db.AppDatabase
import com.jacklugano.termvault.data.db.HostDao
import com.jacklugano.termvault.data.db.KnownHostDao
import com.jacklugano.termvault.data.db.PortForwardDao
import com.jacklugano.termvault.data.db.SnippetDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "termvault.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides
    fun provideHostDao(db: AppDatabase): HostDao = db.hostDao()

    @Provides
    fun provideSnippetDao(db: AppDatabase): SnippetDao = db.snippetDao()

    @Provides
    fun provideKnownHostDao(db: AppDatabase): KnownHostDao = db.knownHostDao()

    @Provides
    fun providePortForwardDao(db: AppDatabase): PortForwardDao = db.portForwardDao()

    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): android.content.SharedPreferences =
        context.getSharedPreferences("termvault_settings", Context.MODE_PRIVATE)
}
