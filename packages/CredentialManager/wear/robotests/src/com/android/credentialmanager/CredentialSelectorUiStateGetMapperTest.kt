/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.credentialmanager

import java.time.Instant
import android.graphics.drawable.Drawable
import com.android.credentialmanager.model.get.CredentialEntryInfo
import com.android.credentialmanager.model.get.ActionEntryInfo
import com.android.credentialmanager.model.get.AuthenticationEntryInfo
import com.android.credentialmanager.model.Request
import androidx.test.filters.SmallTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.mockito.kotlin.mock
import org.junit.runner.RunWith
import com.android.credentialmanager.model.CredentialType
import com.google.common.truth.Truth.assertThat
import com.android.credentialmanager.ui.mappers.toGet
import com.android.credentialmanager.model.get.ProviderInfo
import com.android.credentialmanager.CredentialSelectorUiState.Get.MultipleEntry.PerNameEntries

/** Unit tests for [CredentialSelectorUiStateGetMapper]. */
@SmallTest
@RunWith(AndroidJUnit4::class)
class CredentialSelectorUiStateGetMapperTest {

    private val mDrawable = mock<Drawable>()

    private val actionEntryInfo =
        ActionEntryInfo(
            providerId = "",
            entryKey = "",
            entrySubkey = "",
            pendingIntent = null,
            fillInIntent = null,
            title = "title",
            icon = mDrawable,
            subTitle = "subtitle",
        )

    private val authenticationEntryInfo =
        AuthenticationEntryInfo(
            providerId = "",
            entryKey = "",
            entrySubkey = "",
            pendingIntent = null,
            fillInIntent = null,
            title = "title",
            providerDisplayName = "",
            icon = mDrawable,
            isUnlockedAndEmpty = true,
            isLastUnlocked = true
        )

    private val passkeyCredentialEntryInfo =
        createCredentialEntryInfo(credentialType = CredentialType.PASSKEY, userName = "userName")

    private val unknownCredentialEntryInfo =
        createCredentialEntryInfo(credentialType = CredentialType.UNKNOWN, userName = "userName2")

    private val passwordCredentialEntryInfo =
        createCredentialEntryInfo(credentialType = CredentialType.PASSWORD, userName = "userName")

    private val recentlyUsedPasskeyCredential =
        createCredentialEntryInfo(credentialType =
    CredentialType.PASSKEY, lastUsedTimeMillis = 2L, userName = "userName")

    private val recentlyUsedPasswordCredential =
        createCredentialEntryInfo(credentialType =
    CredentialType.PASSWORD, lastUsedTimeMillis = 2L, userName = "userName")

    private val credentialList1 = listOf(
        passkeyCredentialEntryInfo,
        passwordCredentialEntryInfo
    )

    private val credentialList2 = listOf(
        passkeyCredentialEntryInfo,
        passwordCredentialEntryInfo,
        recentlyUsedPasskeyCredential,
        unknownCredentialEntryInfo,
        recentlyUsedPasswordCredential
    )

    @Test
    fun `On primary screen, just one account returns SingleEntry`() {
        val getCredentialUiState = Request.Get(
            token = null,
            resultReceiver = null,
            providerInfos = listOf(createProviderInfo(credentialList1))).toGet(isPrimary = true)

        assertThat(getCredentialUiState).isEqualTo(
            CredentialSelectorUiState.Get.SingleEntry(passkeyCredentialEntryInfo)
        ) // prefer passkey over password for selected credential
    }

    @Test
    fun `On primary screen, multiple accounts returns MultipleEntryPrimaryScreen`() {
        val getCredentialUiState = Request.Get(
            token = null,
            resultReceiver = null,
            providerInfos = listOf(createProviderInfo(listOf(passkeyCredentialEntryInfo,
                unknownCredentialEntryInfo)))).toGet(isPrimary = true)

        assertThat(getCredentialUiState).isEqualTo(
            CredentialSelectorUiState.Get.MultipleEntryPrimaryScreen(
                sortedEntries = listOf(
                    passkeyCredentialEntryInfo, // userName
                    unknownCredentialEntryInfo // userName2
                ),
                icon = mDrawable,
                authenticationEntryList = listOf(authenticationEntryInfo)
            )) // prefer passkey from account 1, then unknown from account 2
    }

    @Test
    fun `On secondary screen, a MultipleEntry is returned`() {
        val getCredentialUiState = Request.Get(
            token = null,
            resultReceiver = null,
            providerInfos = listOf(createProviderInfo(credentialList1))).toGet(isPrimary = false)

        assertThat(getCredentialUiState).isEqualTo(
            CredentialSelectorUiState.Get.MultipleEntry(
                listOf(PerNameEntries("userName", listOf(
                    passkeyCredentialEntryInfo,
                    passwordCredentialEntryInfo))
                ),
                listOf(actionEntryInfo),
                listOf(authenticationEntryInfo)
            ))
    }

    @Test
    fun `Returned multiple entry is sorted by credentialType and lastUsedTimeMillis`() {
        val getCredentialUiState = Request.Get(
            token = null,
            resultReceiver = null,
            providerInfos = listOf(createProviderInfo(credentialList1),
                createProviderInfo(credentialList2))).toGet(isPrimary = false)

        assertThat(getCredentialUiState).isEqualTo(
            CredentialSelectorUiState.Get.MultipleEntry(
                listOf(
                    PerNameEntries("userName",
                        listOf(
                            recentlyUsedPasskeyCredential, // from provider 2
                            passkeyCredentialEntryInfo, // from provider 1 or 2
                            passkeyCredentialEntryInfo, // from provider 1 or 2
                            recentlyUsedPasswordCredential, // from provider 2
                            passwordCredentialEntryInfo, // from provider 1 or 2
                            passwordCredentialEntryInfo, // from provider 1 or 2
                        )),
                    PerNameEntries("userName2", listOf(unknownCredentialEntryInfo)),
                ),
                listOf(actionEntryInfo, actionEntryInfo),
                listOf(authenticationEntryInfo, authenticationEntryInfo)
            )
        )
    }

    @Test
    fun `Returned multiple entry is grouped by display name if present`() {
        val testCred1 = createCredentialEntryInfo(displayName = "testDisplayName",
            userName = "testUserName", credentialType = CredentialType.PASSWORD)
        val testCred2 = createCredentialEntryInfo(displayName = "testDisplayName",
            userName = "testUserName", credentialType = CredentialType.PASSKEY)
        val getCredentialUiState = Request.Get(
            token = null,
            resultReceiver = null,
            providerInfos = listOf(createProviderInfo(credentialList1),
                createProviderInfo(credentialList2),
                createProviderInfo(listOf(testCred1, testCred2))))
            .toGet(isPrimary = false)

        assertThat(getCredentialUiState).isEqualTo(
            CredentialSelectorUiState.Get.MultipleEntry(
                listOf(
                    PerNameEntries("userName",
                        listOf(
                            recentlyUsedPasskeyCredential, // from provider 2
                            passkeyCredentialEntryInfo, // from provider 1 or 2
                            passkeyCredentialEntryInfo, // from provider 1 or 2
                            recentlyUsedPasswordCredential, // from provider 2
                            passwordCredentialEntryInfo, // from provider 1 or 2
                            passwordCredentialEntryInfo, // from provider 1 or 2
                        )),
                    PerNameEntries("userName2", listOf(unknownCredentialEntryInfo)),
                    PerNameEntries("testDisplayName", listOf(testCred2, testCred1)),
                ),
                listOf(actionEntryInfo, actionEntryInfo, actionEntryInfo),
                listOf(authenticationEntryInfo, authenticationEntryInfo, authenticationEntryInfo)
            )
        )
    }

    fun createCredentialEntryInfo(
        userName: String,
        displayName: String? = null,
        credentialType: CredentialType = CredentialType.PASSKEY,
        lastUsedTimeMillis: Long = 0L
    ): CredentialEntryInfo =
        CredentialEntryInfo(
            providerId = "",
            entryKey = "",
            entrySubkey = "",
            pendingIntent = null,
            fillInIntent = null,
            credentialType = credentialType,
            rawCredentialType = "",
            credentialTypeDisplayName = "",
            providerDisplayName = "",
            userName = userName,
            displayName = displayName,
            icon = mDrawable,
            shouldTintIcon = false,
            lastUsedTimeMillis = Instant.ofEpochMilli(lastUsedTimeMillis),
            isAutoSelectable = true,
            entryGroupId = "",
            isDefaultIconPreferredAsSingleProvider = false,
            affiliatedDomain = "",
        )

    fun createProviderInfo(credentials: List<CredentialEntryInfo> = listOf()): ProviderInfo =
        ProviderInfo(
            id = "providerInfo",
            icon = mDrawable,
            displayName = "displayName",
            credentialEntryList = credentials,
            authenticationEntryList = listOf(authenticationEntryInfo),
            remoteEntry = null,
            actionEntryList = listOf(actionEntryInfo)
        )
}
