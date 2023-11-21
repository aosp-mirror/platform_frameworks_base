/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0N
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.credentialmanager.mapper

import android.content.Intent
import android.credentials.ui.Entry
import androidx.credentials.provider.PasswordCredentialEntry
import com.android.credentialmanager.factory.fromSlice
import com.android.credentialmanager.ktx.getCredentialProviderDataList
import com.android.credentialmanager.ktx.requestInfo
import com.android.credentialmanager.ktx.resultReceiver
import com.android.credentialmanager.model.Password
import com.android.credentialmanager.model.Request
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap

fun Intent.toGet(): Request.Get {
    val credentialEntries = mutableListOf<Pair<String, Entry>>()
    for (providerData in getCredentialProviderDataList) {
        for (credentialEntry in providerData.credentialEntries) {
            credentialEntries.add(
                Pair(providerData.providerFlattenedComponentName, credentialEntry)
            )
        }
    }

    val passwordEntries = mutableListOf<Password>()
    for ((providerId, entry) in credentialEntries) {
        val slice = fromSlice(entry.slice)
        if (slice is PasswordCredentialEntry) {
            passwordEntries.add(
                Password(
                    providerId = providerId,
                    entry = entry,
                    passwordCredentialEntry = slice
                )
            )
        }
    }

    return Request.Get(
        token = requestInfo?.token,
        resultReceiver = this.resultReceiver,
        providers = ImmutableMap.copyOf(
            getCredentialProviderDataList.associateBy { it.providerFlattenedComponentName }
        ),
        passwordEntries = ImmutableList.copyOf(passwordEntries)
    )
}
