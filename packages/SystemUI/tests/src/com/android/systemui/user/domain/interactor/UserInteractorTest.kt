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
import android.content.Intent
import android.content.pm.UserInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.GuestResetOrExitSessionReceiver
import com.android.systemui.GuestResumeSessionReceiver
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Text
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.user.UserSwitchDialogController
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.telephony.data.repository.FakeTelephonyRepository
import com.android.systemui.telephony.domain.interactor.TelephonyInteractor
import com.android.systemui.user.data.model.UserSwitcherSettingsModel
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.data.source.UserRecord
import com.android.systemui.user.domain.model.ShowDialogRequestModel
import com.android.systemui.user.shared.model.UserActionModel
import com.android.systemui.user.shared.model.UserModel
import com.android.systemui.user.utils.MultiUserActionsEvent
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.kotlinArgumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertNotNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class UserInteractorTest : SysuiTestCase() {

    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var manager: UserManager
    @Mock private lateinit var headlessSystemUserMode: HeadlessSystemUserMode
    @Mock private lateinit var activityManager: ActivityManager
    @Mock private lateinit var deviceProvisionedController: DeviceProvisionedController
    @Mock private lateinit var devicePolicyManager: DevicePolicyManager
    @Mock private lateinit var uiEventLogger: UiEventLogger
    @Mock private lateinit var dialogShower: UserSwitchDialogController.DialogShower
    @Mock private lateinit var resumeSessionReceiver: GuestResumeSessionReceiver
    @Mock private lateinit var resetOrExitSessionReceiver: GuestResetOrExitSessionReceiver
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor

    private lateinit var underTest: UserInteractor

    private lateinit var testScope: TestScope
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

        featureFlags =
            FakeFeatureFlags().apply {
                set(Flags.FULL_SCREEN_USER_SWITCHER, false)
                set(Flags.FACE_AUTH_REFACTOR, true)
            }
        val reply = KeyguardInteractorFactory.create(featureFlags = featureFlags)
        keyguardRepository = reply.repository
        userRepository = FakeUserRepository()
        telephonyRepository = FakeTelephonyRepository()
        val testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        val refreshUsersScheduler =
            RefreshUsersScheduler(
                applicationScope = testScope.backgroundScope,
                mainDispatcher = testDispatcher,
                repository = userRepository,
            )
        underTest =
            UserInteractor(
                applicationContext = context,
                repository = userRepository,
                activityStarter = activityStarter,
                keyguardInteractor = reply.keyguardInteractor,
                manager = manager,
                headlessSystemUserMode = headlessSystemUserMode,
                applicationScope = testScope.backgroundScope,
                telephonyInteractor =
                    TelephonyInteractor(
                        repository = telephonyRepository,
                    ),
                broadcastDispatcher = fakeBroadcastDispatcher,
                keyguardUpdateMonitor = keyguardUpdateMonitor,
                backgroundDispatcher = testDispatcher,
                activityManager = activityManager,
                refreshUsersScheduler = refreshUsersScheduler,
                guestUserInteractor =
                    GuestUserInteractor(
                        applicationContext = context,
                        applicationScope = testScope.backgroundScope,
                        mainDispatcher = testDispatcher,
                        backgroundDispatcher = testDispatcher,
                        manager = manager,
                        repository = userRepository,
                        deviceProvisionedController = deviceProvisionedController,
                        devicePolicyManager = devicePolicyManager,
                        refreshUsersScheduler = refreshUsersScheduler,
                        uiEventLogger = uiEventLogger,
                        resumeSessionReceiver = resumeSessionReceiver,
                        resetOrExitSessionReceiver = resetOrExitSessionReceiver,
                    ),
                uiEventLogger = uiEventLogger,
                featureFlags = featureFlags,
            )
    }

    @Test
    fun testKeyguardUpdateMonitor_onKeyguardGoingAway() =
        testScope.runTest {
            val argumentCaptor = ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback::class.java)
            verify(keyguardUpdateMonitor).registerCallback(argumentCaptor.capture())

            argumentCaptor.value.onKeyguardGoingAway()

            val lastValue = collectLastValue(underTest.dialogDismissRequests)
            assertNotNull(lastValue)
        }

    @Test
    fun onRecordSelected_user() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 3, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            underTest.onRecordSelected(UserRecord(info = userInfos[1]), dialogShower)

            verify(uiEventLogger, times(1))
                .log(MultiUserActionsEvent.SWITCH_TO_USER_FROM_USER_SWITCHER)
            verify(dialogShower).dismiss()
            verify(activityManager).switchUser(userInfos[1].id)
            Unit
        }

    @Test
    fun onRecordSelected_switchToGuestUser() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 3, includeGuest = true)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            underTest.onRecordSelected(UserRecord(info = userInfos.last()))

            verify(uiEventLogger, times(1))
                .log(MultiUserActionsEvent.SWITCH_TO_GUEST_FROM_USER_SWITCHER)
            verify(activityManager).switchUser(userInfos.last().id)
            Unit
        }

    @Test
    fun onRecordSelected_switchToRestrictedUser() =
        testScope.runTest {
            var userInfos = createUserInfos(count = 2, includeGuest = false).toMutableList()
            userInfos.add(
                UserInfo(
                    60,
                    "Restricted user",
                    /* iconPath= */ "",
                    /* flags= */ UserInfo.FLAG_FULL,
                    UserManager.USER_TYPE_FULL_RESTRICTED,
                )
            )
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            underTest.onRecordSelected(UserRecord(info = userInfos.last()))

            verify(uiEventLogger, times(1))
                .log(MultiUserActionsEvent.SWITCH_TO_RESTRICTED_USER_FROM_USER_SWITCHER)
            verify(activityManager).switchUser(userInfos.last().id)
            Unit
        }

    @Test
    fun onRecordSelected_enterGuestMode() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 3, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            val guestUserInfo = createUserInfo(id = 1337, name = "guest", isGuest = true)
            whenever(manager.createGuest(any())).thenReturn(guestUserInfo)

            underTest.onRecordSelected(UserRecord(isGuest = true), dialogShower)
            runCurrent()

            verify(uiEventLogger, times(1))
                .log(MultiUserActionsEvent.CREATE_GUEST_FROM_USER_SWITCHER)
            verify(dialogShower).dismiss()
            verify(manager).createGuest(any())
            Unit
        }

    @Test
    fun onRecordSelected_action() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 3, includeGuest = true)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            underTest.onRecordSelected(UserRecord(isAddSupervisedUser = true), dialogShower)

            verify(uiEventLogger, times(1))
                .log(MultiUserActionsEvent.CREATE_RESTRICTED_USER_FROM_USER_SWITCHER)
            verify(dialogShower, never()).dismiss()
            verify(activityStarter).startActivity(any(), anyBoolean())
        }

    @Test
    fun users_switcherEnabled() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 3, includeGuest = true)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            val value = collectLastValue(underTest.users)

            assertUsers(models = value(), count = 3, includeGuest = true)
        }

    @Test
    fun users_switchesToSecondUser() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            val value = collectLastValue(underTest.users)
            userRepository.setSelectedUserInfo(userInfos[1])

            assertUsers(models = value(), count = 2, selectedIndex = 1)
        }

    @Test
    fun users_switcherNotEnabled() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = false))

            val value = collectLastValue(underTest.users)
            assertUsers(models = value(), count = 1)
        }

    @Test
    fun selectedUser() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            val value = collectLastValue(underTest.selectedUser)
            assertUser(value(), id = 0, isSelected = true)

            userRepository.setSelectedUserInfo(userInfos[1])
            assertUser(value(), id = 1, isSelected = true)
        }

    @Test
    fun actions_deviceUnlocked() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = false)

            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            keyguardRepository.setKeyguardShowing(false)
            val value = collectLastValue(underTest.actions)

            runCurrent()

            assertThat(value())
                .isEqualTo(
                    listOf(
                        UserActionModel.ENTER_GUEST_MODE,
                        UserActionModel.ADD_USER,
                        UserActionModel.ADD_SUPERVISED_USER,
                        UserActionModel.NAVIGATE_TO_USER_MANAGEMENT,
                    )
                )
        }

    @Test
    fun actions_deviceUnlocked_fullScreen() =
        testScope.runTest {
            featureFlags.set(Flags.FULL_SCREEN_USER_SWITCHER, true)
            val userInfos = createUserInfos(count = 2, includeGuest = false)

            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            keyguardRepository.setKeyguardShowing(false)
            val value = collectLastValue(underTest.actions)

            assertThat(value())
                .isEqualTo(
                    listOf(
                        UserActionModel.ADD_USER,
                        UserActionModel.ADD_SUPERVISED_USER,
                        UserActionModel.ENTER_GUEST_MODE,
                        UserActionModel.NAVIGATE_TO_USER_MANAGEMENT,
                    )
                )
        }

    @Test
    fun actions_deviceUnlockedUserNotPrimary_emptyList() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[1])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            keyguardRepository.setKeyguardShowing(false)
            val value = collectLastValue(underTest.actions)

            assertThat(value()).isEqualTo(emptyList<UserActionModel>())
        }

    @Test
    fun actions_deviceUnlockedUserIsGuest_emptyList() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = true)
            assertThat(userInfos[1].isGuest).isTrue()
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[1])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            keyguardRepository.setKeyguardShowing(false)
            val value = collectLastValue(underTest.actions)

            assertThat(value()).isEqualTo(emptyList<UserActionModel>())
        }

    @Test
    fun actions_deviceLockedAddFromLockscreenSet_fullList() =
        testScope.runTest {
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
            val value = collectLastValue(underTest.actions)

            assertThat(value())
                .isEqualTo(
                    listOf(
                        UserActionModel.ENTER_GUEST_MODE,
                        UserActionModel.ADD_USER,
                        UserActionModel.ADD_SUPERVISED_USER,
                        UserActionModel.NAVIGATE_TO_USER_MANAGEMENT,
                    )
                )
        }

    @Test
    fun actions_deviceLockedAddFromLockscreenSet_fullList_fullScreen() =
        testScope.runTest {
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
            val value = collectLastValue(underTest.actions)

            assertThat(value())
                .isEqualTo(
                    listOf(
                        UserActionModel.ADD_USER,
                        UserActionModel.ADD_SUPERVISED_USER,
                        UserActionModel.ENTER_GUEST_MODE,
                        UserActionModel.NAVIGATE_TO_USER_MANAGEMENT,
                    )
                )
        }

    @Test
    fun actions_deviceLocked_onlymanageUserIsShown() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            keyguardRepository.setKeyguardShowing(true)
            val value = collectLastValue(underTest.actions)

            assertThat(value()).isEqualTo(listOf(UserActionModel.NAVIGATE_TO_USER_MANAGEMENT))
        }

    @Test
    fun executeAction_addUser_dismissesDialogAndStartsActivity() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            keyguardRepository.setKeyguardShowing(false)

            underTest.executeAction(UserActionModel.ADD_USER)
            verify(uiEventLogger, times(1))
                .log(MultiUserActionsEvent.CREATE_USER_FROM_USER_SWITCHER)
            underTest.onDialogShown()
        }

    @Test
    fun executeAction_addSupervisedUser_dismissesDialogAndStartsActivity() =
        testScope.runTest {
            underTest.executeAction(UserActionModel.ADD_SUPERVISED_USER)

            verify(uiEventLogger, times(1))
                .log(MultiUserActionsEvent.CREATE_RESTRICTED_USER_FROM_USER_SWITCHER)
            val intentCaptor = kotlinArgumentCaptor<Intent>()
            verify(activityStarter).startActivity(intentCaptor.capture(), eq(true))
            assertThat(intentCaptor.value.action)
                .isEqualTo(UserManager.ACTION_CREATE_SUPERVISED_USER)
            assertThat(intentCaptor.value.`package`).isEqualTo(SUPERVISED_USER_CREATION_APP_PACKAGE)
        }

    @Test
    fun executeAction_navigateToManageUsers() =
        testScope.runTest {
            underTest.executeAction(UserActionModel.NAVIGATE_TO_USER_MANAGEMENT)

            val intentCaptor = kotlinArgumentCaptor<Intent>()
            verify(activityStarter).startActivity(intentCaptor.capture(), eq(true))
            assertThat(intentCaptor.value.action).isEqualTo(Settings.ACTION_USER_SETTINGS)
        }

    @Test
    fun executeAction_guestMode() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            val guestUserInfo = createUserInfo(id = 1337, name = "guest", isGuest = true)
            whenever(manager.createGuest(any())).thenReturn(guestUserInfo)
            val dialogRequests = mutableListOf<ShowDialogRequestModel?>()
            backgroundScope.launch {
                underTest.dialogShowRequests.collect {
                    dialogRequests.add(it)
                    if (it != null) {
                        underTest.onDialogShown()
                    }
                }
            }
            backgroundScope.launch {
                underTest.dialogDismissRequests.collect {
                    if (it != null) {
                        underTest.onDialogDismissed()
                    }
                }
            }

            underTest.executeAction(UserActionModel.ENTER_GUEST_MODE)
            runCurrent()

            verify(uiEventLogger, times(1))
                .log(MultiUserActionsEvent.CREATE_GUEST_FROM_USER_SWITCHER)
            assertThat(dialogRequests)
                .contains(
                    ShowDialogRequestModel.ShowUserCreationDialog(isGuest = true),
                )
            verify(activityManager).switchUser(guestUserInfo.id)
        }

    @Test
    fun selectUser_alreadySelectedGuestReSelected_exitGuestDialog() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = true)
            val guestUserInfo = userInfos[1]
            assertThat(guestUserInfo.isGuest).isTrue()
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(guestUserInfo)
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            val dialogRequest = collectLastValue(underTest.dialogShowRequests)

            underTest.selectUser(
                newlySelectedUserId = guestUserInfo.id,
                dialogShower = dialogShower,
            )

            assertThat(dialogRequest())
                .isInstanceOf(ShowDialogRequestModel.ShowExitGuestDialog::class.java)
            verify(dialogShower, never()).dismiss()
        }

    @Test
    fun selectUser_currentlyGuestNonGuestSelected_exitGuestDialog() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = true)
            val guestUserInfo = userInfos[1]
            assertThat(guestUserInfo.isGuest).isTrue()
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(guestUserInfo)
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            val dialogRequest = collectLastValue(underTest.dialogShowRequests)

            underTest.selectUser(newlySelectedUserId = userInfos[0].id, dialogShower = dialogShower)

            assertThat(dialogRequest())
                .isInstanceOf(ShowDialogRequestModel.ShowExitGuestDialog::class.java)
            verify(dialogShower, never()).dismiss()
        }

    @Test
    fun selectUser_notCurrentlyGuest_switchesUsers() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            val dialogRequest = collectLastValue(underTest.dialogShowRequests)

            underTest.selectUser(newlySelectedUserId = userInfos[1].id, dialogShower = dialogShower)

            assertThat(dialogRequest()).isNull()
            verify(activityManager).switchUser(userInfos[1].id)
            verify(dialogShower).dismiss()
        }

    @Test
    fun telephonyCallStateChanges_refreshesUsers() =
        testScope.runTest {
            runCurrent()

            val refreshUsersCallCount = userRepository.refreshUsersCallCount

            telephonyRepository.setCallState(1)
            runCurrent()

            assertThat(userRepository.refreshUsersCallCount).isEqualTo(refreshUsersCallCount + 1)
        }

    @Test
    fun userSwitchedBroadcast() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            val callback1: UserInteractor.UserCallback = mock()
            val callback2: UserInteractor.UserCallback = mock()
            underTest.addCallback(callback1)
            underTest.addCallback(callback2)
            runCurrent()
            val refreshUsersCallCount = userRepository.refreshUsersCallCount

            userRepository.setSelectedUserInfo(userInfos[1])
            runCurrent()
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_USER_SWITCHED)
                    .putExtra(Intent.EXTRA_USER_HANDLE, userInfos[1].id),
            )
            runCurrent()

            verify(callback1, atLeastOnce()).onUserStateChanged()
            verify(callback2, atLeastOnce()).onUserStateChanged()
            assertThat(userRepository.secondaryUserId).isEqualTo(userInfos[1].id)
            assertThat(userRepository.refreshUsersCallCount).isEqualTo(refreshUsersCallCount + 1)
        }

    @Test
    fun userInfoChangedBroadcast() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            val refreshUsersCallCount = userRepository.refreshUsersCallCount

            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_USER_INFO_CHANGED),
            )

            runCurrent()

            assertThat(userRepository.refreshUsersCallCount).isEqualTo(refreshUsersCallCount + 1)
        }

    @Test
    fun systemUserUnlockedBroadcast_refreshUsers() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            val refreshUsersCallCount = userRepository.refreshUsersCallCount

            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_USER_UNLOCKED)
                    .putExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_SYSTEM),
            )
            runCurrent()

            assertThat(userRepository.refreshUsersCallCount).isEqualTo(refreshUsersCallCount + 1)
        }

    @Test
    fun nonSystemUserUnlockedBroadcast_doNotRefreshUsers() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            val refreshUsersCallCount = userRepository.refreshUsersCallCount

            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(Intent.ACTION_USER_UNLOCKED).putExtra(Intent.EXTRA_USER_HANDLE, 1337),
            )

            assertThat(userRepository.refreshUsersCallCount).isEqualTo(refreshUsersCallCount)
        }

    @Test
    fun userRecords() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 3, includeGuest = false)
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            keyguardRepository.setKeyguardShowing(false)

            runCurrent()

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
        testScope.runTest {
            featureFlags.set(Flags.FULL_SCREEN_USER_SWITCHER, true)
            val userInfos = createUserInfos(count = 3, includeGuest = false)
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            keyguardRepository.setKeyguardShowing(false)

            runCurrent()

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
        testScope.runTest {
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
    fun users_secondaryUser_guestUserCanBeSwitchedTo() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 3, includeGuest = true)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[1])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            val res = collectLastValue(underTest.users)
            assertThat(res()?.size == 3).isTrue()
            assertThat(res()?.find { it.isGuest }).isNotNull()
        }

    @Test
    fun users_secondaryUser_noGuestAction() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 3, includeGuest = true)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[1])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            val res = collectLastValue(underTest.actions)
            assertThat(res()?.find { it == UserActionModel.ENTER_GUEST_MODE }).isNull()
        }

    @Test
    fun users_secondaryUser_noGuestUserRecord() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 3, includeGuest = true)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[1])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            assertThat(underTest.userRecords.value.find { it.isGuest }).isNull()
        }

    @Test
    fun showUserSwitcher_fullScreenDisabled_showsDialogSwitcher() =
        testScope.runTest {
            val expandable = mock<Expandable>()
            underTest.showUserSwitcher(expandable)

            val dialogRequest = collectLastValue(underTest.dialogShowRequests)

            // Dialog is shown.
            assertThat(dialogRequest())
                .isEqualTo(ShowDialogRequestModel.ShowUserSwitcherDialog(expandable))

            underTest.onDialogShown()
            assertThat(dialogRequest()).isNull()
        }

    @Test
    fun showUserSwitcher_fullScreenEnabled_launchesFullScreenDialog() =
        testScope.runTest {
            featureFlags.set(Flags.FULL_SCREEN_USER_SWITCHER, true)

            val expandable = mock<Expandable>()
            underTest.showUserSwitcher(expandable)

            val dialogRequest = collectLastValue(underTest.dialogShowRequests)

            // Dialog is shown.
            assertThat(dialogRequest())
                .isEqualTo(ShowDialogRequestModel.ShowUserSwitcherFullscreenDialog(expandable))

            underTest.onDialogShown()
            assertThat(dialogRequest()).isNull()
        }

    @Test
    fun users_secondaryUser_managedProfileIsNotIncluded() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 3, includeGuest = false).toMutableList()
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

            val res = collectLastValue(underTest.users)
            assertThat(res()?.size == 3).isTrue()
        }

    @Test
    fun currentUserIsNotPrimaryAndUserSwitcherIsDisabled() =
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[1])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = false))
            val selectedUser = collectLastValue(underTest.selectedUser)
            assertThat(selectedUser()).isNotNull()
        }

    @Test
    fun userRecords_isActionAndNoUsersUnlocked_actionIsDisabled() =
        testScope.runTest {
            keyguardRepository.setKeyguardShowing(true)
            whenever(manager.getUserSwitchability(any()))
                .thenReturn(UserManager.SWITCHABILITY_STATUS_SYSTEM_USER_LOCKED)
            val userInfos = createUserInfos(count = 3, includeGuest = false).toMutableList()
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[1])
            userRepository.setSettings(
                UserSwitcherSettingsModel(
                    isUserSwitcherEnabled = true,
                    isAddUsersFromLockscreen = true
                )
            )

            runCurrent()
            underTest.userRecords.value
                .filter { it.info == null }
                .forEach { action -> assertThat(action.isSwitchToEnabled).isFalse() }
        }

    @Test
    fun userRecords_isActionAndNoUsersUnlocked_actionIsDisabled_HeadlessMode() =
        testScope.runTest {
            keyguardRepository.setKeyguardShowing(true)
            whenever(headlessSystemUserMode.isHeadlessSystemUserMode()).thenReturn(true)
            whenever(manager.isUserUnlocked(anyInt())).thenReturn(false)
            val userInfos = createUserInfos(count = 3, includeGuest = false).toMutableList()
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[1])
            userRepository.setSettings(
                UserSwitcherSettingsModel(
                    isUserSwitcherEnabled = true,
                    isAddUsersFromLockscreen = true
                )
            )

            runCurrent()
            underTest.userRecords.value
                .filter { it.info == null }
                .forEach { action -> assertThat(action.isSwitchToEnabled).isFalse() }
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
        private val ICON = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        private val GUEST_ICON: Drawable = mock()
        private const val SUPERVISED_USER_CREATION_APP_PACKAGE = "supervisedUserCreation"
    }
}
