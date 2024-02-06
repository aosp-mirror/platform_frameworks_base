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
import android.content.Context
import android.content.Intent
import android.content.pm.UserInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.Flags as AConfigFlags
import com.android.systemui.GuestResetOrExitSessionReceiver
import com.android.systemui.GuestResumeSessionReceiver
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Text
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.process.processWrapper
import com.android.systemui.qs.user.UserSwitchDialogController
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.telephony.data.repository.fakeTelephonyRepository
import com.android.systemui.telephony.domain.interactor.telephonyInteractor
import com.android.systemui.testKosmos
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
import kotlinx.coroutines.runBlocking
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
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class UserSwitcherInteractorTest : SysuiTestCase() {

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

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private lateinit var spyContext: Context
    private lateinit var userRepository: FakeUserRepository
    private lateinit var keyguardReply: KeyguardInteractorFactory.WithDependencies
    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var refreshUsersScheduler: RefreshUsersScheduler

    private lateinit var underTest: UserSwitcherInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(manager.getUserIcon(anyInt())).thenReturn(ICON)
        whenever(manager.canAddMoreUsers(any())).thenReturn(true)

        overrideResource(com.android.settingslib.R.drawable.ic_account_circle, GUEST_ICON)
        overrideResource(R.dimen.max_avatar_size, 10)
        overrideResource(
            com.android.internal.R.string.config_supervisedUserCreationPackage,
            SUPERVISED_USER_CREATION_APP_PACKAGE,
        )

        kosmos.fakeFeatureFlagsClassic.set(Flags.FULL_SCREEN_USER_SWITCHER, false)
        mSetFlagsRule.enableFlags(AConfigFlags.FLAG_SWITCH_USER_ON_BG)
        spyContext = spy(context)
        keyguardReply =
            KeyguardInteractorFactory.create(featureFlags = kosmos.fakeFeatureFlagsClassic)
        keyguardRepository = keyguardReply.repository
        userRepository = FakeUserRepository()
        refreshUsersScheduler =
            RefreshUsersScheduler(
                applicationScope = testScope.backgroundScope,
                mainDispatcher = kosmos.testDispatcher,
                repository = userRepository,
            )
    }

    @Test
    fun createUserInteractor_processUser_noSecondaryService() {
        createUserInteractor()
        verify(spyContext, never()).startServiceAsUser(any(), any())
    }

    @Test
    fun createUserInteractor_nonProcessUser_startsSecondaryService() {
        val userId = Process.myUserHandle().identifier + 1
        whenever(manager.aliveUsers).thenReturn(listOf(createUserInfo(userId, "abc")))

        createUserInteractor(false /* startAsProcessUser */)
        verify(spyContext).startServiceAsUser(any(), any())
    }

    @Test
    fun testKeyguardUpdateMonitor_onKeyguardGoingAway() {
        createUserInteractor()
        testScope.runTest {
            val argumentCaptor = ArgumentCaptor.forClass(KeyguardUpdateMonitorCallback::class.java)
            verify(keyguardUpdateMonitor).registerCallback(argumentCaptor.capture())

            argumentCaptor.value.onKeyguardGoingAway()

            val lastValue = collectLastValue(underTest.dialogDismissRequests)
            assertNotNull(lastValue)
        }
    }

    @Test
    fun onRecordSelected_user() {
        createUserInteractor()
        testScope.runTest {
            val userInfos = createUserInfos(count = 3, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            underTest.onRecordSelected(UserRecord(info = userInfos[1]), dialogShower)
            runCurrent()

            verify(uiEventLogger, times(1))
                .log(MultiUserActionsEvent.SWITCH_TO_USER_FROM_USER_SWITCHER)
            verify(dialogShower).dismiss()
            verify(activityManager).switchUser(userInfos[1].id)
            Unit
        }
    }

    @Test
    fun onRecordSelected_switchToGuestUser() {
        createUserInteractor()
        testScope.runTest {
            val userInfos = createUserInfos(count = 3, includeGuest = true)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            underTest.onRecordSelected(UserRecord(info = userInfos.last()))
            runCurrent()

            verify(uiEventLogger, times(1))
                .log(MultiUserActionsEvent.SWITCH_TO_GUEST_FROM_USER_SWITCHER)
            verify(activityManager).switchUser(userInfos.last().id)
            Unit
        }
    }

    @Test
    fun onRecordSelected_switchToRestrictedUser() {
        createUserInteractor()
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
            runCurrent()

            verify(uiEventLogger, times(1))
                .log(MultiUserActionsEvent.SWITCH_TO_RESTRICTED_USER_FROM_USER_SWITCHER)
            verify(activityManager).switchUser(userInfos.last().id)
            Unit
        }
    }

    @Test
    fun onRecordSelected_enterGuestMode() {
        createUserInteractor()
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
    }

    @Test
    fun onRecordSelected_action() {
        createUserInteractor()
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
    }

    @Test
    fun users_switcherEnabled() {
        createUserInteractor()
        testScope.runTest {
            val userInfos = createUserInfos(count = 3, includeGuest = true)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            val value = collectLastValue(underTest.users)

            assertUsers(models = value(), count = 3, includeGuest = true)
        }
    }

    @Test
    fun users_switchesToSecondUser() {
        createUserInteractor()
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            val value = collectLastValue(underTest.users)
            userRepository.setSelectedUserInfo(userInfos[1])

            assertUsers(models = value(), count = 2, selectedIndex = 1)
        }
    }

    @Test
    fun users_switcherNotEnabled() {
        createUserInteractor()
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = false))

            val value = collectLastValue(underTest.users)
            assertUsers(models = value(), count = 1)
        }
    }

    @Test
    fun selectedUser() {
        createUserInteractor()
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
    }

    @Test
    fun actions_deviceUnlocked() {
        createUserInteractor()
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
    }

    @Test
    fun actions_deviceUnlocked_fullScreen() {
        createUserInteractor()
        testScope.runTest {
            kosmos.fakeFeatureFlagsClassic.set(Flags.FULL_SCREEN_USER_SWITCHER, true)
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
    }

    @Test
    fun actions_deviceUnlockedUserNotPrimary_emptyList() {
        createUserInteractor()
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[1])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            keyguardRepository.setKeyguardShowing(false)
            val value = collectLastValue(underTest.actions)

            assertThat(value()).isEqualTo(emptyList<UserActionModel>())
        }
    }

    @Test
    fun actions_deviceUnlockedUserIsGuest_emptyList() {
        createUserInteractor()
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
    }

    @Test
    fun actions_deviceLockedAddFromLockscreenSet_fullList() {
        createUserInteractor()
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
    }

    @Test
    fun actions_deviceLockedAddFromLockscreenSet_fullList_fullScreen() {
        createUserInteractor()
        testScope.runTest {
            kosmos.fakeFeatureFlagsClassic.set(Flags.FULL_SCREEN_USER_SWITCHER, true)
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
    }

    @Test
    fun actions_deviceLocked_onlymanageUserIsShown() {
        createUserInteractor()
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            keyguardRepository.setKeyguardShowing(true)
            val value = collectLastValue(underTest.actions)

            assertThat(value()).isEqualTo(listOf(UserActionModel.NAVIGATE_TO_USER_MANAGEMENT))
        }
    }

    @Test
    fun executeAction_addUser_dismissesDialogAndStartsActivity() {
        createUserInteractor()
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
    }

    @Test
    fun executeAction_addSupervisedUser_dismissesDialogAndStartsActivity() {
        createUserInteractor()
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
    }

    @Test
    fun executeAction_navigateToManageUsers() {
        createUserInteractor()
        testScope.runTest {
            underTest.executeAction(UserActionModel.NAVIGATE_TO_USER_MANAGEMENT)

            val intentCaptor = kotlinArgumentCaptor<Intent>()
            verify(activityStarter).startActivity(intentCaptor.capture(), eq(true))
            assertThat(intentCaptor.value.action).isEqualTo(Settings.ACTION_USER_SETTINGS)
        }
    }

    @Test
    fun executeAction_guestMode() {
        createUserInteractor()
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
    }

    @Test
    fun selectUser_alreadySelectedGuestReSelected_exitGuestDialog() {
        createUserInteractor()
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
    }

    @Test
    fun selectUser_currentlyGuestNonGuestSelected_exitGuestDialog() {
        createUserInteractor()
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
    }

    @Test
    fun selectUser_notCurrentlyGuest_switchesUsers() {
        createUserInteractor()
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
    }

    @Test
    fun telephonyCallStateChanges_refreshesUsers() {
        createUserInteractor()
        testScope.runTest {
            runCurrent()

            val refreshUsersCallCount = userRepository.refreshUsersCallCount

            kosmos.fakeTelephonyRepository.setCallState(1)
            runCurrent()

            assertThat(userRepository.refreshUsersCallCount).isEqualTo(refreshUsersCallCount + 1)
        }
    }

    @Test
    fun userSwitchedBroadcast() {
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            whenever(manager.aliveUsers).thenReturn(userInfos)
            createUserInteractor()
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
            val callback1: UserSwitcherInteractor.UserCallback = mock()
            val callback2: UserSwitcherInteractor.UserCallback = mock()
            underTest.addCallback(callback1)
            underTest.addCallback(callback2)
            runCurrent()
            val refreshUsersCallCount = userRepository.refreshUsersCallCount

            userRepository.setSelectedUserInfo(userInfos[1])
            runCurrent()
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                spyContext,
                Intent(Intent.ACTION_USER_SWITCHED)
                    .putExtra(Intent.EXTRA_USER_HANDLE, userInfos[1].id),
            )
            runCurrent()

            verify(callback1, atLeastOnce()).onUserStateChanged()
            verify(callback2, atLeastOnce()).onUserStateChanged()
            assertThat(userRepository.secondaryUserId).isEqualTo(userInfos[1].id)
            assertThat(userRepository.refreshUsersCallCount).isEqualTo(refreshUsersCallCount + 1)
            verify(spyContext).startServiceAsUser(any(), eq(UserHandle.of(userInfos[1].id)))
        }
    }

    @Test
    fun userInfoChangedBroadcast() {
        createUserInteractor()
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            runCurrent()
            val refreshUsersCallCount = userRepository.refreshUsersCallCount

            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                spyContext,
                Intent(Intent.ACTION_USER_INFO_CHANGED),
            )

            runCurrent()

            assertThat(userRepository.refreshUsersCallCount).isEqualTo(refreshUsersCallCount + 1)
        }
    }

    @Test
    fun systemUserUnlockedBroadcast_refreshUsers() {
        createUserInteractor()
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            runCurrent()
            val refreshUsersCallCount = userRepository.refreshUsersCallCount

            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                spyContext,
                Intent(Intent.ACTION_USER_UNLOCKED)
                    .putExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_SYSTEM),
            )
            runCurrent()

            assertThat(userRepository.refreshUsersCallCount).isEqualTo(refreshUsersCallCount + 1)
        }
    }

    @Test
    fun localeChanged_refreshUsers() {
        createUserInteractor()
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            runCurrent()
            val refreshUsersCallCount = userRepository.refreshUsersCallCount

            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                spyContext,
                Intent(Intent.ACTION_LOCALE_CHANGED)
            )
            runCurrent()

            assertThat(userRepository.refreshUsersCallCount).isEqualTo(refreshUsersCallCount + 1)
        }
    }

    @Test
    fun nonSystemUserUnlockedBroadcast_doNotRefreshUsers() {
        createUserInteractor()
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[0])
            val refreshUsersCallCount = userRepository.refreshUsersCallCount

            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                spyContext,
                Intent(Intent.ACTION_USER_UNLOCKED).putExtra(Intent.EXTRA_USER_HANDLE, 1337),
            )

            assertThat(userRepository.refreshUsersCallCount).isEqualTo(refreshUsersCallCount)
        }
    }

    @Test
    fun userRecords() {
        createUserInteractor()
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
    }

    @Test
    fun userRecordsFullScreen() {
        createUserInteractor()
        testScope.runTest {
            kosmos.fakeFeatureFlagsClassic.set(Flags.FULL_SCREEN_USER_SWITCHER, true)
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
    }

    @Test
    fun selectedUserRecord() {
        createUserInteractor()
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
    }

    @Test
    fun users_secondaryUser_guestUserCanBeSwitchedTo() {
        createUserInteractor()
        testScope.runTest {
            val userInfos = createUserInfos(count = 3, includeGuest = true)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[1])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            val res = collectLastValue(underTest.users)
            assertThat(res()?.size == 3).isTrue()
            assertThat(res()?.find { it.isGuest }).isNotNull()
        }
    }

    @Test
    fun users_secondaryUser_noGuestAction() {
        createUserInteractor()
        testScope.runTest {
            val userInfos = createUserInfos(count = 3, includeGuest = true)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[1])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            val res = collectLastValue(underTest.actions)
            assertThat(res()?.find { it == UserActionModel.ENTER_GUEST_MODE }).isNull()
        }
    }

    @Test
    fun users_secondaryUser_noGuestUserRecord() {
        createUserInteractor()
        testScope.runTest {
            val userInfos = createUserInfos(count = 3, includeGuest = true)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[1])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            assertThat(underTest.userRecords.value.find { it.isGuest }).isNull()
        }
    }

    @Test
    fun showUserSwitcher_fullScreenDisabled_showsDialogSwitcher() {
        createUserInteractor()
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
    }

    @Test
    fun showUserSwitcher_fullScreenEnabled_launchesFullScreenDialog() {
        createUserInteractor()
        testScope.runTest {
            kosmos.fakeFeatureFlagsClassic.set(Flags.FULL_SCREEN_USER_SWITCHER, true)

            val expandable = mock<Expandable>()
            underTest.showUserSwitcher(expandable)

            val dialogRequest = collectLastValue(underTest.dialogShowRequests)

            // Dialog is shown.
            assertThat(dialogRequest())
                .isEqualTo(ShowDialogRequestModel.ShowUserSwitcherFullscreenDialog(expandable))

            underTest.onDialogShown()
            assertThat(dialogRequest()).isNull()
        }
    }

    @Test
    fun users_secondaryUser_managedProfileIsNotIncluded() {
        createUserInteractor()
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
    }

    @Test
    fun currentUserIsNotPrimaryAndUserSwitcherIsDisabled() {
        createUserInteractor()
        testScope.runTest {
            val userInfos = createUserInfos(count = 2, includeGuest = false)
            userRepository.setUserInfos(userInfos)
            userRepository.setSelectedUserInfo(userInfos[1])
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = false))
            val selectedUser = collectLastValue(underTest.selectedUser)
            assertThat(selectedUser()).isNotNull()
        }
    }

    @Test
    fun userRecords_isActionAndNoUsersUnlocked_actionIsDisabled() {
        createUserInteractor()
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
    }

    @Test
    fun userRecords_isActionAndNoUsersUnlocked_actionIsDisabled_HeadlessMode() {
        createUserInteractor()
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
    }

    @Test
    fun initWithNoAliveUsers() {
        whenever(manager.aliveUsers).thenReturn(listOf())
        createUserInteractor()
        verify(spyContext, never()).startServiceAsUser(any(), any())
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

    private fun createUserInteractor(startAsProcessUser: Boolean = true) {
        val processUserId = Process.myUserHandle().identifier
        val startUserId = if (startAsProcessUser) processUserId else (processUserId + 1)
        runBlocking {
            val userInfo =
                createUserInfo(id = startUserId, name = "user_$startUserId", isPrimary = true)
            userRepository.setUserInfos(listOf(userInfo))
            userRepository.setSelectedUserInfo(userInfo)
        }
        underTest =
            UserSwitcherInteractor(
                applicationContext = spyContext,
                repository = userRepository,
                activityStarter = activityStarter,
                keyguardInteractor = keyguardReply.keyguardInteractor,
                manager = manager,
                headlessSystemUserMode = headlessSystemUserMode,
                applicationScope = testScope.backgroundScope,
                telephonyInteractor = kosmos.telephonyInteractor,
                broadcastDispatcher = fakeBroadcastDispatcher,
                keyguardUpdateMonitor = keyguardUpdateMonitor,
                backgroundDispatcher = kosmos.testDispatcher,
                mainDispatcher = kosmos.testDispatcher,
                activityManager = activityManager,
                refreshUsersScheduler = refreshUsersScheduler,
                guestUserInteractor =
                    GuestUserInteractor(
                        applicationContext = spyContext,
                        applicationScope = testScope.backgroundScope,
                        mainDispatcher = kosmos.testDispatcher,
                        backgroundDispatcher = kosmos.testDispatcher,
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
                featureFlags = kosmos.fakeFeatureFlagsClassic,
                userRestrictionChecker = mock(),
                processWrapper = kosmos.processWrapper,
            )
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
