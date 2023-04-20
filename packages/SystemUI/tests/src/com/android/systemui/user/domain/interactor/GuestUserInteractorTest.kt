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

import android.app.admin.DevicePolicyManager
import android.content.pm.UserInfo
import android.os.UserHandle
import android.os.UserManager
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.systemui.GuestResetOrExitSessionReceiver
import com.android.systemui.GuestResumeSessionReceiver
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.domain.model.ShowDialogRequestModel
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.kotlinArgumentCaptor
import com.android.systemui.util.mockito.whenever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScope
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class GuestUserInteractorTest : SysuiTestCase() {

    @Mock private lateinit var manager: UserManager
    @Mock private lateinit var deviceProvisionedController: DeviceProvisionedController
    @Mock private lateinit var devicePolicyManager: DevicePolicyManager
    @Mock private lateinit var uiEventLogger: UiEventLogger
    @Mock private lateinit var showDialog: (ShowDialogRequestModel) -> Unit
    @Mock private lateinit var dismissDialog: () -> Unit
    @Mock private lateinit var selectUser: (Int) -> Unit
    @Mock private lateinit var switchUser: (Int) -> Unit
    @Mock private lateinit var resumeSessionReceiver: GuestResumeSessionReceiver
    @Mock private lateinit var resetOrExitSessionReceiver: GuestResetOrExitSessionReceiver

    private lateinit var underTest: GuestUserInteractor

    private lateinit var scope: TestCoroutineScope
    private lateinit var repository: FakeUserRepository

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(manager.createGuest(any())).thenReturn(GUEST_USER_INFO)

        scope = TestCoroutineScope()
        repository = FakeUserRepository()
        repository.setUserInfos(ALL_USERS)

        underTest =
            GuestUserInteractor(
                applicationContext = context,
                applicationScope = scope,
                mainDispatcher = IMMEDIATE,
                backgroundDispatcher = IMMEDIATE,
                manager = manager,
                repository = repository,
                deviceProvisionedController = deviceProvisionedController,
                devicePolicyManager = devicePolicyManager,
                refreshUsersScheduler =
                    RefreshUsersScheduler(
                        applicationScope = scope,
                        mainDispatcher = IMMEDIATE,
                        repository = repository,
                    ),
                uiEventLogger = uiEventLogger,
                resumeSessionReceiver = resumeSessionReceiver,
                resetOrExitSessionReceiver = resetOrExitSessionReceiver,
            )
    }

    @Test
    fun `registers broadcast receivers`() {
        verify(resumeSessionReceiver).register()
        verify(resetOrExitSessionReceiver).register()
    }

    @Test
    fun `onDeviceBootCompleted - allowed to add - create guest`() =
        runBlocking(IMMEDIATE) {
            setAllowedToAdd()

            underTest.onDeviceBootCompleted()

            verify(manager).createGuest(any())
            verify(deviceProvisionedController, never()).addCallback(any())
        }

    @Test
    fun `onDeviceBootCompleted - await provisioning - and create guest`() =
        runBlocking(IMMEDIATE) {
            setAllowedToAdd(isAllowed = false)
            underTest.onDeviceBootCompleted()
            val captor =
                kotlinArgumentCaptor<DeviceProvisionedController.DeviceProvisionedListener>()
            verify(deviceProvisionedController).addCallback(captor.capture())

            setAllowedToAdd(isAllowed = true)
            captor.value.onDeviceProvisionedChanged()

            verify(manager).createGuest(any())
            verify(deviceProvisionedController).removeCallback(captor.value)
        }

    @Test
    fun createAndSwitchTo() =
        runBlocking(IMMEDIATE) {
            underTest.createAndSwitchTo(
                showDialog = showDialog,
                dismissDialog = dismissDialog,
                selectUser = selectUser,
            )

            verify(showDialog).invoke(ShowDialogRequestModel.ShowUserCreationDialog(isGuest = true))
            verify(manager).createGuest(any())
            verify(dismissDialog).invoke()
            verify(selectUser).invoke(GUEST_USER_INFO.id)
        }

    @Test
    fun `createAndSwitchTo - fails to create - does not switch to`() =
        runBlocking(IMMEDIATE) {
            whenever(manager.createGuest(any())).thenReturn(null)

            underTest.createAndSwitchTo(
                showDialog = showDialog,
                dismissDialog = dismissDialog,
                selectUser = selectUser,
            )

            verify(showDialog).invoke(ShowDialogRequestModel.ShowUserCreationDialog(isGuest = true))
            verify(manager).createGuest(any())
            verify(dismissDialog).invoke()
            verify(selectUser, never()).invoke(anyInt())
        }

    @Test
    fun `exit - returns to target user`() =
        runBlocking(IMMEDIATE) {
            repository.setSelectedUserInfo(GUEST_USER_INFO)

            val targetUserId = NON_GUEST_USER_INFO.id
            underTest.exit(
                guestUserId = GUEST_USER_INFO.id,
                targetUserId = targetUserId,
                forceRemoveGuestOnExit = false,
                showDialog = showDialog,
                dismissDialog = dismissDialog,
                switchUser = switchUser,
            )

            verify(manager, never()).markGuestForDeletion(anyInt())
            verify(manager, never()).removeUser(anyInt())
            verify(switchUser).invoke(targetUserId)
        }

    @Test
    fun `exit - returns to last non-guest`() =
        runBlocking(IMMEDIATE) {
            val expectedUserId = NON_GUEST_USER_INFO.id
            whenever(manager.getUserInfo(expectedUserId)).thenReturn(NON_GUEST_USER_INFO)
            repository.lastSelectedNonGuestUserId = expectedUserId
            repository.setSelectedUserInfo(GUEST_USER_INFO)

            underTest.exit(
                guestUserId = GUEST_USER_INFO.id,
                targetUserId = UserHandle.USER_NULL,
                forceRemoveGuestOnExit = false,
                showDialog = showDialog,
                dismissDialog = dismissDialog,
                switchUser = switchUser,
            )

            verify(manager, never()).markGuestForDeletion(anyInt())
            verify(manager, never()).removeUser(anyInt())
            verify(switchUser).invoke(expectedUserId)
        }

    @Test
    fun `exit - last non-guest was removed - returns to system`() =
        runBlocking(IMMEDIATE) {
            val removedUserId = 310
            repository.lastSelectedNonGuestUserId = removedUserId
            repository.setSelectedUserInfo(GUEST_USER_INFO)

            underTest.exit(
                guestUserId = GUEST_USER_INFO.id,
                targetUserId = UserHandle.USER_NULL,
                forceRemoveGuestOnExit = false,
                showDialog = showDialog,
                dismissDialog = dismissDialog,
                switchUser = switchUser,
            )

            verify(manager, never()).markGuestForDeletion(anyInt())
            verify(manager, never()).removeUser(anyInt())
            verify(switchUser).invoke(UserHandle.USER_SYSTEM)
        }

    @Test
    fun `exit - guest was ephemeral - it is removed`() =
        runBlocking(IMMEDIATE) {
            whenever(manager.markGuestForDeletion(anyInt())).thenReturn(true)
            repository.setUserInfos(listOf(NON_GUEST_USER_INFO, EPHEMERAL_GUEST_USER_INFO))
            repository.setSelectedUserInfo(EPHEMERAL_GUEST_USER_INFO)
            val targetUserId = NON_GUEST_USER_INFO.id

            underTest.exit(
                guestUserId = GUEST_USER_INFO.id,
                targetUserId = targetUserId,
                forceRemoveGuestOnExit = false,
                showDialog = showDialog,
                dismissDialog = dismissDialog,
                switchUser = switchUser,
            )

            verify(manager).markGuestForDeletion(EPHEMERAL_GUEST_USER_INFO.id)
            verify(manager).removeUser(EPHEMERAL_GUEST_USER_INFO.id)
            verify(switchUser).invoke(targetUserId)
        }

    @Test
    fun `exit - force remove guest - it is removed`() =
        runBlocking(IMMEDIATE) {
            whenever(manager.markGuestForDeletion(anyInt())).thenReturn(true)
            repository.setSelectedUserInfo(GUEST_USER_INFO)
            val targetUserId = NON_GUEST_USER_INFO.id

            underTest.exit(
                guestUserId = GUEST_USER_INFO.id,
                targetUserId = targetUserId,
                forceRemoveGuestOnExit = true,
                showDialog = showDialog,
                dismissDialog = dismissDialog,
                switchUser = switchUser,
            )

            verify(manager).markGuestForDeletion(GUEST_USER_INFO.id)
            verify(manager).removeUser(GUEST_USER_INFO.id)
            verify(switchUser).invoke(targetUserId)
        }

    @Test
    fun `exit - selected different from guest user - do nothing`() =
        runBlocking(IMMEDIATE) {
            repository.setSelectedUserInfo(NON_GUEST_USER_INFO)

            underTest.exit(
                guestUserId = GUEST_USER_INFO.id,
                targetUserId = 123,
                forceRemoveGuestOnExit = false,
                showDialog = showDialog,
                dismissDialog = dismissDialog,
                switchUser = switchUser,
            )

            verifyDidNotExit()
        }

    @Test
    fun `exit - selected is actually not a guest user - do nothing`() =
        runBlocking(IMMEDIATE) {
            repository.setSelectedUserInfo(NON_GUEST_USER_INFO)

            underTest.exit(
                guestUserId = NON_GUEST_USER_INFO.id,
                targetUserId = 123,
                forceRemoveGuestOnExit = false,
                showDialog = showDialog,
                dismissDialog = dismissDialog,
                switchUser = switchUser,
            )

            verifyDidNotExit()
        }

    @Test
    fun `remove - returns to target user`() =
        runBlocking(IMMEDIATE) {
            whenever(manager.markGuestForDeletion(anyInt())).thenReturn(true)
            repository.setSelectedUserInfo(GUEST_USER_INFO)

            val targetUserId = NON_GUEST_USER_INFO.id
            underTest.remove(
                guestUserId = GUEST_USER_INFO.id,
                targetUserId = targetUserId,
                showDialog = showDialog,
                dismissDialog = dismissDialog,
                switchUser = switchUser,
            )

            verify(manager).markGuestForDeletion(GUEST_USER_INFO.id)
            verify(manager).removeUser(GUEST_USER_INFO.id)
            verify(switchUser).invoke(targetUserId)
        }

    @Test
    fun `remove - selected different from guest user - do nothing`() =
        runBlocking(IMMEDIATE) {
            whenever(manager.markGuestForDeletion(anyInt())).thenReturn(true)
            repository.setSelectedUserInfo(NON_GUEST_USER_INFO)

            underTest.remove(
                guestUserId = GUEST_USER_INFO.id,
                targetUserId = 123,
                showDialog = showDialog,
                dismissDialog = dismissDialog,
                switchUser = switchUser,
            )

            verifyDidNotRemove()
        }

    @Test
    fun `remove - selected is actually not a guest user - do nothing`() =
        runBlocking(IMMEDIATE) {
            whenever(manager.markGuestForDeletion(anyInt())).thenReturn(true)
            repository.setSelectedUserInfo(NON_GUEST_USER_INFO)

            underTest.remove(
                guestUserId = NON_GUEST_USER_INFO.id,
                targetUserId = 123,
                showDialog = showDialog,
                dismissDialog = dismissDialog,
                switchUser = switchUser,
            )

            verifyDidNotRemove()
        }

    private fun setAllowedToAdd(isAllowed: Boolean = true) {
        whenever(deviceProvisionedController.isDeviceProvisioned).thenReturn(isAllowed)
        whenever(devicePolicyManager.isDeviceManaged).thenReturn(!isAllowed)
    }

    private fun verifyDidNotExit() {
        verifyDidNotRemove()
        verify(manager, never()).getUserInfo(anyInt())
        verify(uiEventLogger, never()).log(any())
    }

    private fun verifyDidNotRemove() {
        verify(manager, never()).markGuestForDeletion(anyInt())
        verify(showDialog, never()).invoke(any())
        verify(dismissDialog, never()).invoke()
        verify(switchUser, never()).invoke(anyInt())
    }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
        private val NON_GUEST_USER_INFO =
            UserInfo(
                /* id= */ 818,
                /* name= */ "non_guest",
                /* flags= */ 0,
            )
        private val GUEST_USER_INFO =
            UserInfo(
                /* id= */ 669,
                /* name= */ "guest",
                /* iconPath= */ "",
                /* flags= */ 0,
                UserManager.USER_TYPE_FULL_GUEST,
            )
        private val EPHEMERAL_GUEST_USER_INFO =
            UserInfo(
                /* id= */ 669,
                /* name= */ "guest",
                /* iconPath= */ "",
                /* flags= */ UserInfo.FLAG_EPHEMERAL,
                UserManager.USER_TYPE_FULL_GUEST,
            )
        private val ALL_USERS =
            listOf(
                NON_GUEST_USER_INFO,
                GUEST_USER_INFO,
            )
    }
}
