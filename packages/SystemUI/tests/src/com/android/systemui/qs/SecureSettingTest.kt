/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs

import android.os.Handler
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.settings.FakeSettings
import com.android.systemui.util.settings.SecureSettings
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private typealias Callback = (Int, Boolean) -> Unit

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class SecureSettingTest : SysuiTestCase() {

    companion object {
        private const val TEST_SETTING = "setting"
        private const val USER = 0
        private const val OTHER_USER = 1
        private const val DEFAULT_VALUE = 1
        private val FAIL_CALLBACK: Callback = { _, _ -> fail("Callback should not be called") }
    }

    private lateinit var testableLooper: TestableLooper
    private lateinit var setting: SecureSetting
    private lateinit var secureSettings: SecureSettings

    private lateinit var callback: Callback

    @Before
    fun setUp() {
        testableLooper = TestableLooper.get(this)
        secureSettings = FakeSettings()

        setting = object : SecureSetting(
                secureSettings,
                Handler(testableLooper.looper),
                TEST_SETTING,
                USER,
                DEFAULT_VALUE
        ) {
            override fun handleValueChanged(value: Int, observedChange: Boolean) {
                callback(value, observedChange)
            }
        }

        // Default empty callback
        callback = { _, _ -> Unit }
    }

    @After
    fun tearDown() {
        setting.isListening = false
    }

    @Test
    fun testNotListeningByDefault() {
        callback = FAIL_CALLBACK

        assertThat(setting.isListening).isFalse()
        secureSettings.putIntForUser(TEST_SETTING, 2, USER)
        testableLooper.processAllMessages()
    }

    @Test
    fun testChangedWhenListeningCallsCallback() {
        var changed = false
        callback = { _, _ -> changed = true }

        setting.isListening = true
        secureSettings.putIntForUser(TEST_SETTING, 2, USER)
        testableLooper.processAllMessages()

        assertThat(changed).isTrue()
    }

    @Test
    fun testListensToCorrectSetting() {
        callback = FAIL_CALLBACK

        setting.isListening = true
        secureSettings.putIntForUser("other", 2, USER)
        testableLooper.processAllMessages()
    }

    @Test
    fun testGetCorrectValue() {
        secureSettings.putIntForUser(TEST_SETTING, 2, USER)
        assertThat(setting.value).isEqualTo(2)

        secureSettings.putIntForUser(TEST_SETTING, 4, USER)
        assertThat(setting.value).isEqualTo(4)
    }

    @Test
    fun testSetValue() {
        setting.value = 5
        assertThat(secureSettings.getIntForUser(TEST_SETTING, USER)).isEqualTo(5)
    }

    @Test
    fun testChangeUser() {
        setting.isListening = true
        setting.setUserId(OTHER_USER)

        setting.isListening = true
        assertThat(setting.currentUser).isEqualTo(OTHER_USER)
    }

    @Test
    fun testDoesntListenInOtherUsers() {
        callback = FAIL_CALLBACK
        setting.isListening = true

        secureSettings.putIntForUser(TEST_SETTING, 3, OTHER_USER)
        testableLooper.processAllMessages()
    }

    @Test
    fun testListensToCorrectUserAfterChange() {
        var changed = false
        callback = { _, _ -> changed = true }

        setting.isListening = true
        setting.setUserId(OTHER_USER)
        secureSettings.putIntForUser(TEST_SETTING, 2, OTHER_USER)
        testableLooper.processAllMessages()

        assertThat(changed).isTrue()
    }

    @Test
    fun testDefaultValue() {
        // Check default value before listening
        assertThat(setting.value).isEqualTo(DEFAULT_VALUE)

        // Check default value if setting is not set
        setting.isListening = true
        assertThat(setting.value).isEqualTo(DEFAULT_VALUE)
    }
}