package com.android.credentialmanager.mapper

import android.content.Intent
import android.credentials.ui.GetCredentialProviderData
import com.android.credentialmanager.ktx.getCredentialProviderDataList
import com.android.credentialmanager.model.Request
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap

fun Intent.toGet() = Request.Get(
    providers = ImmutableMap.copyOf(
        getCredentialProviderDataList.associateBy { it.providerFlattenedComponentName }
    ),
    entries = ImmutableList.copyOf(
        getCredentialProviderDataList.map { providerData ->
            check(providerData is GetCredentialProviderData) {
                "Invalid provider data type for GetCredentialRequest"
            }
            providerData
        }.flatMap { it.credentialEntries }
    )
)