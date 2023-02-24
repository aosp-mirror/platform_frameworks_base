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

package com.android.systemui.user.data.repository

import android.content.pm.UserInfo
import android.os.UserManager
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.policy.UserSwitcherController
import com.android.systemui.user.data.source.UserRecord
import com.android.systemui.user.shared.model.UserActionModel
import com.android.systemui.user.shared.model.UserModel
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class UserRepositoryImplTest : SysuiTestCase() {

    @Mock private lateinit var manager: UserManager
    @Mock private lateinit var controller: UserSwitcherController
    @Captor
    private lateinit var userSwitchCallbackCaptor:
        ArgumentCaptor<UserSwitcherController.UserSwitchCallback>

    private lateinit var underTest: UserRepositoryImpl

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(controller.isAddUsersFromLockScreenEnabled).thenReturn(MutableStateFlow(false))
        whenever(controller.isGuestUserAutoCreated).thenReturn(false)
        whenever(controller.isGuestUserResetting).thenReturn(false)

        underTest =
            UserRepositoryImpl(
                appContext = context,
                manager = manager,
                controller = controller,
            )
    }

    @Test
    fun `users - registers for updates`() =
        runBlocking(IMMEDIATE) {
            val job = underTest.users.onEach {}.launchIn(this)

            verify(controller).addUserSwitchCallback(any())

            job.cancel()
        }

    @Test
    fun `users - unregisters from updates`() =
        runBlocking(IMMEDIATE) {
            val job = underTest.users.onEach {}.launchIn(this)
            verify(controller).addUserSwitchCallback(capture(userSwitchCallbackCaptor))

            job.cancel()

            verify(controller).removeUserSwitchCallback(userSwitchCallbackCaptor.value)
        }

    @Test
    fun `users - does not include actions`() =
        runBlocking(IMMEDIATE) {
            whenever(controller.users)
                .thenReturn(
                    arrayListOf(
                        createUserRecord(0, isSelected = true),
                        createActionRecord(UserActionModel.ADD_USER),
                        createUserRecord(1),
                        createUserRecord(2),
                        createActionRecord(UserActionModel.ADD_SUPERVISED_USER),
                        createActionRecord(UserActionModel.ENTER_GUEST_MODE),
                    )
                )
            var models: List<UserModel>? = null
            val job = underTest.users.onEach { models = it }.launchIn(this)

            assertThat(models).hasSize(3)
            assertThat(models?.get(0)?.id).isEqualTo(0)
            assertThat(models?.get(0)?.isSelected).isTrue()
            assertThat(models?.get(1)?.id).isEqualTo(1)
            assertThat(models?.get(1)?.isSelected).isFalse()
            assertThat(models?.get(2)?.id).isEqualTo(2)
            assertThat(models?.get(2)?.isSelected).isFalse()
            job.cancel()
        }

    @Test
    fun selectedUser() =
        runBlocking(IMMEDIATE) {
            whenever(controller.users)
                .thenReturn(
                    arrayListOf(
                        createUserRecord(0, isSelected = true),
                        createUserRecord(1),
                        createUserRecord(2),
                    )
                )
            var id: Int? = null
            val job = underTest.selectedUser.map { it.id }.onEach { id = it }.launchIn(this)

            assertThat(id).isEqualTo(0)

            whenever(controller.users)
                .thenReturn(
                    arrayListOf(
                        createUserRecord(0),
                        createUserRecord(1),
                        createUserRecord(2, isSelected = true),
                    )
                )
            verify(controller).addUserSwitchCallback(capture(userSwitchCallbackCaptor))
            userSwitchCallbackCaptor.value.onUserSwitched()
            assertThat(id).isEqualTo(2)

            job.cancel()
        }

    @Test
    fun `actions - unregisters from updates`() =
        runBlocking(IMMEDIATE) {
            val job = underTest.actions.onEach {}.launchIn(this)
            verify(controller).addUserSwitchCallback(capture(userSwitchCallbackCaptor))

            job.cancel()

            verify(controller).removeUserSwitchCallback(userSwitchCallbackCaptor.value)
        }

    @Test
    fun `actions - registers for updates`() =
        runBlocking(IMMEDIATE) {
            val job = underTest.actions.onEach {}.launchIn(this)

            verify(controller).addUserSwitchCallback(any())

            job.cancel()
        }

    @Test
    fun `actopms - does not include users`() =
        runBlocking(IMMEDIATE) {
            whenever(controller.users)
                .thenReturn(
                    arrayListOf(
                        createUserRecord(0, isSelected = true),
                        createActionRecord(UserActionModel.ADD_USER),
                        createUserRecord(1),
                        createUserRecord(2),
                        createActionRecord(UserActionModel.ADD_SUPERVISED_USER),
                        createActionRecord(UserActionModel.ENTER_GUEST_MODE),
                    )
                )
            var models: List<UserActionModel>? = null
            val job = underTest.actions.onEach { models = it }.launchIn(this)

            assertThat(models).hasSize(3)
            assertThat(models?.get(0)).isEqualTo(UserActionModel.ADD_USER)
            assertThat(models?.get(1)).isEqualTo(UserActionModel.ADD_SUPERVISED_USER)
            assertThat(models?.get(2)).isEqualTo(UserActionModel.ENTER_GUEST_MODE)
            job.cancel()
        }

    private fun createUserRecord(id: Int, isSelected: Boolean = false): UserRecord {
        return UserRecord(
            info = UserInfo(id, "name$id", 0),
            isCurrent = isSelected,
        )
    }

    private fun createActionRecord(action: UserActionModel): UserRecord {
        return UserRecord(
            isAddUser = action == UserActionModel.ADD_USER,
            isAddSupervisedUser = action == UserActionModel.ADD_SUPERVISED_USER,
            isGuest = action == UserActionModel.ENTER_GUEST_MODE,
        )
    }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
    }
}
