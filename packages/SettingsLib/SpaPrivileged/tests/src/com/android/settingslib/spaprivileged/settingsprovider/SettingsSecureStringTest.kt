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
class SettingsSecureStringTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun getValue_returnCorrectValue() {
        Settings.Secure.putString(context.contentResolver, TEST_NAME, VALUE)

        val value by context.settingsSecureString(TEST_NAME)

        assertThat(value).isEqualTo(VALUE)
    }

    @Test
    fun setValue_correctValueSet() {
        var value by context.settingsSecureString(TEST_NAME)

        value = VALUE

        assertThat(Settings.Secure.getString(context.contentResolver, TEST_NAME)).isEqualTo(VALUE)
    }

    @Test
    fun settingsSecureStringFlow_valueNotChanged() = runBlocking {
        var value by context.settingsSecureString(TEST_NAME)
        value = VALUE

        val flow = context.settingsSecureStringFlow(TEST_NAME)

        assertThat(flow.firstWithTimeoutOrNull()).isEqualTo(VALUE)
    }

    @Test
    fun settingsSecureStringFlow_collectAfterValueChanged_onlyKeepLatest() = runBlocking {
        var value by context.settingsSecureString(TEST_NAME)
        value = ""

        val flow = context.settingsSecureStringFlow(TEST_NAME)
        value = VALUE

        assertThat(flow.firstWithTimeoutOrNull()).isEqualTo(VALUE)
    }

    @Test
    fun settingsSecureStringFlow_collectBeforeValueChanged_getBoth() = runBlocking {
        var value by context.settingsSecureString(TEST_NAME)
        value = ""

        val listDeferred = async { context.settingsSecureStringFlow(TEST_NAME).toListWithTimeout() }
        delay(100)
        value = VALUE

        assertThat(listDeferred.await()).containsAtLeast("", VALUE).inOrder()
    }

    private companion object {
        const val TEST_NAME = "test_string_delegate"
        const val VALUE = "value"
    }
}
