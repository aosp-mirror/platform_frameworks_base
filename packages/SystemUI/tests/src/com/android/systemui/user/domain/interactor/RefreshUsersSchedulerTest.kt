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
 *
 */

package com.android.systemui.user.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.user.data.repository.FakeUserRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class RefreshUsersSchedulerTest : SysuiTestCase() {

    private lateinit var underTest: RefreshUsersScheduler

    private lateinit var repository: FakeUserRepository

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        repository = FakeUserRepository()
    }

    @Test
    fun pause_preventsTheNextRefreshFromHappening() =
        runBlocking(IMMEDIATE) {
            underTest =
                RefreshUsersScheduler(
                    applicationScope = this,
                    mainDispatcher = IMMEDIATE,
                    repository = repository,
                )
            underTest.pause()

            underTest.refreshIfNotPaused()
            assertThat(repository.refreshUsersCallCount).isEqualTo(0)
        }

    @Test
    fun unpauseAndRefresh_forcesTheRefreshEvenWhenPaused() =
        runBlocking(IMMEDIATE) {
            underTest =
                RefreshUsersScheduler(
                    applicationScope = this,
                    mainDispatcher = IMMEDIATE,
                    repository = repository,
                )
            underTest.pause()

            underTest.unpauseAndRefresh()

            assertThat(repository.refreshUsersCallCount).isEqualTo(1)
        }

    @Test
    fun refreshIfNotPaused_refreshesWhenNotPaused() =
        runBlocking(IMMEDIATE) {
            underTest =
                RefreshUsersScheduler(
                    applicationScope = this,
                    mainDispatcher = IMMEDIATE,
                    repository = repository,
                )
            underTest.refreshIfNotPaused()

            assertThat(repository.refreshUsersCallCount).isEqualTo(1)
        }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
    }
}
