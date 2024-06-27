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

package com.android.credentialmanager.ui.mappers

import android.graphics.drawable.Drawable
import com.android.credentialmanager.model.Request
import com.android.credentialmanager.CredentialSelectorUiState
import com.android.credentialmanager.CredentialSelectorUiState.Get.MultipleEntry.PerUserNameEntries
import com.android.credentialmanager.model.CredentialType
import com.android.credentialmanager.model.get.CredentialEntryInfo
import java.time.Instant

fun Request.Get.toGet(isPrimary: Boolean): CredentialSelectorUiState.Get {
    val accounts = providerInfos
        .flatMap { it.credentialEntryList }
        .groupBy { it.userName}
        .entries
        .toList()

    return if (isPrimary) {
        if (accounts.size == 1) {
            CredentialSelectorUiState.Get.SingleEntry(
                entry = accounts[0].value.minWith(comparator)
            )
        } else {
            val sortedEntries = accounts.map {
                it.value.minWith(comparator)
            }.sortedWith(comparator)

            var icon: Drawable? = null
            // provide icon if all entries have the same provider
            if (sortedEntries.isNotEmpty() &&
                sortedEntries.all {it.providerId == sortedEntries[0].providerId}) {
                icon = providerInfos[0].icon
            }

            CredentialSelectorUiState.Get.MultipleEntryPrimaryScreen(
                sortedEntries = sortedEntries,
                icon = icon,
                authenticationEntryList = providerInfos.flatMap { it.authenticationEntryList }
            )
        }
    } else {
        CredentialSelectorUiState.Get.MultipleEntry(
            accounts = accounts.map { PerUserNameEntries(
                it.key,
                it.value.sortedWith(comparator)
            )
            },
            actionEntryList = providerInfos.flatMap { it.actionEntryList },
            authenticationEntryList = providerInfos.flatMap { it.authenticationEntryList }
        )
    }
}
val comparator = compareBy<CredentialEntryInfo> { entryInfo ->
    // Passkey type always go first
    entryInfo.credentialType.let { if (it == CredentialType.PASSKEY) 0 else 1 }
}.thenByDescending { it.lastUsedTimeMillis ?: Instant.EPOCH }
