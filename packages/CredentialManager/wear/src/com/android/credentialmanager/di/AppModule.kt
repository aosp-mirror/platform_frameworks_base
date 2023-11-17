package com.android.credentialmanager.di

import android.content.Context
import android.content.pm.PackageManager
import com.android.credentialmanager.client.CredentialManagerClient
import com.android.credentialmanager.client.impl.CredentialManagerClientImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object AppModule {
    @Provides
    @Singleton
    @JvmStatic
    fun providePackageManager(@ApplicationContext context: Context): PackageManager =
        context.packageManager

    @Provides
    @Singleton
    @JvmStatic
    fun provideCredentialManagerClient(packageManager: PackageManager): CredentialManagerClient =
        CredentialManagerClientImpl(packageManager)
}

