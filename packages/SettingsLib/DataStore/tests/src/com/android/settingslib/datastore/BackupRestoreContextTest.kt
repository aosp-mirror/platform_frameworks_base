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

package com.android.settingslib.datastore

import android.app.backup.BackupDataOutput
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/** Tests of [BackupContext] and [RestoreContext]. */
@RunWith(AndroidJUnit4::class)
class BackupRestoreContextTest {
    @Test
    fun backupContext_quota() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val data = mock<BackupDataOutput> { on { quota } doReturn 10L }
        assertThat(BackupContext(data).quota).isEqualTo(10)
    }

    @Test
    fun backupContext_transportFlags() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val data = mock<BackupDataOutput> { on { transportFlags } doReturn 5 }
        assertThat(BackupContext(data).transportFlags).isEqualTo(5)
    }
}
