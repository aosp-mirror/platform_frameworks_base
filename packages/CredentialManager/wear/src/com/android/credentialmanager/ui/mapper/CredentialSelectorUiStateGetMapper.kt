package com.android.credentialmanager.ui.mapper

import androidx.credentials.provider.CustomCredentialEntry
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.android.credentialmanager.ui.CredentialSelectorUiState
import com.android.credentialmanager.ui.factory.fromSlice
import com.android.credentialmanager.ui.model.PasswordUiModel
import com.android.credentialmanager.ui.model.Request

fun Request.Get.toGet(): CredentialSelectorUiState.Get {
    if (this.providers.isEmpty()) {
        throw IllegalStateException("Invalid GetCredential request with empty list of providers.")
    }

    if (this.entries.isEmpty()) {
        throw IllegalStateException("Invalid GetCredential request with empty list of entries.")
    }

    if (this.providers.size == 1) {
        if (this.entries.size == 1) {
            val slice = this.entries.first().slice
            when (val credentialEntry = fromSlice(slice)) {
                is PasswordCredentialEntry -> {
                    return CredentialSelectorUiState.Get.SingleProviderSinglePassword(
                        PasswordUiModel(credentialEntry.displayName.toString())
                    )
                }

                is PublicKeyCredentialEntry -> {
                    TODO("b/301206470 - to be implemented")
                }

                is CustomCredentialEntry -> {
                    TODO("b/301206470 - to be implemented")
                }

                else -> {
                    throw IllegalStateException(
                        "Encountered unrecognized credential entry (${slice.spec?.type}) for " +
                            "GetCredential request with single account"
                    )
                }
            }
        } else {
            TODO("b/301206470 - to be implemented")
        }
    } else {
        TODO("b/301206470 - to be implemented")
    }
}