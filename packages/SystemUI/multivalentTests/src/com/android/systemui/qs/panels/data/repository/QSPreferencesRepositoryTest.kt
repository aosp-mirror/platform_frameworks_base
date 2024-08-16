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

package com.android.systemui.qs.panels.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.UserInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.settings.userFileManager
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.user.data.repository.userRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class QSPreferencesRepositoryTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val underTest = with(kosmos) { qsPreferencesRepository }

    @Test
    fun largeTilesSpecs_updatesFromSharedPreferences() =
        with(kosmos) {
            testScope.runTest {
                val latest by collectLastValue(underTest.largeTilesSpecs)
                assertThat(latest).isEqualTo(defaultLargeTilesRepository.defaultLargeTiles)

                val newSet = setOf("tileA", "tileB")
                setLargeTilesSpecsInSharedPreferences(newSet)
                assertThat(latest).isEqualTo(newSet.toTileSpecs())
            }
        }

    @Test
    fun largeTilesSpecs_updatesFromUserChange() =
        with(kosmos) {
            testScope.runTest {
                fakeUserRepository.setUserInfos(USERS)
                val latest by collectLastValue(underTest.largeTilesSpecs)

                fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
                val newSet = setOf("tileA", "tileB")
                setLargeTilesSpecsInSharedPreferences(newSet)

                fakeUserRepository.setSelectedUserInfo(ANOTHER_USER)
                setLargeTilesSpecsInSharedPreferences(emptySet())

                fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
                assertThat(latest).isEqualTo(newSet.toTileSpecs())
            }
        }

    @Test
    fun setLargeTilesSpecs_inSharedPreferences() {
        val setA = setOf("tileA", "tileB")
        underTest.setLargeTilesSpecs(setA.toTileSpecs())
        assertThat(getLargeTilesSpecsFromSharedPreferences()).isEqualTo(setA)

        val setB = setOf("tileA", "tileB")
        underTest.setLargeTilesSpecs(setB.toTileSpecs())
        assertThat(getLargeTilesSpecsFromSharedPreferences()).isEqualTo(setB)
    }

    @Test
    fun setLargeTilesSpecs_forDifferentUser() =
        with(kosmos) {
            testScope.runTest {
                fakeUserRepository.setUserInfos(USERS)

                fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
                val setA = setOf("tileA", "tileB")
                underTest.setLargeTilesSpecs(setA.toTileSpecs())
                assertThat(getLargeTilesSpecsFromSharedPreferences()).isEqualTo(setA)

                fakeUserRepository.setSelectedUserInfo(ANOTHER_USER)
                val setB = setOf("tileA", "tileB")
                underTest.setLargeTilesSpecs(setB.toTileSpecs())
                assertThat(getLargeTilesSpecsFromSharedPreferences()).isEqualTo(setB)

                fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
                assertThat(getLargeTilesSpecsFromSharedPreferences()).isEqualTo(setA)
            }
        }

    @Test
    fun showLabels_updatesFromSharedPreferences() =
        with(kosmos) {
            testScope.runTest {
                val latest by collectLastValue(underTest.showLabels)
                assertThat(latest).isFalse()

                setShowLabelsInSharedPreferences(true)
                assertThat(latest).isTrue()

                setShowLabelsInSharedPreferences(false)
                assertThat(latest).isFalse()
            }
        }

    @Test
    fun showLabels_updatesFromUserChange() =
        with(kosmos) {
            testScope.runTest {
                fakeUserRepository.setUserInfos(USERS)
                val latest by collectLastValue(underTest.showLabels)

                fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
                setShowLabelsInSharedPreferences(false)

                fakeUserRepository.setSelectedUserInfo(ANOTHER_USER)
                setShowLabelsInSharedPreferences(true)

                fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
                assertThat(latest).isFalse()
            }
        }

    @Test
    fun setShowLabels_inSharedPreferences() {
        underTest.setShowLabels(false)
        assertThat(getShowLabelsFromSharedPreferences(true)).isFalse()

        underTest.setShowLabels(true)
        assertThat(getShowLabelsFromSharedPreferences(false)).isTrue()
    }

    @Test
    fun setShowLabels_forDifferentUser() =
        with(kosmos) {
            testScope.runTest {
                fakeUserRepository.setUserInfos(USERS)

                fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
                underTest.setShowLabels(false)
                assertThat(getShowLabelsFromSharedPreferences(true)).isFalse()

                fakeUserRepository.setSelectedUserInfo(ANOTHER_USER)
                underTest.setShowLabels(true)
                assertThat(getShowLabelsFromSharedPreferences(false)).isTrue()

                fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
                assertThat(getShowLabelsFromSharedPreferences(true)).isFalse()
            }
        }

    private fun getSharedPreferences(): SharedPreferences =
        with(kosmos) {
            return userFileManager.getSharedPreferences(
                QSPreferencesRepository.FILE_NAME,
                Context.MODE_PRIVATE,
                userRepository.getSelectedUserInfo().id,
            )
        }

    private fun setLargeTilesSpecsInSharedPreferences(specs: Set<String>) {
        getSharedPreferences().edit().putStringSet(LARGE_TILES_SPECS_KEY, specs).apply()
    }

    private fun getLargeTilesSpecsFromSharedPreferences(): Set<String> {
        return getSharedPreferences().getStringSet(LARGE_TILES_SPECS_KEY, emptySet())!!
    }

    private fun setShowLabelsInSharedPreferences(value: Boolean) {
        getSharedPreferences().edit().putBoolean(ICON_LABELS_KEY, value).apply()
    }

    private fun getShowLabelsFromSharedPreferences(defaultValue: Boolean): Boolean {
        return getSharedPreferences().getBoolean(ICON_LABELS_KEY, defaultValue)
    }

    private fun Set<String>.toTileSpecs(): Set<TileSpec> {
        return map { TileSpec.create(it) }.toSet()
    }

    companion object {
        private const val ICON_LABELS_KEY = "show_icon_labels"
        private const val LARGE_TILES_SPECS_KEY = "large_tiles_specs"
        private const val PRIMARY_USER_ID = 0
        private val PRIMARY_USER = UserInfo(PRIMARY_USER_ID, "user 0", UserInfo.FLAG_MAIN)
        private const val ANOTHER_USER_ID = 1
        private val ANOTHER_USER = UserInfo(ANOTHER_USER_ID, "user 1", UserInfo.FLAG_FULL)
        private val USERS = listOf(PRIMARY_USER, ANOTHER_USER)
    }
}
