/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.util

import android.content.SharedPreferences
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.eq
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyString
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class FakeSharedPreferencesTest : SysuiTestCase() {

    @Mock
    private lateinit var listener: SharedPreferences.OnSharedPreferenceChangeListener

    private lateinit var sharedPreferences: SharedPreferences

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        sharedPreferences = FakeSharedPreferences()
    }

    @Test
    fun testGetString_default() {
        val default = "default"
        val result = sharedPreferences.getString("key", default)
        assertThat(result).isEqualTo(default)
    }

    @Test
    fun testGetStringSet_default() {
        val default = setOf("one", "two")
        val result = sharedPreferences.getStringSet("key", default)
        assertThat(result).isEqualTo(default)
    }

    @Test
    fun testGetInt_default() {
        val default = 10
        val result = sharedPreferences.getInt("key", default)
        assertThat(result).isEqualTo(default)
    }

    @Test
    fun testGetLong_default() {
        val default = 11L
        val result = sharedPreferences.getLong("key", default)
        assertThat(result).isEqualTo(default)
    }

    @Test
    fun testGetFloat_default() {
        val default = 1.3f
        val result = sharedPreferences.getFloat("key", default)
        assertThat(result).isEqualTo(default)
    }

    @Test
    fun testGetBoolean_default() {
        val default = true
        val result = sharedPreferences.getBoolean("key", default)
        assertThat(result).isEqualTo(default)
    }

    @Test
    fun testPutValuesAndRetrieve() {
        val editor = sharedPreferences.edit()
        val data = listOf<Data<*>>(
            Data(
                "keyString",
                "value",
                SharedPreferences.Editor::putString,
                { getString(it, "") }
            ),
            Data(
                "keyStringSet",
                setOf("one", "two"),
                SharedPreferences.Editor::putStringSet,
                { getStringSet(it, emptySet()) }
            ),
            Data("keyInt", 10, SharedPreferences.Editor::putInt, { getInt(it, 0) }),
            Data("keyLong", 11L, SharedPreferences.Editor::putLong, { getLong(it, 0L) }),
            Data(
                "keyFloat",
                1.3f,
                SharedPreferences.Editor::putFloat,
                { getFloat(it, 0f) }
            ),
            Data(
                "keyBoolean",
                true,
                SharedPreferences.Editor::putBoolean,
                { getBoolean(it, false) }
            )
        )

        data.fold(editor) { ed, d ->
            d.set(ed)
        }
        editor.commit()

        data.forEach {
            assertThat(it.get(sharedPreferences)).isEqualTo(it.value)
        }
    }

    @Test
    fun testContains() {
        sharedPreferences.edit().putInt("key", 10).commit()

        assertThat(sharedPreferences.contains("key")).isTrue()
        assertThat(sharedPreferences.contains("other")).isFalse()
    }

    @Test
    fun testOverwrite() {
        sharedPreferences.edit().putInt("key", 10).commit()
        sharedPreferences.edit().putInt("key", 11).commit()

        assertThat(sharedPreferences.getInt("key", 0)).isEqualTo(11)
    }

    @Test
    fun testDeleteString() {
        sharedPreferences.edit().putString("key", "value").commit()
        sharedPreferences.edit().putString("key", null).commit()

        assertThat(sharedPreferences.contains("key")).isFalse()
    }

    @Test
    fun testDeleteAndReplaceString() {
        sharedPreferences.edit().putString("key", "value").commit()
        sharedPreferences.edit().putString("key", "other").putString("key", null).commit()

        assertThat(sharedPreferences.getString("key", "")).isEqualTo("other")
    }

    @Test
    fun testDeleteStringSet() {
        sharedPreferences.edit().putStringSet("key", setOf("one")).commit()
        sharedPreferences.edit().putStringSet("key", setOf("two")).commit()

        assertThat(sharedPreferences.getStringSet("key", emptySet())).isEqualTo(setOf("two"))
    }

    @Test
    fun testClear() {
        sharedPreferences.edit().putInt("keyInt", 1).putString("keyString", "a").commit()
        sharedPreferences.edit().clear().commit()

        assertThat(sharedPreferences.contains("keyInt")).isFalse()
        assertThat(sharedPreferences.contains("keyString")).isFalse()
    }

    @Test
    fun testClearAndWrite() {
        sharedPreferences.edit().putInt("key", 10).commit()
        sharedPreferences.edit().putInt("key", 11).clear().commit()

        assertThat(sharedPreferences.getInt("key", 0)).isEqualTo(11)
    }

    @Test
    fun testListenerNotifiedOnChanges() {
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)

        sharedPreferences.edit().putInt("keyInt", 10).putString("keyString", "value").commit()

        verify(listener).onSharedPreferenceChanged(sharedPreferences, "keyInt")
        verify(listener).onSharedPreferenceChanged(sharedPreferences, "keyString")
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testListenerNotifiedOnClear() {
        sharedPreferences.edit().putInt("keyInt", 10).commit()
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)

        sharedPreferences.edit().clear().commit()

        verify(listener).onSharedPreferenceChanged(sharedPreferences, null)
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testListenerNotifiedOnRemoval() {
        sharedPreferences.edit()
            .putString("keyString", "a")
            .putStringSet("keySet", setOf("a"))
            .commit()

        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        sharedPreferences.edit().putString("keyString", null).putStringSet("keySet", null).commit()

        verify(listener).onSharedPreferenceChanged(sharedPreferences, "keyString")
        verify(listener).onSharedPreferenceChanged(sharedPreferences, "keySet")
        verifyNoMoreInteractions(listener)
    }

    @Test
    fun testListenerUnregistered() {
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
        sharedPreferences.edit().putInt("key", 10).commit()

        verify(listener, never()).onSharedPreferenceChanged(eq(sharedPreferences), anyString())
    }

    @Test
    fun testSharedPreferencesOnlyModifiedOnCommit() {
        sharedPreferences.edit().putInt("key", 10)

        assertThat(sharedPreferences.contains("key")).isFalse()
    }

    private data class Data<T>(
        val key: String,
        val value: T,
        private val setter: SharedPreferences.Editor.(String, T) -> SharedPreferences.Editor,
        private val getter: SharedPreferences.(String) -> T
    ) {
        fun set(editor: SharedPreferences.Editor): SharedPreferences.Editor {
            return editor.setter(key, value)
        }

        fun get(sharedPreferences: SharedPreferences): T {
            return sharedPreferences.getter(key)
        }
    }
}
