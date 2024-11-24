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
import android.content.Context
import android.content.pm.UserInfo
import android.os.UserHandle
import android.os.UserManager
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.systemui.GuestResetOrExitSessionReceiver
import com.android.systemui.GuestResumeSessionReceiver
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.domain.model.ShowDialogRequestModel
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.kotlinArgumentCaptor
import com.android.systemui.util.mockito.whenever
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
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
    @Mock private lateinit var otherContext: Context

    private lateinit var underTest: GuestUserInteractor

    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val scope = kosmos.testScope
    private val repository = FakeUserRepository()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(manager.createGuest(any())).thenReturn(GUEST_USER_INFO)

        repository.setUserInfos(ALL_USERS)

        underTest = initGuestUserInteractor(context)
    }

    private fun initGuestUserInteractor(context: Context) =
        GuestUserInteractor(
            applicationContext = context,
            applicationScope = scope,
            mainDispatcher = kosmos.testDispatcher,
            backgroundDispatcher = kosmos.testDispatcher,
            manager = manager,
            repository = repository,
            deviceProvisionedController = deviceProvisionedController,
            devicePolicyManager = devicePolicyManager,
            refreshUsersScheduler =
                RefreshUsersScheduler(
                    applicationScope = scope,
                    mainDispatcher = kosmos.testDispatcher,
                    repository = repository,
                ),
            uiEventLogger = uiEventLogger,
            resumeSessionReceiver = resumeSessionReceiver,
            resetOrExitSessionReceiver = resetOrExitSessionReceiver,
        )

    @Test
    fun registersBroadcastReceivers() {
        verify(resumeSessionReceiver).register()
        verify(resetOrExitSessionReceiver).register()
    }

    @Test
    fun registersBroadcastReceiversOnlyForSystemUser() {
        for (i in 1..5) {
            whenever(otherContext.userId).thenReturn(UserHandle.MIN_SECONDARY_USER_ID + i)
            initGuestUserInteractor(otherContext)
        }
        verify(resumeSessionReceiver).register()
        verify(resetOrExitSessionReceiver).register()
    }

    @Test
    fun onDeviceBootCompleted_allowedToAdd_createGuest() =
        kosmos.runTest {
            setAllowedToAdd()

            underTest.onDeviceBootCompleted()

            verify(manager).createGuest(any())
            verify(deviceProvisionedController, never()).addCallback(any())
        }

    @Test
    fun onDeviceBootCompleted_awaitProvisioning_andCreateGuest() =
        kosmos.runTest {
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
        kosmos.runTest {
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
    fun createAndSwitchTo_failsToCreate_doesNotSwitchTo() =
        kosmos.runTest {
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
    fun exit_returnsToTargetUser() =
        kosmos.runTest {
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
    fun exit_returnsToLastNonGuest() =
        kosmos.runTest {
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
    fun exit_lastNonGuestWasRemoved_returnsToMainUser() =
        kosmos.runTest {
            val removedUserId = 310
            val mainUserId = 10
            repository.lastSelectedNonGuestUserId = removedUserId
            repository.mainUserId = mainUserId
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
            verify(switchUser).invoke(mainUserId)
        }

    @Test
    fun exit_guestWasEphemeral_itIsRemoved() =
        kosmos.runTest {
            whenever(manager.markGuestForDeletion(anyInt())).thenReturn(true)
            repository.setUserInfos(listOf(NON_GUEST_USER_INFO, EPHEMERAL_GUEST_USER_INFO))
            repository.setSelectedUserInfo(EPHEMERAL_GUEST_USER_INFO)
            val targetUserId = NON_GUEST_USER_INFO.id
            val ephemeralGuestUserHandle = UserHandle.of(EPHEMERAL_GUEST_USER_INFO.id)

            underTest.exit(
                guestUserId = GUEST_USER_INFO.id,
                targetUserId = targetUserId,
                forceRemoveGuestOnExit = false,
                showDialog = showDialog,
                dismissDialog = dismissDialog,
                switchUser = switchUser,
            )

            verify(manager).markGuestForDeletion(EPHEMERAL_GUEST_USER_INFO.id)
            verify(manager).removeUserWhenPossible(ephemeralGuestUserHandle, false)
            verify(switchUser).invoke(targetUserId)
        }

    @Test
    fun exit_forceRemoveGuest_itIsRemoved() =
        kosmos.runTest {
            whenever(manager.markGuestForDeletion(anyInt())).thenReturn(true)
            repository.setSelectedUserInfo(GUEST_USER_INFO)
            val targetUserId = NON_GUEST_USER_INFO.id
            val guestUserHandle = UserHandle.of(GUEST_USER_INFO.id)

            underTest.exit(
                guestUserId = GUEST_USER_INFO.id,
                targetUserId = targetUserId,
                forceRemoveGuestOnExit = true,
                showDialog = showDialog,
                dismissDialog = dismissDialog,
                switchUser = switchUser,
            )

            verify(manager).markGuestForDeletion(GUEST_USER_INFO.id)
            verify(manager).removeUserWhenPossible(guestUserHandle, false)
            verify(switchUser).invoke(targetUserId)
        }

    @Test
    fun exit_selectedDifferentFromGuestUser_doNothing() =
        kosmos.runTest {
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
    fun exit_selectedIsActuallyNotAguestUser_doNothing() =
        kosmos.runTest {
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
    fun remove_returnsToTargetUser() =
        kosmos.runTest {
            whenever(manager.markGuestForDeletion(anyInt())).thenReturn(true)
            repository.setSelectedUserInfo(GUEST_USER_INFO)

            val targetUserId = NON_GUEST_USER_INFO.id
            val guestUserHandle = UserHandle.of(GUEST_USER_INFO.id)
            underTest.remove(
                guestUserId = GUEST_USER_INFO.id,
                targetUserId = targetUserId,
                showDialog = showDialog,
                dismissDialog = dismissDialog,
                switchUser = switchUser,
            )

            verify(manager).markGuestForDeletion(GUEST_USER_INFO.id)
            verify(manager).removeUserWhenPossible(guestUserHandle, false)
            verify(switchUser).invoke(targetUserId)
        }

    @Test
    fun remove_selectedDifferentFromGuestUser_doNothing() =
        kosmos.runTest {
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
    fun remove_selectedIsActuallyNotAguestUser_doNothing() =
        kosmos.runTest {
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
            UserInfo(/* id= */ 818, /* name= */ "non_guest", /* flags= */ UserInfo.FLAG_FULL)
        private val GUEST_USER_INFO =
            UserInfo(
                /* id= */ 669,
                /* name= */ "guest",
                /* iconPath= */ "",
                /* flags= */ UserInfo.FLAG_FULL,
                UserManager.USER_TYPE_FULL_GUEST,
            )
        private val EPHEMERAL_GUEST_USER_INFO =
            UserInfo(
                /* id= */ 669,
                /* name= */ "guest",
                /* iconPath= */ "",
                /* flags= */ UserInfo.FLAG_EPHEMERAL or UserInfo.FLAG_FULL,
                UserManager.USER_TYPE_FULL_GUEST,
            )
        private val ALL_USERS = listOf(NON_GUEST_USER_INFO, GUEST_USER_INFO)
    }
}
