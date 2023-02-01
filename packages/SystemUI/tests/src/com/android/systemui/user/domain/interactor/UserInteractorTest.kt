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

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.policy.UserSwitcherController
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.shared.model.UserActionModel
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.nullable
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class UserInteractorTest : SysuiTestCase() {

    @Mock private lateinit var controller: UserSwitcherController
    @Mock private lateinit var activityStarter: ActivityStarter

    private lateinit var underTest: UserInteractor

    private lateinit var userRepository: FakeUserRepository
    private lateinit var keyguardRepository: FakeKeyguardRepository

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        userRepository = FakeUserRepository()
        keyguardRepository = FakeKeyguardRepository()
        underTest =
            UserInteractor(
                repository = userRepository,
                controller = controller,
                activityStarter = activityStarter,
                keyguardInteractor =
                    KeyguardInteractor(
                        repository = keyguardRepository,
                    ),
            )
    }

    @Test
    fun `actions - not actionable when locked and locked - no actions`() =
        runBlocking(IMMEDIATE) {
            userRepository.setActions(UserActionModel.values().toList())
            userRepository.setActionableWhenLocked(false)
            keyguardRepository.setKeyguardShowing(true)

            var actions: List<UserActionModel>? = null
            val job = underTest.actions.onEach { actions = it }.launchIn(this)

            assertThat(actions).isEmpty()
            job.cancel()
        }

    @Test
    fun `actions - not actionable when locked and not locked`() =
        runBlocking(IMMEDIATE) {
            userRepository.setActions(
                listOf(
                    UserActionModel.ENTER_GUEST_MODE,
                    UserActionModel.ADD_USER,
                    UserActionModel.ADD_SUPERVISED_USER,
                )
            )
            userRepository.setActionableWhenLocked(false)
            keyguardRepository.setKeyguardShowing(false)

            var actions: List<UserActionModel>? = null
            val job = underTest.actions.onEach { actions = it }.launchIn(this)

            assertThat(actions)
                .isEqualTo(
                    listOf(
                        UserActionModel.ENTER_GUEST_MODE,
                        UserActionModel.ADD_USER,
                        UserActionModel.ADD_SUPERVISED_USER,
                        UserActionModel.NAVIGATE_TO_USER_MANAGEMENT,
                    )
                )
            job.cancel()
        }

    @Test
    fun `actions - actionable when locked and not locked`() =
        runBlocking(IMMEDIATE) {
            userRepository.setActions(
                listOf(
                    UserActionModel.ENTER_GUEST_MODE,
                    UserActionModel.ADD_USER,
                    UserActionModel.ADD_SUPERVISED_USER,
                )
            )
            userRepository.setActionableWhenLocked(true)
            keyguardRepository.setKeyguardShowing(false)

            var actions: List<UserActionModel>? = null
            val job = underTest.actions.onEach { actions = it }.launchIn(this)

            assertThat(actions)
                .isEqualTo(
                    listOf(
                        UserActionModel.ENTER_GUEST_MODE,
                        UserActionModel.ADD_USER,
                        UserActionModel.ADD_SUPERVISED_USER,
                        UserActionModel.NAVIGATE_TO_USER_MANAGEMENT,
                    )
                )
            job.cancel()
        }

    @Test
    fun `actions - actionable when locked and locked`() =
        runBlocking(IMMEDIATE) {
            userRepository.setActions(
                listOf(
                    UserActionModel.ENTER_GUEST_MODE,
                    UserActionModel.ADD_USER,
                    UserActionModel.ADD_SUPERVISED_USER,
                )
            )
            userRepository.setActionableWhenLocked(true)
            keyguardRepository.setKeyguardShowing(true)

            var actions: List<UserActionModel>? = null
            val job = underTest.actions.onEach { actions = it }.launchIn(this)

            assertThat(actions)
                .isEqualTo(
                    listOf(
                        UserActionModel.ENTER_GUEST_MODE,
                        UserActionModel.ADD_USER,
                        UserActionModel.ADD_SUPERVISED_USER,
                        UserActionModel.NAVIGATE_TO_USER_MANAGEMENT,
                    )
                )
            job.cancel()
        }

    @Test
    fun selectUser() {
        val userId = 3

        underTest.selectUser(userId)

        verify(controller).onUserSelected(eq(userId), nullable())
    }

    @Test
    fun `executeAction - guest`() {
        underTest.executeAction(UserActionModel.ENTER_GUEST_MODE)

        verify(controller).createAndSwitchToGuestUser(nullable())
    }

    @Test
    fun `executeAction - add user`() {
        underTest.executeAction(UserActionModel.ADD_USER)

        verify(controller).showAddUserDialog(nullable())
    }

    @Test
    fun `executeAction - add supervised user`() {
        underTest.executeAction(UserActionModel.ADD_SUPERVISED_USER)

        verify(controller).startSupervisedUserActivity()
    }

    @Test
    fun `executeAction - manage users`() {
        underTest.executeAction(UserActionModel.NAVIGATE_TO_USER_MANAGEMENT)

        verify(activityStarter).startActivity(any(), anyBoolean())
    }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
    }
}
