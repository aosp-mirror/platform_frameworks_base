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

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.UserInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import androidx.test.filters.SmallTest
import com.android.internal.R.drawable.ic_account_circle
import com.android.internal.logging.UiEventLogger
import com.android.systemui.GuestResetOrExitSessionReceiver
import com.android.systemui.GuestResumeSessionReceiver
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Text
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.user.UserSwitchDialogController
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.telephony.data.repository.FakeTelephonyRepository
import com.android.systemui.telephony.domain.interactor.TelephonyInteractor
import com.android.systemui.user.UserSwitcherActivity
import com.android.systemui.user.data.model.UserSwitcherSettingsModel
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.data.source.UserRecord
import com.android.systemui.user.domain.model.ShowDialogRequestModel
import com.android.systemui.user.shared.model.UserActionModel
import com.android.systemui.user.shared.model.UserModel
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.kotlinArgumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class UserInteractorTest : SysuiTestCase() {

    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var manager: UserManager
    @Mock private lateinit var activityManager: ActivityManager
    @Mock private lateinit var deviceProvisionedController: DeviceProvisionedController
    @Mock private lateinit var devicePolicyManager: DevicePolicyManager
    @Mock private lateinit var uiEventLogger: UiEventLogger
    @Mock private lateinit var dialogShower: UserSwitchDialogController.DialogShower
    @Mock private lateinit var resumeSessionReceiver: GuestResumeSessionReceiver
    @Mock private lateinit var resetOrExitSessionReceiver: GuestResetOrExitSessionReceiver

    private lateinit var underTest: UserInteractor

    private lateinit var testCoroutineScope: TestCoroutineScope
    private lateinit var userRepository: FakeUserRepository
    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var telephonyRepository: FakeTelephonyRepository
    private lateinit var featureFlags: FakeFeatureFlags

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(manager.getUserIcon(anyInt())).thenReturn(ICON)
        whenever(manager.canAddMoreUsers(any())).thenReturn(true)

        overrideResource(R.drawable.ic_account_circle, GUEST_ICON)
        overrideResource(R.dimen.max_avatar_size, 10)
        overrideResource(
            com.android.internal.R.string.config_supervisedUserCreationPackage,
            SUPERVISED_USER_CREATION_APP_PACKAGE,
        )

        featureFlags = FakeFeatureFlags()
        featureFlags.set(Flags.FULL_SCREEN_USER_SWITCHER, false)
        userRepository = FakeUserRepository()
        keyguardRepository = FakeKeyguardRepository()
        telephonyRepository = FakeTelephonyRepository()
        testCoroutineScope = TestCoroutineScope()
        val refreshUsersScheduler =
            RefreshUsersScheduler(
                applicationScope = testCoroutineScope,
                mainDispatcher = IMMEDIATE,
                repository = userRepository,
            )
        underTest =
            UserInteractor(
                applicationContext = context,
                repository = userRepository,
                activityStarter = activityStarter,
                keyguardInteractor =
                    KeyguardInteractor(
                        repository = keyguardRepository,
                    ),
                manager = manager,
                applicationScope = testCoroutineScope,
                telephonyInteractor =
                    TelephonyInteractor(
                        repository = telephonyRepository,
                    ),
                broadcastDispatcher = fakeBroadcastDispatcher,
                backgroundDispatcher = IMMEDIATE,
                activityManager = activityManager,
                refreshUsersScheduler = refreshUsersScheduler,
                guestUserInteractor =
                    GuestUserInteractor(
                        applicationContext = context,
                        applicationScope = testCoroutineScope,
                        mainDispatcher = IMMEDIATE,
                        backgroundDispatcher = IMMEDIATE,
                        manager = manager,
                        repository = userRepository,
                        deviceProvisionedController = deviceProvisionedController,
                        devicePolicyManager = devicePolicyManager,
                        refreshUsersScheduler = refreshUsersScheduler,
                        uiEventLogger = uiEventLogger,
                        resumeSessionReceiver = resumeSessionReceiver,
                        resetOrExitSessionReceiver = resetOrExitSessionReceiver,
                    ),
                featureFlags = featureFlags,
            )
    }

    @Test
    fun `onRecordSelected - user`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 3, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            underTest.onRecordSelected(UserRecord(info = userInfos[1]), dialogShower)

            verify(dialogShower).dismiss()
            verify(activityManager).switchUser(userInfos[1].id)
            Unit
        }

    @Test
    fun `onRecordSelected - switch to guest user`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 3, includeGuest = true)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            underTest.onRecordSelected(UserRecord(info = userInfos.last()))

            verify(activityManager).switchUser(userInfos.last().id)
            Unit
        }

    @Test
    fun `onRecordSelected - enter guest mode`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 3, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            val guestUserInfo = createUserInfo(id = 1337, name = "guest", isGuest = true)
            whenever(manager.createGuest(any())).thenReturn(guestUserInfo)

            underTest.onRecordSelected(UserRecord(isGuest = true), dialogShower)

            verify(dialogShower).dismiss()
            verify(manager).createGuest(any())
            Unit
        }

    @Test
    fun `onRecordSelected - action`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 3, includeGuest = true)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            underTest.onRecordSelected(UserRecord(isAddSupervisedUser = true), dialogShower)

            verify(dialogShower, never()).dismiss()
            verify(activityStarter).startActivity(any(), anyBoolean())
        }

    @Test
    fun `users - switcher enabled`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 3, includeGuest = true)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            var value: List<UserModel>? = null
            val job = underTest.users.onEach { value = it }.launchIn(this)
            assertUsers(models = value, count = 3, includeGuest = true)

            job.cancel()
        }

    @Test
    fun `users - switches to second user`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            var value: List<UserModel>? = null
            val job = underTest.users.onEach { value = it }.launchIn(this)
            userRepository.setSelectedUserInfo(userInfos[1])

            assertUsers(models = value, count = 2, selectedIndex = 1)
            job.cancel()
        }

    @Test
    fun `users - switcher not enabled`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = false))

            var value: List<UserModel>? = null
            val job = underTest.users.onEach { value = it }.launchIn(this)
            assertUsers(models = value, count = 1)

            job.cancel()
        }

    @Test
    fun selectedUser() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            var value: UserModel? = null
            val job = underTest.selectedUser.onEach { value = it }.launchIn(this)
            assertUser(value, id = 0, isSelected = true)

            userRepository.setSelectedUserInfo(userInfos[1])
            assertUser(value, id = 1, isSelected = true)

            job.cancel()
        }

    @Test
    fun `actions - device unlocked`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 2, includeGuest = false)

            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            keyguardRepository.setKeyguardShowing(false)
            var value: List<UserActionModel>? = null
            val job = underTest.actions.onEach { value = it }.launchIn(this)

            assertThat(value)
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
    fun `actions - device unlocked - full screen`() =
        runBlocking(IMMEDIATE) {
            featureFlags.set(Flags.FULL_SCREEN_USER_SWITCHER, true)
            val userInfos = createUserInfos(count = 2, includeGuest = false)

            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            keyguardRepository.setKeyguardShowing(false)
            var value: List<UserActionModel>? = null
            val job = underTest.actions.onEach { value = it }.launchIn(this)

            assertThat(value)
                .isEqualTo(
                    listOf(
                        UserActionModel.ADD_USER,
                        UserActionModel.ADD_SUPERVISED_USER,
                        UserActionModel.ENTER_GUEST_MODE,
                        UserActionModel.NAVIGATE_TO_USER_MANAGEMENT,
                    )
                )

            job.cancel()
        }

    @Test
    fun `actions - device unlocked user not primary - empty list`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[1])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            keyguardRepository.setKeyguardShowing(false)
            var value: List<UserActionModel>? = null
            val job = underTest.actions.onEach { value = it }.launchIn(this)

            assertThat(value).isEqualTo(emptyList<UserActionModel>())

            job.cancel()
        }

    @Test
    fun `actions - device unlocked user is guest - empty list`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 2, includeGuest = true)
            assertThat(userInfos[1].isGuest).isTrue()
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[1])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            keyguardRepository.setKeyguardShowing(false)
            var value: List<UserActionModel>? = null
            val job = underTest.actions.onEach { value = it }.launchIn(this)

            assertThat(value).isEqualTo(emptyList<UserActionModel>())

            job.cancel()
        }

    @Test
    fun `actions - device locked add from lockscreen set - full list`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(
                UserSwitcherSettingsModel(
                    isUserSwitcherEnabled = true,
                    isAddUsersFromLockscreen = true,
                )
            )
            keyguardRepository.setKeyguardShowing(false)
            var value: List<UserActionModel>? = null
            val job = underTest.actions.onEach { value = it }.launchIn(this)

            assertThat(value)
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
    fun `actions - device locked add from lockscreen set - full list - full screen`() =
        runBlocking(IMMEDIATE) {
            featureFlags.set(Flags.FULL_SCREEN_USER_SWITCHER, true)
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(
                UserSwitcherSettingsModel(
                    isUserSwitcherEnabled = true,
                    isAddUsersFromLockscreen = true,
                )
            )
            keyguardRepository.setKeyguardShowing(false)
            var value: List<UserActionModel>? = null
            val job = underTest.actions.onEach { value = it }.launchIn(this)

            assertThat(value)
                .isEqualTo(
                    listOf(
                        UserActionModel.ADD_USER,
                        UserActionModel.ADD_SUPERVISED_USER,
                        UserActionModel.ENTER_GUEST_MODE,
                        UserActionModel.NAVIGATE_TO_USER_MANAGEMENT,
                    )
                )

            job.cancel()
        }

    @Test
    fun `actions - device locked - only  manage user is shown`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            keyguardRepository.setKeyguardShowing(true)
            var value: List<UserActionModel>? = null
            val job = underTest.actions.onEach { value = it }.launchIn(this)

            assertThat(value).isEqualTo(listOf(UserActionModel.NAVIGATE_TO_USER_MANAGEMENT))

            job.cancel()
        }

    @Test
    fun `executeAction - add user - dialog shown`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            keyguardRepository.setKeyguardShowing(false)
            var dialogRequest: ShowDialogRequestModel? = null
            val job = underTest.dialogShowRequests.onEach { dialogRequest = it }.launchIn(this)
            val dialogShower: UserSwitchDialogController.DialogShower = mock()

            underTest.executeAction(UserActionModel.ADD_USER, dialogShower)
            assertThat(dialogRequest)
                .isEqualTo(
                    ShowDialogRequestModel.ShowAddUserDialog(
                        userHandle = userInfos[0].userHandle,
                        isKeyguardShowing = false,
                        showEphemeralMessage = false,
                        dialogShower = dialogShower,
                    )
                )

            underTest.onDialogShown()
            assertThat(dialogRequest).isNull()

            job.cancel()
        }

    @Test
    fun `executeAction - add supervised user - starts activity`() =
        runBlocking(IMMEDIATE) {
            underTest.executeAction(UserActionModel.ADD_SUPERVISED_USER)

            val intentCaptor = kotlinArgumentCaptor<Intent>()
            verify(activityStarter).startActivity(intentCaptor.capture(), eq(true))
            assertThat(intentCaptor.value.action)
                .isEqualTo(UserManager.ACTION_CREATE_SUPERVISED_USER)
            assertThat(intentCaptor.value.`package`).isEqualTo(SUPERVISED_USER_CREATION_APP_PACKAGE)
        }

    @Test
    fun `executeAction - navigate to manage users`() =
        runBlocking(IMMEDIATE) {
            underTest.executeAction(UserActionModel.NAVIGATE_TO_USER_MANAGEMENT)

            val intentCaptor = kotlinArgumentCaptor<Intent>()
            verify(activityStarter).startActivity(intentCaptor.capture(), eq(true))
            assertThat(intentCaptor.value.action).isEqualTo(Settings.ACTION_USER_SETTINGS)
        }

    @Test
    fun `executeAction - guest mode`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            val guestUserInfo = createUserInfo(id = 1337, name = "guest", isGuest = true)
            whenever(manager.createGuest(any())).thenReturn(guestUserInfo)
            val dialogRequests = mutableListOf<ShowDialogRequestModel?>()
            val showDialogsJob =
                underTest.dialogShowRequests
                    .onEach {
                        dialogRequests.add(it)
                        if (it != null) {
                            underTest.onDialogShown()
                        }
                    }
                    .launchIn(this)
            val dismissDialogsJob =
                underTest.dialogDismissRequests
                    .onEach {
                        if (it != null) {
                            underTest.onDialogDismissed()
                        }
                    }
                    .launchIn(this)

            underTest.executeAction(UserActionModel.ENTER_GUEST_MODE)

            assertThat(dialogRequests)
                .contains(
                    ShowDialogRequestModel.ShowUserCreationDialog(isGuest = true),
                )
            verify(activityManager).switchUser(guestUserInfo.id)

            showDialogsJob.cancel()
            dismissDialogsJob.cancel()
        }

    @Test
    fun `selectUser - already selected guest re-selected - exit guest dialog`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 2, includeGuest = true)
            val guestUserInfo = userInfos[1]
            assertThat(guestUserInfo.isGuest).isTrue()
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(guestUserInfo)
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            var dialogRequest: ShowDialogRequestModel? = null
            val job = underTest.dialogShowRequests.onEach { dialogRequest = it }.launchIn(this)

            underTest.selectUser(
                newlySelectedUserId = guestUserInfo.id,
                dialogShower = dialogShower,
            )

            assertThat(dialogRequest)
                .isInstanceOf(ShowDialogRequestModel.ShowExitGuestDialog::class.java)
            verify(dialogShower, never()).dismiss()
            job.cancel()
        }

    @Test
    fun `selectUser - currently guest non-guest selected - exit guest dialog`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 2, includeGuest = true)
            val guestUserInfo = userInfos[1]
            assertThat(guestUserInfo.isGuest).isTrue()
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(guestUserInfo)
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            var dialogRequest: ShowDialogRequestModel? = null
            val job = underTest.dialogShowRequests.onEach { dialogRequest = it }.launchIn(this)

            underTest.selectUser(newlySelectedUserId = userInfos[0].id, dialogShower = dialogShower)

            assertThat(dialogRequest)
                .isInstanceOf(ShowDialogRequestModel.ShowExitGuestDialog::class.java)
            verify(dialogShower, never()).dismiss()
            job.cancel()
        }

    @Test
    fun `selectUser - not currently guest - switches users`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            var dialogRequest: ShowDialogRequestModel? = null
            val job = underTest.dialogShowRequests.onEach { dialogRequest = it }.launchIn(this)

            underTest.selectUser(newlySelectedUserId = userInfos[1].id, dialogShower = dialogShower)

            assertThat(dialogRequest).isNull()
            verify(activityManager).switchUser(userInfos[1].id)
            verify(dialogShower).dismiss()
            job.cancel()
        }

    @Test
    fun `Telephony call state changes - refreshes users`() =
        runBlocking(IMMEDIATE) {
            val refreshUsersCallCount = userRepository.refreshUsersCallCount

            telephonyRepository.setCallState(1)

            assertThat(userRepository.refreshUsersCallCount).isEqualTo(refreshUsersCallCount + 1)
        }

    @Test
    fun `User switched broadcast`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            val callback1: UserInteractor.UserCallback = mock()
            val callback2: UserInteractor.UserCallback = mock()
            underTest.addCallback(callback1)
            underTest.addCallback(callback2)
            val refreshUsersCallCount = userRepository.refreshUsersCallCount

            userRepository.setSelectedUserInfo(userInfos[1])
            fakeBroadcastDispatcher.registeredReceivers.forEach {
                it.onReceive(
                    context,
                    Intent(Intent.ACTION_USER_SWITCHED)
                        .putExtra(Intent.EXTRA_USER_HANDLE, userInfos[1].id),
                )
            }

            verify(callback1).onUserStateChanged()
            verify(callback2).onUserStateChanged()
            assertThat(userRepository.secondaryUserId).isEqualTo(userInfos[1].id)
            assertThat(userRepository.refreshUsersCallCount).isEqualTo(refreshUsersCallCount + 1)
        }

    @Test
    fun `User info changed broadcast`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            val refreshUsersCallCount = userRepository.refreshUsersCallCount

            fakeBroadcastDispatcher.registeredReceivers.forEach {
                it.onReceive(
                    context,
                    Intent(Intent.ACTION_USER_INFO_CHANGED),
                )
            }

            assertThat(userRepository.refreshUsersCallCount).isEqualTo(refreshUsersCallCount + 1)
        }

    @Test
    fun `System user unlocked broadcast - refresh users`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            val refreshUsersCallCount = userRepository.refreshUsersCallCount

            fakeBroadcastDispatcher.registeredReceivers.forEach {
                it.onReceive(
                    context,
                    Intent(Intent.ACTION_USER_UNLOCKED)
                        .putExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_SYSTEM),
                )
            }

            assertThat(userRepository.refreshUsersCallCount).isEqualTo(refreshUsersCallCount + 1)
        }

    @Test
    fun `Non-system user unlocked broadcast - do not refresh users`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            val refreshUsersCallCount = userRepository.refreshUsersCallCount

            fakeBroadcastDispatcher.registeredReceivers.forEach {
                it.onReceive(
                    context,
                    Intent(Intent.ACTION_USER_UNLOCKED).putExtra(Intent.EXTRA_USER_HANDLE, 1337),
                )
            }

            assertThat(userRepository.refreshUsersCallCount).isEqualTo(refreshUsersCallCount)
        }

    @Test
    fun userRecords() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 3, includeGuest = false)
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            keyguardRepository.setKeyguardShowing(false)

            testCoroutineScope.advanceUntilIdle()

            assertRecords(
                records = underTest.userRecords.value,
                userIds = listOf(0, 1, 2),
                selectedUserIndex = 0,
                includeGuest = false,
                expectedActions =
                    listOf(
                        UserActionModel.ENTER_GUEST_MODE,
                        UserActionModel.ADD_USER,
                        UserActionModel.ADD_SUPERVISED_USER,
                        UserActionModel.NAVIGATE_TO_USER_MANAGEMENT,
                    ),
            )
        }

    @Test
    fun userRecordsFullScreen() =
        runBlocking(IMMEDIATE) {
            featureFlags.set(Flags.FULL_SCREEN_USER_SWITCHER, true)
            val userInfos = createUserInfos(count = 3, includeGuest = false)
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            keyguardRepository.setKeyguardShowing(false)

            testCoroutineScope.advanceUntilIdle()

            assertRecords(
                records = underTest.userRecords.value,
                userIds = listOf(0, 1, 2),
                selectedUserIndex = 0,
                includeGuest = false,
                expectedActions =
                    listOf(
                        UserActionModel.ADD_USER,
                        UserActionModel.ADD_SUPERVISED_USER,
                        UserActionModel.ENTER_GUEST_MODE,
                        UserActionModel.NAVIGATE_TO_USER_MANAGEMENT,
                    ),
            )
        }

    @Test
    fun selectedUserRecord() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 3, includeGuest = true)
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            keyguardRepository.setKeyguardShowing(false)

            assertRecordForUser(
                record = underTest.selectedUserRecord.value,
                id = 0,
                hasPicture = true,
                isCurrent = true,
                isSwitchToEnabled = true,
            )
        }

    @Test
    fun `users - secondary user - guest user can be switched to`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 3, includeGuest = true)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[1])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            var res: List<UserModel>? = null
            val job = underTest.users.onEach { res = it }.launchIn(this)
            assertThat(res?.size == 3).isTrue()
            assertThat(res?.find { it.isGuest }).isNotNull()
            job.cancel()
        }

    @Test
    fun `users - secondary user - no guest action`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 3, includeGuest = true)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[1])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            var res: List<UserActionModel>? = null
            val job = underTest.actions.onEach { res = it }.launchIn(this)
            assertThat(res?.find { it == UserActionModel.ENTER_GUEST_MODE }).isNull()
            job.cancel()
        }

    @Test
    fun `users - secondary user - no guest user record`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 3, includeGuest = true)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[1])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            var res: List<UserRecord>? = null
            val job = underTest.userRecords.onEach { res = it }.launchIn(this)
            assertThat(res?.find { it.isGuest }).isNull()
            job.cancel()
        }

    @Test
    fun `show user switcher - full screen disabled - shows dialog switcher`() =
        runBlocking(IMMEDIATE) {
            var dialogRequest: ShowDialogRequestModel? = null
            val expandable = mock<Expandable>()
            underTest.showUserSwitcher(context, expandable)

            val job = underTest.dialogShowRequests.onEach { dialogRequest = it }.launchIn(this)

            // Dialog is shown.
            assertThat(dialogRequest).isEqualTo(ShowDialogRequestModel.ShowUserSwitcherDialog)

            underTest.onDialogShown()
            assertThat(dialogRequest).isNull()

            job.cancel()
        }

    @Test
    fun `show user switcher - full screen enabled - launches activity`() {
        featureFlags.set(Flags.FULL_SCREEN_USER_SWITCHER, true)

        val expandable = mock<Expandable>()
        underTest.showUserSwitcher(context, expandable)

        // Dialog is shown.
        val intentCaptor = argumentCaptor<Intent>()
        verify(activityStarter)
            .startActivity(
                intentCaptor.capture(),
                /* dismissShade= */ eq(true),
                /* ActivityLaunchAnimator.Controller= */ nullable(),
                /* showOverLockscreenWhenLocked= */ eq(true),
                eq(UserHandle.SYSTEM),
            )
        assertThat(intentCaptor.value.component)
            .isEqualTo(
                ComponentName(
                    context,
                    UserSwitcherActivity::class.java,
                )
            )
    }

    @Test
    fun `users - secondary user - managed profile is not included`() =
        runBlocking(IMMEDIATE) {
            var userInfos = createUserInfos(count = 3, includeGuest = false).toMutableList()
            userInfos.add(
                UserInfo(
                    50,
                    "Work Profile",
                    /* iconPath= */ "",
                    /* flags= */ UserInfo.FLAG_MANAGED_PROFILE
                )
            )
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[1])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            var res: List<UserModel>? = null
            val job = underTest.users.onEach { res = it }.launchIn(this)
            assertThat(res?.size == 3).isTrue()
            job.cancel()
        }

    @Test
    fun `current user is not primary and user switcher is disabled`() =
        runBlocking(IMMEDIATE) {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[1])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = false))
            var selectedUser: UserModel? = null
            val job = underTest.selectedUser.onEach { selectedUser = it }.launchIn(this)
            assertThat(selectedUser).isNotNull()
            job.cancel()
        }

    private fun assertUsers(
        models: List<UserModel>?,
        count: Int,
        selectedIndex: Int = 0,
        includeGuest: Boolean = false,
    ) {
        checkNotNull(models)
        assertThat(models.size).isEqualTo(count)
        models.forEachIndexed { index, model ->
            assertUser(
                model = model,
                id = index,
                isSelected = index == selectedIndex,
                isGuest = includeGuest && index == count - 1
            )
        }
    }

    private fun assertUser(
        model: UserModel?,
        id: Int,
        isSelected: Boolean = false,
        isGuest: Boolean = false,
    ) {
        checkNotNull(model)
        assertThat(model.id).isEqualTo(id)
        assertThat(model.name).isEqualTo(Text.Loaded(if (isGuest) "guest" else "user_$id"))
        assertThat(model.isSelected).isEqualTo(isSelected)
        assertThat(model.isSelectable).isTrue()
        assertThat(model.isGuest).isEqualTo(isGuest)
    }

    private fun assertRecords(
        records: List<UserRecord>,
        userIds: List<Int>,
        selectedUserIndex: Int = 0,
        includeGuest: Boolean = false,
        expectedActions: List<UserActionModel> = emptyList(),
    ) {
        assertThat(records.size >= userIds.size).isTrue()
        userIds.indices.forEach { userIndex ->
            val record = records[userIndex]
            assertThat(record.info).isNotNull()
            val isGuest = includeGuest && userIndex == userIds.size - 1
            assertRecordForUser(
                record = record,
                id = userIds[userIndex],
                hasPicture = !isGuest,
                isCurrent = userIndex == selectedUserIndex,
                isGuest = isGuest,
                isSwitchToEnabled = true,
            )
        }

        assertThat(records.size - userIds.size).isEqualTo(expectedActions.size)
        (userIds.size until userIds.size + expectedActions.size).forEach { actionIndex ->
            val record = records[actionIndex]
            assertThat(record.info).isNull()
            assertRecordForAction(
                record = record,
                type = expectedActions[actionIndex - userIds.size],
            )
        }
    }

    private fun assertRecordForUser(
        record: UserRecord?,
        id: Int? = null,
        hasPicture: Boolean = false,
        isCurrent: Boolean = false,
        isGuest: Boolean = false,
        isSwitchToEnabled: Boolean = false,
    ) {
        checkNotNull(record)
        assertThat(record.info?.id).isEqualTo(id)
        assertThat(record.picture != null).isEqualTo(hasPicture)
        assertThat(record.isCurrent).isEqualTo(isCurrent)
        assertThat(record.isGuest).isEqualTo(isGuest)
        assertThat(record.isSwitchToEnabled).isEqualTo(isSwitchToEnabled)
    }

    private fun assertRecordForAction(
        record: UserRecord,
        type: UserActionModel,
    ) {
        assertThat(record.isGuest).isEqualTo(type == UserActionModel.ENTER_GUEST_MODE)
        assertThat(record.isAddUser).isEqualTo(type == UserActionModel.ADD_USER)
        assertThat(record.isAddSupervisedUser)
            .isEqualTo(type == UserActionModel.ADD_SUPERVISED_USER)
    }

    private fun createUserInfos(
        count: Int,
        includeGuest: Boolean,
    ): List<UserInfo> {
        return (0 until count).map { index ->
            val isGuest = includeGuest && index == count - 1
            createUserInfo(
                id = index,
                name =
                    if (isGuest) {
                        "guest"
                    } else {
                        "user_$index"
                    },
                isPrimary = !isGuest && index == 0,
                isGuest = isGuest,
            )
        }
    }

    private fun createUserInfo(
        id: Int,
        name: String,
        isPrimary: Boolean = false,
        isGuest: Boolean = false,
    ): UserInfo {
        return UserInfo(
            id,
            name,
            /* iconPath= */ "",
            /* flags= */ if (isPrimary) {
                UserInfo.FLAG_PRIMARY or UserInfo.FLAG_ADMIN or UserInfo.FLAG_FULL
            } else {
                UserInfo.FLAG_FULL
            },
            if (isGuest) {
                UserManager.USER_TYPE_FULL_GUEST
            } else {
                UserManager.USER_TYPE_FULL_SYSTEM
            },
        )
    }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
        private val ICON = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        private val GUEST_ICON: Drawable = mock()
        private const val SUPERVISED_USER_CREATION_APP_PACKAGE = "supervisedUserCreation"
    }
}
