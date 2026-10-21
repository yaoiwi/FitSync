package com.fitgarminsync.data.di

import android.content.Context
import androidx.room.Room
import com.fitgarminsync.data.db.FitDataRepository
import com.fitgarminsync.data.db.FitDataRepositoryImpl
import com.fitgarminsync.data.db.FitGarminDatabase
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
    fun provideDatabase(@ApplicationContext context: Context): FitGarminDatabase =
        Room.databaseBuilder(
            context,
            FitGarminDatabase::class.java,
            FitGarminDatabase.DB_NAME,
        )
            .fallbackToDestructiveMigration() // v1 阶段；上线后替换为显式 Migration
            .build()

    @Provides
    @Singleton
    fun provideRepository(db: FitGarminDatabase): FitDataRepository =
        FitDataRepositoryImpl(db)
}