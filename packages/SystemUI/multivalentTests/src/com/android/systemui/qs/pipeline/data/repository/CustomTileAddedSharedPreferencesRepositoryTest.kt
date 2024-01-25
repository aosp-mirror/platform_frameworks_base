/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.data.repository

import android.content.ComponentName
import android.content.SharedPreferences
import android.platform.test.annotations.EnabledOnRavenwood
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.UserFileManager
import com.android.systemui.util.FakeSharedPreferences
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
class CustomTileAddedSharedPreferencesRepositoryTest : SysuiTestCase() {

    private lateinit var underTest: CustomTileAddedSharedPrefsRepository

    @Test
    fun setTileAdded_inSharedPreferences() {
        val userId = 0
        val sharedPrefs = FakeSharedPreferences()
        val userFileManager = FakeUserFileManager(mapOf(userId to sharedPrefs))

        underTest = CustomTileAddedSharedPrefsRepository(userFileManager)

        underTest.setTileAdded(TEST_COMPONENT, userId, added = true)
        assertThat(sharedPrefs.getForComponentName(TEST_COMPONENT)).isTrue()

        underTest.setTileAdded(TEST_COMPONENT, userId, added = false)
        assertThat(sharedPrefs.getForComponentName(TEST_COMPONENT)).isFalse()
    }

    @Test
    fun setTileAdded_differentComponents() {
        val userId = 0
        val sharedPrefs = FakeSharedPreferences()
        val userFileManager = FakeUserFileManager(mapOf(userId to sharedPrefs))

        underTest = CustomTileAddedSharedPrefsRepository(userFileManager)

        underTest.setTileAdded(TEST_COMPONENT, userId, added = true)

        assertThat(sharedPrefs.getForComponentName(TEST_COMPONENT)).isTrue()
        assertThat(sharedPrefs.getForComponentName(OTHER_TEST_COMPONENT)).isFalse()
    }

    @Test
    fun setTileAdded_differentUsers() {
        val sharedPrefs0 = FakeSharedPreferences()
        val sharedPrefs1 = FakeSharedPreferences()
        val userFileManager = FakeUserFileManager(mapOf(0 to sharedPrefs0, 1 to sharedPrefs1))

        underTest = CustomTileAddedSharedPrefsRepository(userFileManager)

        underTest.setTileAdded(TEST_COMPONENT, userId = 1, added = true)

        assertThat(sharedPrefs0.getForComponentName(TEST_COMPONENT)).isFalse()
        assertThat(sharedPrefs1.getForComponentName(TEST_COMPONENT)).isTrue()
    }

    @Test
    fun isTileAdded_fromSharedPreferences() {
        val userId = 0
        val sharedPrefs = FakeSharedPreferences()
        val userFileManager = FakeUserFileManager(mapOf(userId to sharedPrefs))

        underTest = CustomTileAddedSharedPrefsRepository(userFileManager)

        assertThat(underTest.isTileAdded(TEST_COMPONENT, userId)).isFalse()

        sharedPrefs.setForComponentName(TEST_COMPONENT, true)
        assertThat(underTest.isTileAdded(TEST_COMPONENT, userId)).isTrue()

        sharedPrefs.setForComponentName(TEST_COMPONENT, false)
        assertThat(underTest.isTileAdded(TEST_COMPONENT, userId)).isFalse()
    }

    @Test
    fun isTileAdded_differentComponents() {
        val userId = 0
        val sharedPrefs = FakeSharedPreferences()
        val userFileManager = FakeUserFileManager(mapOf(userId to sharedPrefs))

        underTest = CustomTileAddedSharedPrefsRepository(userFileManager)

        sharedPrefs.setForComponentName(OTHER_TEST_COMPONENT, true)

        assertThat(underTest.isTileAdded(TEST_COMPONENT, userId)).isFalse()
        assertThat(underTest.isTileAdded(OTHER_TEST_COMPONENT, userId)).isTrue()
    }

    @Test
    fun isTileAdded_differentUsers() {
        val sharedPrefs0 = FakeSharedPreferences()
        val sharedPrefs1 = FakeSharedPreferences()
        val userFileManager = FakeUserFileManager(mapOf(0 to sharedPrefs0, 1 to sharedPrefs1))

        underTest = CustomTileAddedSharedPrefsRepository(userFileManager)

        sharedPrefs1.setForComponentName(TEST_COMPONENT, true)

        assertThat(underTest.isTileAdded(TEST_COMPONENT, userId = 0)).isFalse()
        assertThat(underTest.isTileAdded(TEST_COMPONENT, userId = 1)).isTrue()
    }

    private fun SharedPreferences.getForComponentName(componentName: ComponentName): Boolean {
        return getBoolean(componentName.flattenToString(), false)
    }

    private fun SharedPreferences.setForComponentName(
        componentName: ComponentName,
        value: Boolean
    ) {
        edit().putBoolean(componentName.flattenToString(), value).commit()
    }

    companion object {
        private val TEST_COMPONENT = ComponentName("pkg", "cls")
        private val OTHER_TEST_COMPONENT = ComponentName("pkg", "other")
    }
}

private const val FILE_NAME = "tiles_prefs"

private class FakeUserFileManager(private val sharedPrefs: Map<Int, SharedPreferences>) :
    UserFileManager {
    override fun getFile(fileName: String, userId: Int): File {
        throw UnsupportedOperationException()
    }

    override fun getSharedPreferences(fileName: String, mode: Int, userId: Int): SharedPreferences {
        if (fileName != FILE_NAME) {
            throw IllegalArgumentException("Preference files must be $FILE_NAME")
        }
        return sharedPrefs.getValue(userId)
    }
}
