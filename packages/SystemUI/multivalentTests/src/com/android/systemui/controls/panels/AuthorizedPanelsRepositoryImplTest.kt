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
import android.content.pm.UserInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.settings.FakeUserTracker
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.fakeUserTracker
import com.android.systemui.testKosmos
import com.android.systemui.util.FakeSharedPreferences
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class AuthorizedPanelsRepositoryImplTest : SysuiTestCase() {
    val kosmos = testKosmos()
    val testScope = kosmos.testScope

    private lateinit var userTracker: FakeUserTracker

    @Before
    fun setUp() {
        mContext.orCreateTestableResources.addOverride(
            R.array.config_controlsPreferredPackages,
            arrayOf<String>()
        )
        userTracker = kosmos.fakeUserTracker.apply { set(listOf(PRIMARY_USER, SECONDARY_USER), 0) }
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
        userTracker.set(listOf(SECONDARY_USER), 0)
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

    @Test
    fun observeAuthorizedPanels() =
        testScope.runTest {
            val sharedPrefs = FakeSharedPreferences()
            val fileManager = FakeUserFileManager(mapOf(0 to sharedPrefs))
            val repository = createRepository(fileManager)

            val authorizedPanels by
                collectLastValue(repository.observeAuthorizedPanels(PRIMARY_USER.userHandle))
            assertThat(authorizedPanels).isEmpty()

            repository.addAuthorizedPanels(setOf(TEST_PACKAGE))
            assertThat(authorizedPanels).containsExactly(TEST_PACKAGE)

            repository.removeAuthorizedPanels(setOf(TEST_PACKAGE))
            assertThat(authorizedPanels).isEmpty()
        }

    @Test
    fun observeAuthorizedPanelsForAnotherUser() =
        testScope.runTest {
            val fileManager =
                FakeUserFileManager(
                    mapOf(
                        0 to FakeSharedPreferences(),
                        1 to FakeSharedPreferences(),
                    )
                )
            val repository = createRepository(fileManager)

            val authorizedPanels by
                collectLastValue(repository.observeAuthorizedPanels(SECONDARY_USER.userHandle))
            assertThat(authorizedPanels).isEmpty()

            // Primary user is active, add authorized panels.
            repository.addAuthorizedPanels(setOf(TEST_PACKAGE))
            assertThat(authorizedPanels).isEmpty()

            // Make secondary user active and add authorized panels again.
            userTracker.set(listOf(PRIMARY_USER, SECONDARY_USER), 1)
            assertThat(authorizedPanels).isEmpty()
            repository.addAuthorizedPanels(setOf(TEST_PACKAGE))
            assertThat(authorizedPanels).containsExactly(TEST_PACKAGE)
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
        private val PRIMARY_USER =
            UserInfo(/* id= */ 0, /* name= */ "primary user", /* flags= */ UserInfo.FLAG_MAIN)
        private val SECONDARY_USER =
            UserInfo(/* id= */ 1, /* name= */ "secondary user", /* flags= */ 0)
    }
}
