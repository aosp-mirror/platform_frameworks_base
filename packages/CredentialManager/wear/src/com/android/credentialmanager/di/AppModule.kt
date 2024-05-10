package com.android.credentialmanager.di

import com.android.credentialmanager.client.CredentialManagerClient
import com.android.credentialmanager.client.impl.CredentialManagerClientImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds
    @Singleton
    abstract fun provideCredentialManagerClient(
        client: CredentialManagerClientImpl
    ): CredentialManagerClient
}

