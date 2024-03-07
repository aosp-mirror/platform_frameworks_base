/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.settingslib.spaprivileged.settingsprovider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.testutils.firstWithTimeoutOrNull
import com.android.settingslib.spa.testutils.toListWithTimeout
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsGlobalChangeFlowTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun settingsGlobalChangeFlow_sendInitialValueTrue() = runBlocking {
        val flow = context.settingsGlobalChangeFlow(name = TEST_NAME, sendInitialValue = true)

        assertThat(flow.firstWithTimeoutOrNull()).isNotNull()
    }

    @Test
    fun settingsGlobalChangeFlow_sendInitialValueFalse() = runBlocking {
        val flow = context.settingsGlobalChangeFlow(name = TEST_NAME, sendInitialValue = false)

        assertThat(flow.firstWithTimeoutOrNull()).isNull()
    }

    @Test
    fun settingsGlobalChangeFlow_collectAfterValueChanged_onlyKeepLatest() = runBlocking {
        var value by context.settingsGlobalBoolean(TEST_NAME)
        value = false

        val flow = context.settingsGlobalChangeFlow(TEST_NAME)
        value = true

        assertThat(flow.toListWithTimeout()).hasSize(1)
    }

    @Test
    fun settingsGlobalChangeFlow_collectBeforeValueChanged_getBoth() = runBlocking {
        var value by context.settingsGlobalBoolean(TEST_NAME)
        value = false

        val listDeferred = async {
            context.settingsGlobalChangeFlow(TEST_NAME).toListWithTimeout()
        }
        delay(100)
        value = true

        assertThat(listDeferred.await()).hasSize(2)
    }

    private companion object {
        const val TEST_NAME = "test_boolean_delegate"
    }
}
