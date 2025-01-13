/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.settingslib.spaprivileged.model.app

import android.app.usage.StorageStats
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager.NameNotFoundException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spaprivileged.framework.common.storageStatsManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.stub
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppStorageRepositoryTest {
    private val app = ApplicationInfo().apply { storageUuid = UUID.randomUUID() }

    private val mockStorageStatsManager =
        mock<StorageStatsManager> {
            on { queryStatsForPackage(app.storageUuid, app.packageName, app.userHandle) } doReturn
                STATS
        }

    private val context: Context =
        spy(ApplicationProvider.getApplicationContext()) {
            on { storageStatsManager } doReturn mockStorageStatsManager
        }

    private val repository = AppStorageRepositoryImpl(context)

    @Test
    fun calculateSizeBytes() {
        val sizeBytes = repository.calculateSizeBytes(app)

        assertThat(sizeBytes).isEqualTo(120)
    }

    @Test
    fun formatSize() {
        val fileSize = repository.formatSize(app)

        assertThat(fileSize).isEqualTo("120 byte")
    }

    @Test
    fun formatSize_throwException() {
        mockStorageStatsManager.stub {
            on { queryStatsForPackage(app.storageUuid, app.packageName, app.userHandle) } doThrow
                NameNotFoundException()
        }

        val fileSize = repository.formatSize(app)

        assertThat(fileSize).isEqualTo("")
    }

    @Test
    fun formatSizeBytes() {
        val fileSize = repository.formatSizeBytes(120)

        assertThat(fileSize).isEqualTo("120 byte")
    }

    companion object {
        private val STATS =
            StorageStats().apply {
                codeBytes = 100
                dataBytes = 20
            }
    }
}
