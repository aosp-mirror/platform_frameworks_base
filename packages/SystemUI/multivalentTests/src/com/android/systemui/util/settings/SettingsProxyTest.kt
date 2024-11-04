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

package com.android.systemui.util.settings

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings.SettingNotFoundException
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.eq

/** Tests for [SettingsProxy]. */
@RunWith(AndroidJUnit4::class)
@SmallTest
@TestableLooper.RunWithLooper
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsProxyTest : SysuiTestCase() {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var mSettings: SettingsProxy
    private lateinit var mContentObserver: ContentObserver
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        testScope = TestScope(testDispatcher)
        mSettings = FakeSettingsProxy(testDispatcher)
        mContentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {}
    }

    @Test
    fun registerContentObserver_inputString_success() =
        testScope.runTest {
            mSettings.registerContentObserverSync(TEST_SETTING, mContentObserver)
            verify(mSettings.getContentResolver())
                .registerContentObserver(eq(TEST_SETTING_URI), eq(false), eq(mContentObserver))
        }

    @Test
    fun registerContentObserverSuspend_inputString_success() =
        testScope.runTest {
            mSettings.registerContentObserver(TEST_SETTING, mContentObserver)
            verify(mSettings.getContentResolver())
                .registerContentObserver(eq(TEST_SETTING_URI), eq(false), eq(mContentObserver))
        }

    @Test
    fun registerContentObserverAsync_inputString_success() =
        testScope.runTest {
            mSettings.registerContentObserverAsync(TEST_SETTING, mContentObserver)
            testScope.advanceUntilIdle()
            verify(mSettings.getContentResolver())
                .registerContentObserver(eq(TEST_SETTING_URI), eq(false), eq(mContentObserver))
        }

    @Test
    fun registerContentObserver_inputString_notifyForDescendants_true() =
        testScope.runTest {
            mSettings.registerContentObserverSync(
                TEST_SETTING,
                notifyForDescendants = true,
                mContentObserver
            )
            verify(mSettings.getContentResolver())
                .registerContentObserver(eq(TEST_SETTING_URI), eq(true), eq(mContentObserver))
        }

    @Test
    fun registerContentObserverSuspend_inputString_notifyForDescendants_true() =
        testScope.runTest {
            mSettings.registerContentObserver(
                TEST_SETTING,
                notifyForDescendants = true,
                mContentObserver
            )
            verify(mSettings.getContentResolver())
                .registerContentObserver(eq(TEST_SETTING_URI), eq(true), eq(mContentObserver))
        }

    @Test
    fun registerContentObserverAsync_inputString_notifyForDescendants_true() =
        testScope.runTest {
            mSettings.registerContentObserverAsync(
                TEST_SETTING,
                notifyForDescendants = true,
                mContentObserver
            )
            testScope.advanceUntilIdle()
            verify(mSettings.getContentResolver())
                .registerContentObserver(eq(TEST_SETTING_URI), eq(true), eq(mContentObserver))
        }

    @Test
    fun registerContentObserver_inputUri_success() =
        testScope.runTest {
            mSettings.registerContentObserverSync(TEST_SETTING_URI, mContentObserver)
            verify(mSettings.getContentResolver())
                .registerContentObserver(eq(TEST_SETTING_URI), eq(false), eq(mContentObserver))
        }

    @Test
    fun registerContentObserverSuspend_inputUri_success() =
        testScope.runTest {
            mSettings.registerContentObserver(TEST_SETTING_URI, mContentObserver)
            verify(mSettings.getContentResolver())
                .registerContentObserver(eq(TEST_SETTING_URI), eq(false), eq(mContentObserver))
        }

    @Test
    fun registerContentObserverAsync_inputUri_success() =
        testScope.runTest {
            mSettings.registerContentObserverAsync(TEST_SETTING_URI, mContentObserver)
            testScope.advanceUntilIdle()
            verify(mSettings.getContentResolver())
                .registerContentObserver(eq(TEST_SETTING_URI), eq(false), eq(mContentObserver))
        }

    @Test
    fun registerContentObserver_inputUri_notifyForDescendants_true() =
        testScope.runTest {
            mSettings.registerContentObserverSync(
                TEST_SETTING_URI,
                notifyForDescendants = true,
                mContentObserver
            )
            verify(mSettings.getContentResolver())
                .registerContentObserver(eq(TEST_SETTING_URI), eq(true), eq(mContentObserver))
        }

    @Test
    fun registerContentObserverSuspend_inputUri_notifyForDescendants_true() =
        testScope.runTest {
            mSettings.registerContentObserver(
                TEST_SETTING_URI,
                notifyForDescendants = true,
                mContentObserver
            )
            verify(mSettings.getContentResolver())
                .registerContentObserver(eq(TEST_SETTING_URI), eq(true), eq(mContentObserver))
        }

    @Test
    fun registerContentObserverAsync_inputUri_notifyForDescendants_true() =
        testScope.runTest {
            mSettings.registerContentObserverAsync(
                TEST_SETTING_URI,
                notifyForDescendants = true,
                mContentObserver
            )
            testScope.advanceUntilIdle()
            verify(mSettings.getContentResolver())
                .registerContentObserver(eq(TEST_SETTING_URI), eq(true), eq(mContentObserver))
        }

    @Test
    fun registerContentObserverAsync_registeredLambdaPassed_callsCallback() =
        testScope.runTest {
            verifyRegisteredCallbackForRegistration {
                mSettings.registerContentObserverAsync(TEST_SETTING, mContentObserver, it)
            }
            verifyRegisteredCallbackForRegistration {
                mSettings.registerContentObserverAsync(TEST_SETTING_URI, mContentObserver, it)
            }
            verifyRegisteredCallbackForRegistration {
                mSettings.registerContentObserverAsync(TEST_SETTING, false, mContentObserver, it)
            }
            verifyRegisteredCallbackForRegistration {
                mSettings.registerContentObserverAsync(
                    TEST_SETTING_URI,
                    false,
                    mContentObserver,
                    it
                )
            }
        }

    private fun verifyRegisteredCallbackForRegistration(
        call: (registeredRunnable: Runnable) -> Unit
    ) {
        var callbackCalled = false
        val runnable = { callbackCalled = true }
        call(runnable)
        testScope.advanceUntilIdle()
        assertThat(callbackCalled).isTrue()
    }

    @Test
    fun unregisterContentObserverSync() =
        testScope.runTest {
            mSettings.unregisterContentObserverSync(mContentObserver)
            verify(mSettings.getContentResolver()).unregisterContentObserver(eq(mContentObserver))
        }

    @Test
    fun unregisterContentObserverSuspend_inputString_success() =
        testScope.runTest {
            mSettings.unregisterContentObserver(mContentObserver)
            verify(mSettings.getContentResolver()).unregisterContentObserver(eq(mContentObserver))
        }

    @Test
    fun unregisterContentObserverAsync_inputString_success() =
        testScope.runTest {
            mSettings.unregisterContentObserverAsync(mContentObserver)
            testScope.advanceUntilIdle()
            verify(mSettings.getContentResolver()).unregisterContentObserver(eq(mContentObserver))
        }

    @Test
    fun getString_keyPresent_returnValidValue() {
        mSettings.putString(TEST_SETTING, "test")
        assertThat(mSettings.getString(TEST_SETTING)).isEqualTo("test")
    }

    @Test
    fun getString_keyAbsent_returnEmptyValue() {
        assertThat(mSettings.getString(TEST_SETTING)).isEmpty()
    }

    @Test
    fun getInt_keyPresent_returnValidValue() {
        mSettings.putInt(TEST_SETTING, 2)
        assertThat(mSettings.getInt(TEST_SETTING)).isEqualTo(2)
    }

    @Test
    fun getInt_keyPresent_nonIntegerValue_throwException() {
        assertThrows(SettingNotFoundException::class.java) {
            mSettings.putString(TEST_SETTING, "test")
            mSettings.getInt(TEST_SETTING)
        }
    }

    @Test
    fun getInt_keyAbsent_throwException() {
        assertThrows(SettingNotFoundException::class.java) { mSettings.getInt(TEST_SETTING) }
    }

    @Test
    fun getInt_keyAbsent_returnDefaultValue() {
        assertThat(mSettings.getInt(TEST_SETTING, 5)).isEqualTo(5)
    }

    @Test
    fun getInt_keyMalformed_returnDefaultValue() {
        mSettings.putString(TEST_SETTING, "nan")
        assertThat(mSettings.getInt(TEST_SETTING, 5)).isEqualTo(5)
    }

    @Test
    fun getInt_keyMalformed_throwException() {
        mSettings.putString(TEST_SETTING, "nan")
        assertThrows(SettingNotFoundException::class.java) { mSettings.getInt(TEST_SETTING) }
    }

    @Test
    fun getBool_keyPresent_returnValidValue() {
        mSettings.putBool(TEST_SETTING, true)
        assertThat(mSettings.getBool(TEST_SETTING)).isTrue()
    }

    @Test
    fun getBool_keyPresent_nonBooleanValue_throwException() {
        assertThrows(SettingNotFoundException::class.java) {
            mSettings.putString(TEST_SETTING, "test")
            mSettings.getBool(TEST_SETTING)
        }
    }

    @Test
    fun getBool_keyAbsent_throwException() {
        assertThrows(SettingNotFoundException::class.java) { mSettings.getBool(TEST_SETTING) }
    }

    @Test
    fun getBool_keyAbsent_returnDefaultValue() {
        assertThat(mSettings.getBool(TEST_SETTING, false)).isEqualTo(false)
    }

    @Test
    fun getLong_keyPresent_returnValidValue() {
        mSettings.putLong(TEST_SETTING, 1L)
        assertThat(mSettings.getLong(TEST_SETTING)).isEqualTo(1L)
    }

    @Test
    fun getLong_keyPresent_nonLongValue_throwException() {
        assertThrows(SettingNotFoundException::class.java) {
            mSettings.putString(TEST_SETTING, "test")
            mSettings.getLong(TEST_SETTING)
        }
    }

    @Test
    fun getLong_keyAbsent_throwException() {
        assertThrows(SettingNotFoundException::class.java) { mSettings.getLong(TEST_SETTING) }
    }

    @Test
    fun getLong_keyAbsent_returnDefaultValue() {
        assertThat(mSettings.getLong(TEST_SETTING, 2L)).isEqualTo(2L)
    }

    @Test
    fun getLong_keyMalformed_throwException() {
        mSettings.putString(TEST_SETTING, "nan")
        assertThrows(SettingNotFoundException::class.java) { mSettings.getLong(TEST_SETTING) }
    }

    @Test
    fun getLong_keyMalformed_returnDefaultValue() {
        mSettings.putString(TEST_SETTING, "nan")
        assertThat(mSettings.getLong(TEST_SETTING, 2L)).isEqualTo(2L)
    }

    @Test
    fun getFloat_keyPresent_returnValidValue() {
        mSettings.putFloat(TEST_SETTING, 2.5F)
        assertThat(mSettings.getFloat(TEST_SETTING)).isEqualTo(2.5F)
    }

    @Test
    fun getFloat_keyPresent_nonFloatValue_throwException() {
        assertThrows(SettingNotFoundException::class.java) {
            mSettings.putString(TEST_SETTING, "test")
            mSettings.getFloat(TEST_SETTING)
        }
    }

    @Test
    fun getFloat_keyAbsent_throwException() {
        assertThrows(SettingNotFoundException::class.java) { mSettings.getFloat(TEST_SETTING) }
    }

    @Test
    fun getFloat_keyAbsent_returnDefaultValue() {
        assertThat(mSettings.getFloat(TEST_SETTING, 2.5F)).isEqualTo(2.5F)
    }

    @Test
    fun getFloat_keyMalformed_throwException() {
        mSettings.putString(TEST_SETTING, "nan")
        assertThrows(SettingNotFoundException::class.java) { mSettings.getFloat(TEST_SETTING) }
    }

    @Test
    fun getFloat_keyMalformed_returnDefaultValue() {
        mSettings.putString(TEST_SETTING, "nan")
        assertThat(mSettings.getFloat(TEST_SETTING, 2.5F)).isEqualTo(2.5F)
    }

    private class FakeSettingsProxy(val testDispatcher: CoroutineDispatcher) : SettingsProxy {

        private val mContentResolver = mock(ContentResolver::class.java)
        private val settingToValueMap: MutableMap<String, String?> = mutableMapOf()

        override fun getContentResolver() = mContentResolver

        override val backgroundDispatcher: CoroutineDispatcher
            get() = testDispatcher

        override fun getUriFor(name: String) =
            Uri.parse(StringBuilder().append("content://settings/").append(name).toString())

        override fun getString(name: String): String {
            return settingToValueMap[name] ?: ""
        }

        override fun putString(name: String, value: String?): Boolean {
            settingToValueMap[name] = value
            return true
        }

        override fun putString(
            name: String,
            value: String?,
            tag: String?,
            makeDefault: Boolean
        ): Boolean {
            settingToValueMap[name] = value
            return true
        }
    }

    companion object {
        private const val TEST_SETTING = "test_setting"
        private val TEST_SETTING_URI = Uri.parse("content://settings/test_setting")
    }
}
