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

import android.content.pm.ApplicationInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.framework.compose.stateOf
import com.android.settingslib.spaprivileged.model.app.AppStorageRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppStorageSizeTest {
    @get:Rule val composeTestRule = createComposeRule()

    private val app = ApplicationInfo().apply { storageUuid = UUID.randomUUID() }

    private val mockAppStorageRepository =
        mock<AppStorageRepository> { on { formatSize(app) } doReturn SIZE }

    @Test
    fun getStorageSize() {
        var storageSize = stateOf("")

        composeTestRule.setContent { storageSize = app.getStorageSize(mockAppStorageRepository) }

        composeTestRule.waitUntil { storageSize.value == SIZE }
    }

    private companion object {
        const val SIZE = "120 kB"
    }
}
