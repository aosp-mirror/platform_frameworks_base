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

package com.android.systemui.qs.panels.data

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.UserInfo
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.panels.data.repository.QSPreferencesRepository
import com.android.systemui.qs.panels.data.repository.qsPreferencesRepository
import com.android.systemui.settings.userFileManager
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.user.data.repository.userRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class QSPreferencesRepositoryTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val underTest = with(kosmos) { qsPreferencesRepository }

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

    private fun setShowLabelsInSharedPreferences(value: Boolean) {
        getSharedPreferences().edit().putBoolean(ICON_LABELS_KEY, value).apply()
    }

    private fun getShowLabelsFromSharedPreferences(defaultValue: Boolean): Boolean {
        return getSharedPreferences().getBoolean(ICON_LABELS_KEY, defaultValue)
    }

    companion object {
        private const val ICON_LABELS_KEY = "show_icon_labels"
        private const val PRIMARY_USER_ID = 0
        private val PRIMARY_USER = UserInfo(PRIMARY_USER_ID, "user 0", UserInfo.FLAG_MAIN)
        private const val ANOTHER_USER_ID = 1
        private val ANOTHER_USER = UserInfo(ANOTHER_USER_ID, "user 1", UserInfo.FLAG_FULL)
        private val USERS = listOf(PRIMARY_USER, ANOTHER_USER)
    }
}
