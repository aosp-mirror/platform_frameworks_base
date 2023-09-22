package com.android.credentialmanager.factory

import android.app.slice.Slice
import android.credentials.Credential
import androidx.credentials.PublicKeyCredential
import androidx.credentials.provider.CredentialEntry
import androidx.credentials.provider.CustomCredentialEntry
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.PublicKeyCredentialEntry

fun fromSlice(slice: Slice): CredentialEntry? =
    try {
        when (slice.spec?.type) {
            Credential.TYPE_PASSWORD_CREDENTIAL -> PasswordCredentialEntry.fromSlice(slice)!!

            PublicKeyCredential.TYPE_PUBLIC_KEY_CREDENTIAL ->
                PublicKeyCredentialEntry.fromSlice(slice)!!

            else -> CustomCredentialEntry.fromSlice(slice)!!
        }
    } catch (e: Exception) {
        // Try CustomCredentialEntry.fromSlice one last time in case the cause was a failed
        // password / passkey parsing attempt.
        CustomCredentialEntry.fromSlice(slice)
    }