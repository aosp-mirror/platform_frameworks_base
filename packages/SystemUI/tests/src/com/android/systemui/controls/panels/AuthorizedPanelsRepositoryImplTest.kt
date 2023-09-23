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
 *
 */

package com.android.systemui.controls.panels

import android.content.SharedPreferences
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.FakeSharedPreferences
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
class AuthorizedPanelsRepositoryImplTest : SysuiTestCase() {

    @Mock private lateinit var userTracker: UserTracker

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        mContext.orCreateTestableResources.addOverride(
            R.array.config_controlsPreferredPackages,
            arrayOf<String>()
        )
        whenever(userTracker.userId).thenReturn(0)
    }

    @Test
    fun testPreApprovedPackagesAreSeededIfNoSavedPreferences() {
        mContext.orCreateTestableResources.addOverride(
            R.array.config_controlsPreferredPackages,
            arrayOf(TEST_PACKAGE)
        )
        val sharedPrefs = FakeSharedPreferences()
        val fileManager = FakeUserFileManager(mapOf(0 to sharedPrefs))
        val repository = createRepository(fileManager)

        assertThat(repository.getAuthorizedPanels()).containsExactly(TEST_PACKAGE)
        assertThat(sharedPrefs.getStringSet(KEY, null)).containsExactly(TEST_PACKAGE)
    }

    @Test
    fun testPreApprovedPackagesNotSeededIfEmptySavedPreferences() {
        mContext.orCreateTestableResources.addOverride(
            R.array.config_controlsPreferredPackages,
            arrayOf(TEST_PACKAGE)
        )
        val sharedPrefs = FakeSharedPreferences()
        sharedPrefs.edit().putStringSet(KEY, emptySet()).apply()
        val fileManager = FakeUserFileManager(mapOf(0 to sharedPrefs))
        createRepository(fileManager)

        assertThat(sharedPrefs.getStringSet(KEY, null)).isEmpty()
    }

    @Test
    fun testPreApprovedPackagesOnlySetForUserThatDoesntHaveThem() {
        mContext.orCreateTestableResources.addOverride(
            R.array.config_controlsPreferredPackages,
            arrayOf(TEST_PACKAGE)
        )
        val sharedPrefs_0 = FakeSharedPreferences()
        val sharedPrefs_1 = FakeSharedPreferences()
        sharedPrefs_1.edit().putStringSet(KEY, emptySet()).apply()
        val fileManager = FakeUserFileManager(mapOf(0 to sharedPrefs_0, 1 to sharedPrefs_1))
        val repository = createRepository(fileManager)

        assertThat(repository.getAuthorizedPanels()).containsExactly(TEST_PACKAGE)
        whenever(userTracker.userId).thenReturn(1)
        assertThat(repository.getAuthorizedPanels()).isEmpty()
    }

    @Test
    fun testGetAuthorizedPackages() {
        val sharedPrefs = FakeSharedPreferences()
        sharedPrefs.edit().putStringSet(KEY, mutableSetOf(TEST_PACKAGE)).apply()
        val fileManager = FakeUserFileManager(mapOf(0 to sharedPrefs))

        val repository = createRepository(fileManager)
        assertThat(repository.getAuthorizedPanels()).containsExactly(TEST_PACKAGE)
    }

    @Test
    fun testSetAuthorizedPackage() {
        val sharedPrefs = FakeSharedPreferences()
        val fileManager = FakeUserFileManager(mapOf(0 to sharedPrefs))

        val repository = createRepository(fileManager)
        repository.addAuthorizedPanels(setOf(TEST_PACKAGE))
        assertThat(sharedPrefs.getStringSet(KEY, null)).containsExactly(TEST_PACKAGE)
    }

    @Test
    fun testRemoveAuthorizedPackageRemovesIt() {
        val sharedPrefs = FakeSharedPreferences()
        val fileManager = FakeUserFileManager(mapOf(0 to sharedPrefs))
        val repository = createRepository(fileManager)
        repository.addAuthorizedPanels(setOf(TEST_PACKAGE))

        repository.removeAuthorizedPanels(setOf(TEST_PACKAGE))

        assertThat(sharedPrefs.getStringSet(KEY, null)).isEmpty()
    }

    private fun createRepository(userFileManager: UserFileManager): AuthorizedPanelsRepositoryImpl {
        return AuthorizedPanelsRepositoryImpl(mContext, userFileManager, userTracker)
    }

    private class FakeUserFileManager(private val sharedPrefs: Map<Int, SharedPreferences>) :
        UserFileManager {
        override fun getFile(fileName: String, userId: Int): File {
            throw UnsupportedOperationException()
        }

        override fun getSharedPreferences(
            fileName: String,
            mode: Int,
            userId: Int
        ): SharedPreferences {
            if (fileName != FILE_NAME) {
                throw IllegalArgumentException("Preference files must be $FILE_NAME")
            }
            return sharedPrefs.getValue(userId)
        }
    }

    companion object {
        private const val FILE_NAME = "controls_prefs"
        private const val KEY = "authorized_panels"
        private const val TEST_PACKAGE = "package"
    }
}
