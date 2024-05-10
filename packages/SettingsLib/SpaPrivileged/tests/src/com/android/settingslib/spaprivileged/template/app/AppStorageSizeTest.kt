/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spaprivileged.template.app

import android.app.usage.StorageStats
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager.NameNotFoundException
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.framework.compose.stateOf
import com.android.settingslib.spaprivileged.framework.common.storageStatsManager
import com.android.settingslib.spaprivileged.model.app.userHandle
import java.util.UUID
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Spy
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
class AppStorageSizeTest {
    @get:Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @get:Rule
    val composeTestRule = createComposeRule()

    @Spy
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Mock
    private lateinit var storageStatsManager: StorageStatsManager

    private val app = ApplicationInfo().apply {
        storageUuid = UUID.randomUUID()
    }

    @Before
    fun setUp() {
        whenever(context.storageStatsManager).thenReturn(storageStatsManager)
        whenever(
            storageStatsManager.queryStatsForPackage(
                app.storageUuid,
                app.packageName,
                app.userHandle,
            )
        ).thenReturn(STATS)
    }

    @Test
    fun getStorageSize() {
        var storageSize = stateOf("")

        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                storageSize = app.getStorageSize()
            }
        }

        composeTestRule.waitUntil { storageSize.value == "120 B" }
    }

    @Test
    fun getStorageSize_throwException() {
        var storageSize = stateOf("Computing")
        whenever(
            storageStatsManager.queryStatsForPackage(
                app.storageUuid,
                app.packageName,
                app.userHandle,
            )
        ).thenThrow(NameNotFoundException())

        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides context) {
                storageSize = app.getStorageSize()
            }
        }

        composeTestRule.waitUntil { storageSize.value == "" }
    }

    companion object {
        private val STATS = StorageStats().apply {
            codeBytes = 100
            dataBytes = 20
            cacheBytes = 3
        }
    }
}
