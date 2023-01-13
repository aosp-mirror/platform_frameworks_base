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

package com.android.systemui.user.ui.viewmodel

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.pm.UserInfo
import android.os.UserManager
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.systemui.GuestResetOrExitSessionReceiver
import com.android.systemui.GuestResumeSessionReceiver
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Text
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.telephony.data.repository.FakeTelephonyRepository
import com.android.systemui.telephony.domain.interactor.TelephonyInteractor
import com.android.systemui.user.data.model.UserSwitcherSettingsModel
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.domain.interactor.GuestUserInteractor
import com.android.systemui.user.domain.interactor.RefreshUsersScheduler
import com.android.systemui.user.domain.interactor.UserInteractor
import com.android.systemui.user.legacyhelper.ui.LegacyUserUiHelper
import com.android.systemui.user.shared.model.UserActionModel
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class UserSwitcherViewModelTest : SysuiTestCase() {

    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var activityManager: ActivityManager
    @Mock private lateinit var manager: UserManager
    @Mock private lateinit var deviceProvisionedController: DeviceProvisionedController
    @Mock private lateinit var devicePolicyManager: DevicePolicyManager
    @Mock private lateinit var uiEventLogger: UiEventLogger
    @Mock private lateinit var resumeSessionReceiver: GuestResumeSessionReceiver
    @Mock private lateinit var resetOrExitSessionReceiver: GuestResetOrExitSessionReceiver

    private lateinit var underTest: UserSwitcherViewModel

    private lateinit var userRepository: FakeUserRepository
    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var powerRepository: FakePowerRepository

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(manager.canAddMoreUsers(any())).thenReturn(true)
        whenever(manager.getUserSwitchability(any()))
            .thenReturn(UserManager.SWITCHABILITY_STATUS_OK)
        overrideResource(
            com.android.internal.R.string.config_supervisedUserCreationPackage,
            SUPERVISED_USER_CREATION_PACKAGE,
        )

        testDispatcher = UnconfinedTestDispatcher()
        testScope = TestScope(testDispatcher)
        userRepository = FakeUserRepository()
        runBlocking {
            userRepository.setSettings(
                UserSwitcherSettingsModel(
                    isUserSwitcherEnabled = true,
                )
            )
        }

        keyguardRepository = FakeKeyguardRepository()
        powerRepository = FakePowerRepository()
        val refreshUsersScheduler =
            RefreshUsersScheduler(
                applicationScope = testScope.backgroundScope,
                mainDispatcher = testDispatcher,
                repository = userRepository,
            )
        val guestUserInteractor =
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
            )

        underTest =
            UserSwitcherViewModel.Factory(
                    userInteractor =
                        UserInteractor(
                            applicationContext = context,
                            repository = userRepository,
                            activityStarter = activityStarter,
                            keyguardInteractor =
                                KeyguardInteractor(
                                    repository = keyguardRepository,
                                ),
                            featureFlags =
                                FakeFeatureFlags().apply {
                                    set(Flags.FULL_SCREEN_USER_SWITCHER, false)
                                },
                            manager = manager,
                            applicationScope = testScope.backgroundScope,
                            telephonyInteractor =
                                TelephonyInteractor(
                                    repository = FakeTelephonyRepository(),
                                ),
                            broadcastDispatcher = fakeBroadcastDispatcher,
                            backgroundDispatcher = testDispatcher,
                            activityManager = activityManager,
                            refreshUsersScheduler = refreshUsersScheduler,
                            guestUserInteractor = guestUserInteractor,
                        ),
                    powerInteractor =
                        PowerInteractor(
                            repository = powerRepository,
                        ),
                    guestUserInteractor = guestUserInteractor,
                )
                .create(UserSwitcherViewModel::class.java)
    }

    @Test
    fun users() = testScope.runTest {
        val userInfos =
            listOf(
                UserInfo(
                    /* id= */ 0,
                    /* name= */ "zero",
                    /* iconPath= */ "",
                    /* flags= */ UserInfo.FLAG_PRIMARY or UserInfo.FLAG_ADMIN or UserInfo.FLAG_FULL,
                    UserManager.USER_TYPE_FULL_SYSTEM,
                ),
                UserInfo(
                    /* id= */ 1,
                    /* name= */ "one",
                    /* iconPath= */ "",
                    /* flags= */ UserInfo.FLAG_FULL,
                    UserManager.USER_TYPE_FULL_SYSTEM,
                ),
                UserInfo(
                    /* id= */ 2,
                    /* name= */ "two",
                    /* iconPath= */ "",
                    /* flags= */ UserInfo.FLAG_FULL,
                    UserManager.USER_TYPE_FULL_SYSTEM,
                ),
            )
        userRepository.setUserInfos(userInfos)
        userRepository.setSelectedUserInfo(userInfos[0])

        val userViewModels = mutableListOf<List<UserViewModel>>()
        val job = launch(testDispatcher) { underTest.users.toList(userViewModels) }

        assertThat(userViewModels.last()).hasSize(3)
        assertUserViewModel(
            viewModel = userViewModels.last()[0],
            viewKey = 0,
            name = Text.Loaded("zero"),
            isSelectionMarkerVisible = true,
        )
        assertUserViewModel(
            viewModel = userViewModels.last()[1],
            viewKey = 1,
            name = Text.Loaded("one"),
            isSelectionMarkerVisible = false,
        )
        assertUserViewModel(
            viewModel = userViewModels.last()[2],
            viewKey = 2,
            name = Text.Loaded("two"),
            isSelectionMarkerVisible = false,
        )
        job.cancel()
    }

    @Test
    fun `maximumUserColumns - few users`() = testScope.runTest {
        setUsers(count = 2)
        val values = mutableListOf<Int>()
        val job = launch(testDispatcher) { underTest.maximumUserColumns.toList(values) }

        assertThat(values.last()).isEqualTo(4)

        job.cancel()
    }

    @Test
    fun `maximumUserColumns - many users`() = testScope.runTest {
        setUsers(count = 5)
        val values = mutableListOf<Int>()
        val job = launch(testDispatcher) { underTest.maximumUserColumns.toList(values) }

        assertThat(values.last()).isEqualTo(3)
        job.cancel()
    }

    @Test
    fun `isOpenMenuButtonVisible - has actions - true`() = testScope.runTest {
        setUsers(2)

        val isVisible = mutableListOf<Boolean>()
        val job = launch(testDispatcher) { underTest.isOpenMenuButtonVisible.toList(isVisible) }

        assertThat(isVisible.last()).isTrue()
        job.cancel()
    }

    @Test
    fun `isOpenMenuButtonVisible - no actions - false`() = testScope.runTest {
        val userInfos = setUsers(2)
        userRepository.setSelectedUserInfo(userInfos[1])
        keyguardRepository.setKeyguardShowing(true)
        whenever(manager.canAddMoreUsers(any())).thenReturn(false)

        val isVisible = mutableListOf<Boolean>()
        val job = launch(testDispatcher) { underTest.isOpenMenuButtonVisible.toList(isVisible) }

        assertThat(isVisible.last()).isFalse()
        job.cancel()
    }

    @Test
    fun menu() = testScope.runTest {
        val isMenuVisible = mutableListOf<Boolean>()
        val job = launch(testDispatcher) { underTest.isMenuVisible.toList(isMenuVisible) }
        assertThat(isMenuVisible.last()).isFalse()

        underTest.onOpenMenuButtonClicked()
        assertThat(isMenuVisible.last()).isTrue()

        underTest.onMenuClosed()
        assertThat(isMenuVisible.last()).isFalse()

        job.cancel()
    }

    @Test
    fun `menu actions`() = testScope.runTest {
        setUsers(2)
        val actions = mutableListOf<List<UserActionViewModel>>()
        val job = launch(testDispatcher) { underTest.menu.toList(actions) }

        assertThat(actions.last().map { it.viewKey })
            .isEqualTo(
                listOf(
                    UserActionModel.ENTER_GUEST_MODE.ordinal.toLong(),
                    UserActionModel.ADD_USER.ordinal.toLong(),
                    UserActionModel.ADD_SUPERVISED_USER.ordinal.toLong(),
                    UserActionModel.NAVIGATE_TO_USER_MANAGEMENT.ordinal.toLong(),
                )
            )

        job.cancel()
    }

    @Test
    fun `isFinishRequested - finishes when user is switched`() = testScope.runTest {
        val userInfos = setUsers(count = 2)
        val isFinishRequested = mutableListOf<Boolean>()
        val job = launch(testDispatcher) { underTest.isFinishRequested.toList(isFinishRequested) }
        assertThat(isFinishRequested.last()).isFalse()

        userRepository.setSelectedUserInfo(userInfos[1])

        assertThat(isFinishRequested.last()).isTrue()

        job.cancel()
    }

    @Test
    fun `isFinishRequested - finishes when the screen turns off`() = testScope.runTest {
        setUsers(count = 2)
        powerRepository.setInteractive(true)
        val isFinishRequested = mutableListOf<Boolean>()
        val job = launch(testDispatcher) { underTest.isFinishRequested.toList(isFinishRequested) }
        assertThat(isFinishRequested.last()).isFalse()

        powerRepository.setInteractive(false)

        assertThat(isFinishRequested.last()).isTrue()

        job.cancel()
    }

    @Test
    fun `isFinishRequested - finishes when cancel button is clicked`() = testScope.runTest {
        setUsers(count = 2)
        powerRepository.setInteractive(true)
        val isFinishRequested = mutableListOf<Boolean>()
        val job = launch(testDispatcher) { underTest.isFinishRequested.toList(isFinishRequested) }
        assertThat(isFinishRequested.last()).isFalse()

        underTest.onCancelButtonClicked()

        assertThat(isFinishRequested.last()).isTrue()

        underTest.onFinished()

        assertThat(isFinishRequested.last()).isFalse()

        job.cancel()
    }

    @Test
    fun `guest selected -- name is exit guest`() = testScope.runTest {
        val userInfos =
                listOf(
                        UserInfo(
                                /* id= */ 0,
                                /* name= */ "zero",
                                /* iconPath= */ "",
                                /* flags= */ UserInfo.FLAG_PRIMARY or UserInfo.FLAG_ADMIN or UserInfo.FLAG_FULL,
                                UserManager.USER_TYPE_FULL_SYSTEM,
                        ),
                        UserInfo(
                                /* id= */ 1,
                                /* name= */ "one",
                                /* iconPath= */ "",
                                /* flags= */ UserInfo.FLAG_FULL,
                                UserManager.USER_TYPE_FULL_GUEST,
                        ),
                )

        userRepository.setUserInfos(userInfos)
        userRepository.setSelectedUserInfo(userInfos[1])

        val userViewModels = mutableListOf<List<UserViewModel>>()
        val job = launch(testDispatcher) { underTest.users.toList(userViewModels) }

        assertThat(userViewModels.last()).hasSize(2)
        assertUserViewModel(
                viewModel = userViewModels.last()[0],
                viewKey = 0,
                name = Text.Loaded("zero"),
                isSelectionMarkerVisible = false,
        )
        assertUserViewModel(
                viewModel = userViewModels.last()[1],
                viewKey = 1,
                name = Text.Resource(
                    com.android.settingslib.R.string.guest_exit_quick_settings_button
                ),
                isSelectionMarkerVisible = true,
        )
        job.cancel()
    }

    @Test
    fun `guest not selected -- name is guest`() = testScope.runTest {
        val userInfos =
                listOf(
                        UserInfo(
                                /* id= */ 0,
                                /* name= */ "zero",
                                /* iconPath= */ "",
                                /* flags= */ UserInfo.FLAG_PRIMARY or UserInfo.FLAG_ADMIN or UserInfo.FLAG_FULL,
                                UserManager.USER_TYPE_FULL_SYSTEM,
                        ),
                        UserInfo(
                                /* id= */ 1,
                                /* name= */ "one",
                                /* iconPath= */ "",
                                /* flags= */ UserInfo.FLAG_FULL,
                                UserManager.USER_TYPE_FULL_GUEST,
                        ),
                )

        userRepository.setUserInfos(userInfos)
        userRepository.setSelectedUserInfo(userInfos[0])
        runCurrent()

        val userViewModels = mutableListOf<List<UserViewModel>>()
        val job = launch(testDispatcher) { underTest.users.toList(userViewModels) }

        assertThat(userViewModels.last()).hasSize(2)
        assertUserViewModel(
                viewModel = userViewModels.last()[0],
                viewKey = 0,
                name = Text.Loaded("zero"),
                isSelectionMarkerVisible = true,
        )
        assertUserViewModel(
                viewModel = userViewModels.last()[1],
                viewKey = 1,
                name = Text.Loaded("one"),
                isSelectionMarkerVisible = false,
        )
        job.cancel()
    }

    private suspend fun setUsers(count: Int): List<UserInfo> {
        val userInfos =
            (0 until count).map { index ->
                UserInfo(
                    /* id= */ index,
                    /* name= */ "$index",
                    /* iconPath= */ "",
                    /* flags= */ if (index == 0) {
                        // This is the primary user.
                        UserInfo.FLAG_PRIMARY or UserInfo.FLAG_ADMIN or UserInfo.FLAG_FULL
                    } else {
                        // This isn't the primary user.
                        UserInfo.FLAG_FULL
                    },
                    UserManager.USER_TYPE_FULL_SYSTEM,
                )
            }
        userRepository.setUserInfos(userInfos)

        if (userInfos.isNotEmpty()) {
            userRepository.setSelectedUserInfo(userInfos[0])
        }
        return userInfos
    }

    private fun assertUserViewModel(
        viewModel: UserViewModel?,
        viewKey: Int,
        name: Text,
        isSelectionMarkerVisible: Boolean,
    ) {
        checkNotNull(viewModel)
        assertThat(viewModel.viewKey).isEqualTo(viewKey)
        assertThat(viewModel.name).isEqualTo(name)
        assertThat(viewModel.isSelectionMarkerVisible).isEqualTo(isSelectionMarkerVisible)
        assertThat(viewModel.alpha)
            .isEqualTo(LegacyUserUiHelper.USER_SWITCHER_USER_VIEW_SELECTABLE_ALPHA)
        assertThat(viewModel.onClicked).isNotNull()
    }

    companion object {
        private const val SUPERVISED_USER_CREATION_PACKAGE = "com.some.package"
    }
}
