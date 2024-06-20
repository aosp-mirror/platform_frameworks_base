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

package com.android.systemui.qs.panels.domain.interactor

import android.content.pm.UserInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class IconLabelVisibilityInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val underTest = with(kosmos) { iconLabelVisibilityInteractor }

    @Before
    fun setUp() {
        with(kosmos) { fakeUserRepository.setUserInfos(USERS) }
    }

    @Test
    fun changingShowLabels_receivesCorrectShowLabels() =
        with(kosmos) {
            testScope.runTest {
                val showLabels by collectLastValue(underTest.showLabels)

                underTest.setShowLabels(false)
                runCurrent()
                assertThat(showLabels).isFalse()

                underTest.setShowLabels(true)
                runCurrent()
                assertThat(showLabels).isTrue()
            }
        }

    @Test
    fun changingUser_receivesCorrectShowLabels() =
        with(kosmos) {
            testScope.runTest {
                val showLabels by collectLastValue(underTest.showLabels)

                fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
                underTest.setShowLabels(false)
                runCurrent()
                assertThat(showLabels).isFalse()

                fakeUserRepository.setSelectedUserInfo(ANOTHER_USER)
                underTest.setShowLabels(true)
                runCurrent()
                assertThat(showLabels).isTrue()

                fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
                runCurrent()
                assertThat(showLabels).isFalse()
            }
        }

    companion object {
        private val PRIMARY_USER = UserInfo(0, "user 0", UserInfo.FLAG_MAIN)
        private val ANOTHER_USER = UserInfo(1, "user 1", UserInfo.FLAG_FULL)
        private val USERS = listOf(PRIMARY_USER, ANOTHER_USER)
    }
}
