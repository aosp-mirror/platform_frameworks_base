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
import android.content.pm.UserInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.settings.UserTracker
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.eq

/** Tests for [UserSettingsProxy]. */
@RunWith(AndroidTestingRunner::class)
@SmallTest
@TestableLooper.RunWithLooper
class UserSettingsProxyTest : SysuiTestCase() {

    private var mUserTracker = FakeUserTracker()
    private var mSettings: UserSettingsProxy = FakeUserSettingsProxy(mUserTracker)
    private var mContentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {}
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        mUserTracker.set(
            listOf(UserInfo(MAIN_USER_ID, "main", UserInfo.FLAG_MAIN)),
            selectedUserIndex = 0
        )
        val testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        Dispatchers.setMain(testDispatcher)
    }

    @Test
    fun registerContentObserverForUser_inputString_success() {
        mSettings.registerContentObserverForUserSync(
            TEST_SETTING,
            mContentObserver,
            mUserTracker.userId
        )
        verify(mSettings.getContentResolver())
            .registerContentObserver(
                eq(TEST_SETTING_URI),
                eq(false),
                eq(mContentObserver),
                eq(MAIN_USER_ID)
            )
    }

    @Test
    fun registerContentObserverForUserSuspend_inputString_success() =
        testScope.runTest {
            mSettings.registerContentObserverForUser(
                TEST_SETTING,
                mContentObserver,
                mUserTracker.userId
            )
            verify(mSettings.getContentResolver())
                .registerContentObserver(
                    eq(TEST_SETTING_URI),
                    eq(false),
                    eq(mContentObserver),
                    eq(MAIN_USER_ID)
                )
        }

    @Test
    fun registerContentObserverForUserAsync_inputString_success() {
        mSettings.registerContentObserverForUserAsync(
            TEST_SETTING,
            mContentObserver,
            mUserTracker.userId
        )
        testScope.launch {
            verify(mSettings.getContentResolver())
                .registerContentObserver(
                    eq(TEST_SETTING_URI),
                    eq(false),
                    eq(mContentObserver),
                    eq(MAIN_USER_ID)
                )
        }
    }

    @Test
    fun registerContentObserverForUser_inputString_notifyForDescendants_true() {
        mSettings.registerContentObserverForUserSync(
            TEST_SETTING,
            notifyForDescendants = true,
            mContentObserver,
            mUserTracker.userId
        )
        verify(mSettings.getContentResolver())
            .registerContentObserver(
                eq(TEST_SETTING_URI),
                eq(true),
                eq(mContentObserver),
                eq(MAIN_USER_ID)
            )
    }

    @Test
    fun registerContentObserverForUserSuspend_inputString_notifyForDescendants_true() =
        testScope.runTest {
            mSettings.registerContentObserverForUser(
                TEST_SETTING,
                notifyForDescendants = true,
                mContentObserver,
                mUserTracker.userId
            )
            verify(mSettings.getContentResolver())
                .registerContentObserver(
                    eq(TEST_SETTING_URI),
                    eq(
                        true,
                    ),
                    eq(mContentObserver),
                    eq(MAIN_USER_ID)
                )
        }

    @Test
    fun registerContentObserverForUserAsync_inputString_notifyForDescendants_true() {
        mSettings.registerContentObserverForUserAsync(
            TEST_SETTING,
            notifyForDescendants = true,
            mContentObserver,
            mUserTracker.userId
        )
        testScope.launch {
            verify(mSettings.getContentResolver())
                .registerContentObserver(
                    eq(TEST_SETTING_URI),
                    eq(true),
                    eq(mContentObserver),
                    eq(MAIN_USER_ID)
                )
        }
    }

    @Test
    fun registerContentObserverForUser_inputUri_success() {
        mSettings.registerContentObserverForUserSync(
            TEST_SETTING_URI,
            mContentObserver,
            mUserTracker.userId
        )
        verify(mSettings.getContentResolver())
            .registerContentObserver(
                eq(TEST_SETTING_URI),
                eq(false),
                eq(mContentObserver),
                eq(MAIN_USER_ID)
            )
    }

    @Test
    fun registerContentObserverForUserSuspend_inputUri_success() =
        testScope.runTest {
            mSettings.registerContentObserverForUser(
                TEST_SETTING_URI,
                mContentObserver,
                mUserTracker.userId
            )
            verify(mSettings.getContentResolver())
                .registerContentObserver(
                    eq(TEST_SETTING_URI),
                    eq(false),
                    eq(mContentObserver),
                    eq(MAIN_USER_ID)
                )
        }

    @Test
    fun registerContentObserverForUserAsync_inputUri_success() {
        mSettings.registerContentObserverForUserAsync(
            TEST_SETTING_URI,
            mContentObserver,
            mUserTracker.userId
        )
        testScope.launch {
            verify(mSettings.getContentResolver())
                .registerContentObserver(
                    eq(TEST_SETTING_URI),
                    eq(false),
                    eq(mContentObserver),
                    eq(MAIN_USER_ID)
                )
        }
    }

    @Test
    fun registerContentObserverForUser_inputUri_notifyForDescendants_true() {
        mSettings.registerContentObserverForUserSync(
            TEST_SETTING_URI,
            notifyForDescendants = true,
            mContentObserver,
            mUserTracker.userId
        )
        verify(mSettings.getContentResolver())
            .registerContentObserver(
                eq(TEST_SETTING_URI),
                eq(true),
                eq(mContentObserver),
                eq(MAIN_USER_ID)
            )
    }

    @Test
    fun registerContentObserverForUserSuspend_inputUri_notifyForDescendants_true() =
        testScope.runTest {
            mSettings.registerContentObserverForUser(
                TEST_SETTING_URI,
                notifyForDescendants = true,
                mContentObserver,
                mUserTracker.userId
            )
            verify(mSettings.getContentResolver())
                .registerContentObserver(
                    eq(TEST_SETTING_URI),
                    eq(
                        true,
                    ),
                    eq(mContentObserver),
                    eq(MAIN_USER_ID)
                )
        }

    @Test
    fun registerContentObserverForUserAsync_inputUri_notifyForDescendants_true() {
        mSettings.registerContentObserverForUserAsync(
            TEST_SETTING_URI,
            notifyForDescendants = true,
            mContentObserver,
            mUserTracker.userId
        )
        testScope.launch {
            verify(mSettings.getContentResolver())
                .registerContentObserver(
                    eq(TEST_SETTING_URI),
                    eq(true),
                    eq(mContentObserver),
                    eq(MAIN_USER_ID)
                )
        }
    }

    @Test
    fun registerContentObserver_inputUri_success() {
        mSettings.registerContentObserverSync(TEST_SETTING_URI, mContentObserver)
        verify(mSettings.getContentResolver())
            .registerContentObserver(eq(TEST_SETTING_URI), eq(false), eq(mContentObserver), eq(0))
    }

    @Test
    fun registerContentObserverSuspend_inputUri_success() =
        testScope.runTest {
            mSettings.registerContentObserver(TEST_SETTING_URI, mContentObserver)
            verify(mSettings.getContentResolver())
                .registerContentObserver(
                    eq(TEST_SETTING_URI),
                    eq(false),
                    eq(mContentObserver),
                    eq(0)
                )
        }

    @Test
    fun registerContentObserverAsync_inputUri_success() {
        mSettings.registerContentObserverAsync(TEST_SETTING_URI, mContentObserver)
        testScope.launch {
            verify(mSettings.getContentResolver())
                .registerContentObserver(
                    eq(TEST_SETTING_URI),
                    eq(false),
                    eq(mContentObserver),
                    eq(0)
                )
        }
    }

    @Test
    fun registerContentObserver_inputUri_notifyForDescendants_true() {
        mSettings.registerContentObserverSync(
            TEST_SETTING_URI,
            notifyForDescendants = true,
            mContentObserver
        )
        verify(mSettings.getContentResolver())
            .registerContentObserver(eq(TEST_SETTING_URI), eq(true), eq(mContentObserver), eq(0))
    }

    @Test
    fun registerContentObserverSuspend_inputUri_notifyForDescendants_true() =
        testScope.runTest {
            mSettings.registerContentObserver(TEST_SETTING_URI, mContentObserver)
            verify(mSettings.getContentResolver())
                .registerContentObserver(
                    eq(TEST_SETTING_URI),
                    eq(false),
                    eq(mContentObserver),
                    eq(0)
                )
        }

    @Test
    fun registerContentObserverAsync_inputUri_notifyForDescendants_true() {
        mSettings.registerContentObserverAsync(TEST_SETTING_URI, mContentObserver)
        testScope.launch {
            verify(mSettings.getContentResolver())
                .registerContentObserver(
                    eq(TEST_SETTING_URI),
                    eq(false),
                    eq(mContentObserver),
                    eq(0)
                )
        }
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
    fun getStringForUser_multipleUsers_validResult() {
        mSettings.putStringForUser(TEST_SETTING, "test", MAIN_USER_ID)
        mSettings.putStringForUser(TEST_SETTING, "test1", SECONDARY_USER_ID)
        assertThat(mSettings.getStringForUser(TEST_SETTING, MAIN_USER_ID)).isEqualTo("test")
        assertThat(mSettings.getStringForUser(TEST_SETTING, SECONDARY_USER_ID)).isEqualTo("test1")
    }

    @Test
    fun getInt_keyPresent_returnValidValue() {
        mSettings.putInt(TEST_SETTING, 2)
        assertThat(mSettings.getInt(TEST_SETTING)).isEqualTo(2)
    }

    @Test
    fun getInt_keyPresent_nonIntegerValue_throwException() {
        assertThrows(Settings.SettingNotFoundException::class.java) {
            mSettings.putString(TEST_SETTING, "test")
            mSettings.getInt(TEST_SETTING)
        }
    }

    @Test
    fun getInt_keyAbsent_throwException() {
        assertThrows(Settings.SettingNotFoundException::class.java) {
            mSettings.getInt(TEST_SETTING)
        }
    }

    @Test
    fun getInt_keyAbsent_returnDefaultValue() {
        assertThat(mSettings.getInt(TEST_SETTING, 5)).isEqualTo(5)
    }

    @Test
    fun getIntForUser_multipleUsers__validResult() {
        mSettings.putIntForUser(TEST_SETTING, 1, MAIN_USER_ID)
        mSettings.putIntForUser(TEST_SETTING, 2, SECONDARY_USER_ID)
        assertThat(mSettings.getIntForUser(TEST_SETTING, MAIN_USER_ID)).isEqualTo(1)
        assertThat(mSettings.getIntForUser(TEST_SETTING, SECONDARY_USER_ID)).isEqualTo(2)
    }

    @Test
    fun getBool_keyPresent_returnValidValue() {
        mSettings.putBool(TEST_SETTING, true)
        assertThat(mSettings.getBool(TEST_SETTING)).isTrue()
    }

    @Test
    fun getBool_keyPresent_nonBooleanValue_throwException() {
        assertThrows(Settings.SettingNotFoundException::class.java) {
            mSettings.putString(TEST_SETTING, "test")
            mSettings.getBool(TEST_SETTING)
        }
    }

    @Test
    fun getBool_keyAbsent_throwException() {
        assertThrows(Settings.SettingNotFoundException::class.java) {
            mSettings.getBool(TEST_SETTING)
        }
    }

    @Test
    fun getBool_keyAbsent_returnDefaultValue() {
        assertThat(mSettings.getBool(TEST_SETTING, false)).isEqualTo(false)
    }

    @Test
    fun getBoolForUser_multipleUsers__validResult() {
        mSettings.putBoolForUser(TEST_SETTING, true, MAIN_USER_ID)
        mSettings.putBoolForUser(TEST_SETTING, false, SECONDARY_USER_ID)
        assertThat(mSettings.getBoolForUser(TEST_SETTING, MAIN_USER_ID)).isEqualTo(true)
        assertThat(mSettings.getBoolForUser(TEST_SETTING, SECONDARY_USER_ID)).isEqualTo(false)
    }

    @Test
    fun getLong_keyPresent_returnValidValue() {
        mSettings.putLong(TEST_SETTING, 1L)
        assertThat(mSettings.getLong(TEST_SETTING)).isEqualTo(1L)
    }

    @Test
    fun getLong_keyPresent_nonLongValue_throwException() {
        assertThrows(Settings.SettingNotFoundException::class.java) {
            mSettings.putString(TEST_SETTING, "test")
            mSettings.getLong(TEST_SETTING)
        }
    }

    @Test
    fun getLong_keyAbsent_throwException() {
        assertThrows(Settings.SettingNotFoundException::class.java) {
            mSettings.getLong(TEST_SETTING)
        }
    }

    @Test
    fun getLong_keyAbsent_returnDefaultValue() {
        assertThat(mSettings.getLong(TEST_SETTING, 2L)).isEqualTo(2L)
    }

    @Test
    fun getLongForUser_multipleUsers__validResult() {
        mSettings.putLongForUser(TEST_SETTING, 1L, MAIN_USER_ID)
        mSettings.putLongForUser(TEST_SETTING, 2L, SECONDARY_USER_ID)
        assertThat(mSettings.getLongForUser(TEST_SETTING, MAIN_USER_ID)).isEqualTo(1L)
        assertThat(mSettings.getLongForUser(TEST_SETTING, SECONDARY_USER_ID)).isEqualTo(2L)
    }

    @Test
    fun getFloat_keyPresent_returnValidValue() {
        mSettings.putFloat(TEST_SETTING, 2.5F)
        assertThat(mSettings.getFloat(TEST_SETTING)).isEqualTo(2.5F)
    }

    @Test
    fun getFloat_keyPresent_nonFloatValue_throwException() {
        assertThrows(Settings.SettingNotFoundException::class.java) {
            mSettings.putString(TEST_SETTING, "test")
            mSettings.getFloat(TEST_SETTING)
        }
    }

    @Test
    fun getFloat_keyAbsent_throwException() {
        assertThrows(Settings.SettingNotFoundException::class.java) {
            mSettings.getFloat(TEST_SETTING)
        }
    }

    @Test
    fun getFloat_keyAbsent_returnDefaultValue() {
        assertThat(mSettings.getFloat(TEST_SETTING, 2.5F)).isEqualTo(2.5F)
    }

    @Test
    fun getFloatForUser_multipleUsers__validResult() {
        mSettings.putFloatForUser(TEST_SETTING, 1F, MAIN_USER_ID)
        mSettings.putFloatForUser(TEST_SETTING, 2F, SECONDARY_USER_ID)
        assertThat(mSettings.getFloatForUser(TEST_SETTING, MAIN_USER_ID)).isEqualTo(1F)
        assertThat(mSettings.getFloatForUser(TEST_SETTING, SECONDARY_USER_ID)).isEqualTo(2F)
    }

    /**
     * Fake implementation of [UserSettingsProxy].
     *
     * This class uses a mock of [ContentResolver] to test the [ContentObserver] registration APIs.
     */
    private class FakeUserSettingsProxy(override val userTracker: UserTracker) : UserSettingsProxy {

        private val mContentResolver = mock(ContentResolver::class.java)
        private val userIdToSettingsValueMap: MutableMap<Int, MutableMap<String, String>> =
            mutableMapOf()
        private val testDispatcher = StandardTestDispatcher()

        override fun getContentResolver() = mContentResolver

        override fun getUriFor(name: String) =
            Uri.parse(StringBuilder().append(URI_PREFIX).append(name).toString())

        override fun getBackgroundDispatcher() = testDispatcher

        override fun getStringForUser(name: String, userHandle: Int) =
            userIdToSettingsValueMap[userHandle]?.get(name) ?: ""

        override fun putString(
            name: String,
            value: String,
            overrideableByRestore: Boolean
        ): Boolean {
            userIdToSettingsValueMap[DEFAULT_USER_ID]?.put(name, value)
            return true
        }

        override fun putString(
            name: String,
            value: String,
            tag: String,
            makeDefault: Boolean
        ): Boolean {
            putStringForUser(name, value, DEFAULT_USER_ID)
            return true
        }

        override fun putStringForUser(name: String, value: String, userHandle: Int): Boolean {
            userIdToSettingsValueMap[userHandle] = mutableMapOf(Pair(name, value))
            return true
        }

        override fun putStringForUser(
            name: String,
            value: String,
            tag: String?,
            makeDefault: Boolean,
            userHandle: Int,
            overrideableByRestore: Boolean
        ): Boolean {
            userIdToSettingsValueMap[userHandle]?.set(name, value)
            return true
        }

        private companion object {
            const val DEFAULT_USER_ID = 0
            const val URI_PREFIX = "content://settings/"
        }
    }

    private companion object {
        const val MAIN_USER_ID = 10
        const val SECONDARY_USER_ID = 20
        const val TEST_SETTING = "test_setting"
        val TEST_SETTING_URI = Uri.parse("content://settings/test_setting")
    }
}
