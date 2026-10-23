package com.fitsync.data.di

import android.content.Context
import androidx.room.Room
import com.fitsync.data.db.FitDataRepository
import com.fitsync.data.db.FitDataRepositoryImpl
import com.fitsync.data.db.FitSyncDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object FitDataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FitSyncDatabase =
        Room.databaseBuilder(
            context,
            FitSyncDatabase::class.java,
            FitSyncDatabase.DB_NAME,
        )
            .fallbackToDestructiveMigration() // v1 阶段；上线后替换为显式 Migration
            .build()

    @Provides
    @Singleton
    fun provideRepository(db: FitSyncDatabase): FitDataRepository =
        FitDataRepositoryImpl(db)
}