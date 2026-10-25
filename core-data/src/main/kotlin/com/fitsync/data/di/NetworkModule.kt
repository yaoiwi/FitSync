package com.fitsync.data.di

import com.fitsync.data.network.CredentialStore
import com.fitsync.data.network.CredentialVault
import com.fitsync.data.network.MemoryCookieJar
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(cookieJar: MemoryCookieJar): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(cookieJar) // Garmin SSO 依赖 Cookie 会话，缺了它登录永远拿不到 ticket
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

    @Provides
    @Singleton
    fun provideCredentialStore(vault: CredentialVault): CredentialStore = vault
}
