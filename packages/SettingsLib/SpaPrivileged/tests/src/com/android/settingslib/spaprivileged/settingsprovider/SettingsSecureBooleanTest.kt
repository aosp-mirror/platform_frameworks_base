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
import android.provider.Settings
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
class SettingsSecureBooleanTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun getValue_setTrue_returnTrue() {
        Settings.Secure.putInt(context.contentResolver, TEST_NAME, 1)

        val value by context.settingsSecureBoolean(TEST_NAME)

        assertThat(value).isTrue()
    }

    @Test
    fun getValue_setFalse_returnFalse() {
        Settings.Secure.putInt(context.contentResolver, TEST_NAME, 0)

        val value by context.settingsSecureBoolean(TEST_NAME)

        assertThat(value).isFalse()
    }

    @Test
    fun setValue_setTrue_returnTrue() {
        var value by context.settingsSecureBoolean(TEST_NAME)

        value = true

        assertThat(Settings.Secure.getInt(context.contentResolver, TEST_NAME, 0)).isEqualTo(1)
    }

    @Test
    fun setValue_setFalse_returnFalse() {
        var value by context.settingsSecureBoolean(TEST_NAME)

        value = false

        assertThat(Settings.Secure.getInt(context.contentResolver, TEST_NAME, 1)).isEqualTo(0)
    }

    @Test
    fun settingsSecureBooleanFlow_valueNotChanged() = runBlocking {
        var value by context.settingsSecureBoolean(TEST_NAME)
        value = false

        val flow = context.settingsSecureBooleanFlow(TEST_NAME)

        assertThat(flow.firstWithTimeoutOrNull()).isFalse()
    }

    @Test
    fun settingsSecureBooleanFlow_collectAfterValueChanged_onlyKeepLatest() = runBlocking {
        var value by context.settingsSecureBoolean(TEST_NAME)
        value = false

        val flow = context.settingsSecureBooleanFlow(TEST_NAME)
        value = true

        assertThat(flow.firstWithTimeoutOrNull()).isTrue()
    }

    @Test
    fun settingsSecureBooleanFlow_collectBeforeValueChanged_getBoth() = runBlocking {
        var value by context.settingsSecureBoolean(TEST_NAME)
        value = false

        val listDeferred = async {
            context.settingsSecureBooleanFlow(TEST_NAME).toListWithTimeout()
        }
        delay(100)
        value = true

        assertThat(listDeferred.await()).containsExactly(false, true).inOrder()
    }

    private companion object {
        const val TEST_NAME = "test_boolean_delegate"
    }
}
