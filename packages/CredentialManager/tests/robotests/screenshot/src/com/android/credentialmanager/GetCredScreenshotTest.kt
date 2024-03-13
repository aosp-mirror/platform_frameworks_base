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

import android.credentials.flags.Flags
import android.content.Context
import android.platform.test.flag.junit.SetFlagsRule
import androidx.compose.ui.test.isPopup
import com.android.credentialmanager.getflow.RequestDisplayInfo
import com.android.credentialmanager.model.CredentialType
import com.android.credentialmanager.model.get.ProviderInfo
import com.android.credentialmanager.model.get.CredentialEntryInfo
import platform.test.screenshot.getEmulatedDevicePathConfig
import platform.test.screenshot.utils.compose.ComposeScreenshotTestRule
import com.android.credentialmanager.getflow.toProviderDisplayInfo
import com.android.credentialmanager.getflow.toActiveEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters
import platform.test.screenshot.DeviceEmulationSpec
import platform.test.screenshot.PhoneAndTabletFull
import androidx.test.core.app.ApplicationProvider
import com.android.credentialmanager.common.ui.ModalBottomSheet
import com.android.credentialmanager.getflow.PrimarySelectionCard
import com.android.credentialmanager.tests.screenshot.R

/** A screenshot test for our Get-Credential flows. */
@RunWith(ParameterizedAndroidJunit4::class)
class GetCredScreenshotTest(emulationSpec: DeviceEmulationSpec) {
    companion object {
        @Parameters(name = "{0}")
        @JvmStatic
        fun getTestSpecs() = DeviceEmulationSpec.PhoneAndTabletFull

        val REQUEST_DISPLAY_INFO = RequestDisplayInfo(
                appName = "Test App",
                preferImmediatelyAvailableCredentials = false,
                preferIdentityDocUi = false,
                preferTopBrandingContent = null,
                typePriorityMap = emptyMap(),
        )
    }

    @get:Rule
    val screenshotRule = ComposeScreenshotTestRule(
            emulationSpec,
            CredentialManagerGoldenPathManager(getEmulatedDevicePathConfig(emulationSpec))
    )

    @get:Rule val setFlagsRule: SetFlagsRule = SetFlagsRule()

    @Test
    fun singleCredentialScreen_M3BottomSheetDisabled() {
        setFlagsRule.disableFlags(Flags.FLAG_SELECTOR_UI_IMPROVEMENTS_ENABLED)
        val providerInfoList = buildProviderInfoList()
        val providerDisplayInfo = toProviderDisplayInfo(providerInfoList, emptyMap())
        val activeEntry = toActiveEntry(providerDisplayInfo)
        screenshotRule.screenshotTest("singleCredentialScreen") {
            ModalBottomSheet(
                    sheetContent = {
                        PrimarySelectionCard(
                                requestDisplayInfo = REQUEST_DISPLAY_INFO,
                                providerDisplayInfo = providerDisplayInfo,
                                providerInfoList = providerInfoList,
                                activeEntry = activeEntry,
                                onEntrySelected = {},
                                onConfirm = {},
                                onMoreOptionSelected = {},
                                onLog = {},
                        )
                    },
                    isInitialRender = true,
                    onDismiss = {},
                    onInitialRenderComplete = {},
                    isAutoSelectFlow = false,
            )
        }
    }

    @Test
    fun singleCredentialScreen_M3BottomSheetEnabled() {
        setFlagsRule.enableFlags(Flags.FLAG_SELECTOR_UI_IMPROVEMENTS_ENABLED)
        val providerInfoList = buildProviderInfoList()
        val providerDisplayInfo = toProviderDisplayInfo(providerInfoList, emptyMap())
        val activeEntry = toActiveEntry(providerDisplayInfo)
        screenshotRule.screenshotTest(
                "singleCredentialScreen_newM3BottomSheet",
                // M3's ModalBottomSheet lives in a new window, meaning we have two windows with
                // a root. Hence use a different matcher `isPopup`.
                viewFinder = { screenshotRule.composeRule.onNode(isPopup()) },
        ) {
            ModalBottomSheet(
                    sheetContent = {
                        PrimarySelectionCard(
                                requestDisplayInfo = REQUEST_DISPLAY_INFO,
                                providerDisplayInfo = providerDisplayInfo,
                                providerInfoList = providerInfoList,
                                activeEntry = activeEntry,
                                onEntrySelected = {},
                                onConfirm = {},
                                onMoreOptionSelected = {},
                                onLog = {},
                        )
                    },
                    isInitialRender = true,
                    onDismiss = {},
                    onInitialRenderComplete = {},
                    isAutoSelectFlow = false,
            )
        }
    }

    private fun buildProviderInfoList(): List<ProviderInfo> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider1 = ProviderInfo(
                id = "1",
                icon = context.getDrawable(R.drawable.provider1)!!,
                displayName = "Password Manager 1",
                credentialEntryList = listOf(
                        CredentialEntryInfo(
                                providerId = "1",
                                entryKey = "key1",
                                entrySubkey = "subkey1",
                                pendingIntent = null,
                                fillInIntent = null,
                                credentialType = CredentialType.PASSWORD,
                                credentialTypeDisplayName = "Passkey",
                                providerDisplayName = "Password Manager 1",
                                userName = "username",
                                displayName = "Display Name",
                                icon = null,
                                shouldTintIcon = true,
                                lastUsedTimeMillis = null,
                                isAutoSelectable = false,
                                entryGroupId = "username",
                                isDefaultIconPreferredAsSingleProvider = false,
                                rawCredentialType = "unknown-type",
                                affiliatedDomain = null,
                        )
                ),
                authenticationEntryList = emptyList(),
                remoteEntry = null,
                actionEntryList = emptyList(),
        )
        return listOf(provider1)
    }
}