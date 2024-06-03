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
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.eq

/** Tests for [SettingsProxy]. */
@RunWith(AndroidTestingRunner::class)
@SmallTest
@TestableLooper.RunWithLooper
class SettingsProxyTest : SysuiTestCase() {

    private lateinit var mSettings: SettingsProxy
    private lateinit var mContentObserver: ContentObserver

    @Before
    fun setUp() {
        mSettings = FakeSettingsProxy()
        mContentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {}
    }

    @Test
    fun registerContentObserver_inputString_success() {
        mSettings.registerContentObserver(TEST_SETTING, mContentObserver)
        verify(mSettings.getContentResolver())
            .registerContentObserver(eq(TEST_SETTING_URI), eq(false), eq(mContentObserver))
    }

    @Test
    fun registerContentObserver_inputString_notifyForDescendants_true() {
        mSettings.registerContentObserver(
            TEST_SETTING,
            notifyForDescendants = true,
            mContentObserver
        )
        verify(mSettings.getContentResolver())
            .registerContentObserver(eq(TEST_SETTING_URI), eq(true), eq(mContentObserver))
    }

    @Test
    fun registerContentObserver_inputUri_success() {
        mSettings.registerContentObserver(TEST_SETTING_URI, mContentObserver)
        verify(mSettings.getContentResolver())
            .registerContentObserver(eq(TEST_SETTING_URI), eq(false), eq(mContentObserver))
    }

    @Test
    fun registerContentObserver_inputUri_notifyForDescendants_true() {
        mSettings.registerContentObserver(
            TEST_SETTING_URI,
            notifyForDescendants = true,
            mContentObserver
        )
        verify(mSettings.getContentResolver())
            .registerContentObserver(eq(TEST_SETTING_URI), eq(true), eq(mContentObserver))
    }

    @Test
    fun unregisterContentObserver() {
        mSettings.unregisterContentObserver(mContentObserver)
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

    private class FakeSettingsProxy : SettingsProxy {

        private val mContentResolver = mock(ContentResolver::class.java)
        private val settingToValueMap: MutableMap<String, String> = mutableMapOf()

        override fun getContentResolver() = mContentResolver

        override fun getUriFor(name: String) =
            Uri.parse(StringBuilder().append("content://settings/").append(name).toString())

        override fun getString(name: String): String {
            return settingToValueMap[name] ?: ""
        }

        override fun putString(name: String, value: String): Boolean {
            settingToValueMap[name] = value
            return true
        }

        override fun putString(
            name: String,
            value: String,
            tag: String,
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
