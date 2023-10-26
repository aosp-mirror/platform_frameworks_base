package com.android.credentialmanager.di

import android.content.Context
import android.content.pm.PackageManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
@Module
@InstallIn(SingletonComponent::class)
internal object AppModule {
    @Provides
    @JvmStatic
    fun providePackageManager(@ApplicationContext context: Context): PackageManager =
            context.packageManager
}

