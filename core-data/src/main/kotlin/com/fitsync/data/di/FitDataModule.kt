package com.fitsync.data.di

import android.content.Context
import androidx.room.Room
import com.fitsync.data.backup.RestoreHelper
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
    fun provideDatabase(@ApplicationContext context: Context): FitSyncDatabase {
        // 有未完成的恢复就先替换文件，Room 打开之后就来不及了
        RestoreHelper.applyIfNeeded(context)
        return Room.databaseBuilder(
            context,
            FitSyncDatabase::class.java,
            FitSyncDatabase.DB_NAME,
        )
            // 显式迁移，绝不 destructive：升级不能把用户同步了几年的数据清掉
            .addMigrations(FitSyncDatabase.MIGRATION_1_2)
            .build()
    }

    @Provides
    @Singleton
    fun provideRepository(db: FitSyncDatabase): FitDataRepository =
        FitDataRepositoryImpl(db)
}