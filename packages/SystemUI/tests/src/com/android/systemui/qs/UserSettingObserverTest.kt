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
import android.platform.test.flag.junit.FlagsParameterization
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.settings.fakeSettings
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.fail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

private typealias Callback = (Int, Boolean) -> Unit

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
@TestableLooper.RunWithLooper
class UserSettingObserverTest(flags: FlagsParameterization) : SysuiTestCase() {

    companion object {
        private const val TEST_SETTING = "setting"
        private const val USER = 0
        private const val OTHER_USER = 1
        private const val DEFAULT_VALUE = 1
        private val FAIL_CALLBACK: Callback = { _, _ -> fail("Callback should not be called") }

        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf(
                Flags.FLAG_QS_REGISTER_SETTING_OBSERVER_ON_BG_THREAD
            )
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope

    private lateinit var testableLooper: TestableLooper
    private lateinit var setting: UserSettingObserver
    private lateinit var secureSettings: SecureSettings

    private lateinit var callback: Callback

    @Before
    fun setUp() {
        testableLooper = TestableLooper.get(this)
        secureSettings = kosmos.fakeSettings

        setting =
            object :
                UserSettingObserver(
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
        setListening(false)
    }

    @Test
    fun testNotListeningByDefault() =
        testScope.runTest {
            callback = FAIL_CALLBACK

            assertThat(setting.isListening).isFalse()
            secureSettings.putIntForUser(TEST_SETTING, 2, USER)
            testableLooper.processAllMessages()
        }

    @Test
    fun testChangedWhenListeningCallsCallback() =
        testScope.runTest {
            var changed = false
            callback = { _, _ -> changed = true }

            setListening(true)
            secureSettings.putIntForUser(TEST_SETTING, 2, USER)
            testableLooper.processAllMessages()

            assertThat(changed).isTrue()
        }

    @Test
    fun testListensToCorrectSetting() =
        testScope.runTest {
            callback = FAIL_CALLBACK

            setListening(true)
            secureSettings.putIntForUser("other", 2, USER)
            testableLooper.processAllMessages()
        }

    @Test
    fun testGetCorrectValue() =
        testScope.runTest {
            secureSettings.putIntForUser(TEST_SETTING, 2, USER)
            assertThat(setting.value).isEqualTo(2)

            secureSettings.putIntForUser(TEST_SETTING, 4, USER)
            assertThat(setting.value).isEqualTo(4)
        }

    @Test
    fun testSetValue() =
        testScope.runTest {
            setting.value = 5
            assertThat(secureSettings.getIntForUser(TEST_SETTING, USER)).isEqualTo(5)
        }

    @Test
    fun testChangeUser() =
        testScope.runTest {
            setListening(true)
            setting.setUserId(OTHER_USER)

            setListening(true)
            assertThat(setting.currentUser).isEqualTo(OTHER_USER)
        }

    @Test
    fun testDoesntListenInOtherUsers() =
        testScope.runTest {
            callback = FAIL_CALLBACK
            setListening(true)

            secureSettings.putIntForUser(TEST_SETTING, 3, OTHER_USER)
            testableLooper.processAllMessages()
        }

    @Test
    fun testListensToCorrectUserAfterChange() =
        testScope.runTest {
            var changed = false
            callback = { _, _ -> changed = true }

            setListening(true)
            setting.setUserId(OTHER_USER)
            testScope.runCurrent()
            secureSettings.putIntForUser(TEST_SETTING, 2, OTHER_USER)
            testableLooper.processAllMessages()

            assertThat(changed).isTrue()
        }

    @Test
    fun testDefaultValue() =
        testScope.runTest {
            // Check default value before listening
            assertThat(setting.value).isEqualTo(DEFAULT_VALUE)

            // Check default value if setting is not set
            setListening(true)
            assertThat(setting.value).isEqualTo(DEFAULT_VALUE)
        }

    fun setListening(listening: Boolean) {
        setting.isListening = listening
        testScope.runCurrent()
    }
}
