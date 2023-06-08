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
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.UserManager
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.GuestResetOrExitSessionReceiver
import com.android.systemui.GuestResumeSessionReceiver
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Text
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.telephony.data.repository.FakeTelephonyRepository
import com.android.systemui.telephony.domain.interactor.TelephonyInteractor
import com.android.systemui.user.data.model.UserSwitcherSettingsModel
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.domain.interactor.GuestUserInteractor
import com.android.systemui.user.domain.interactor.HeadlessSystemUserMode
import com.android.systemui.user.domain.interactor.RefreshUsersScheduler
import com.android.systemui.user.domain.interactor.UserInteractor
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.doAnswer
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class StatusBarUserChipViewModelTest : SysuiTestCase() {
    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var activityManager: ActivityManager
    @Mock private lateinit var manager: UserManager
    @Mock private lateinit var headlessSystemUserMode: HeadlessSystemUserMode
    @Mock private lateinit var deviceProvisionedController: DeviceProvisionedController
    @Mock private lateinit var devicePolicyManager: DevicePolicyManager
    @Mock private lateinit var uiEventLogger: UiEventLogger
    @Mock private lateinit var resumeSessionReceiver: GuestResumeSessionReceiver
    @Mock private lateinit var resetOrExitSessionReceiver: GuestResetOrExitSessionReceiver
    @Mock private lateinit var commandQueue: CommandQueue
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor

    private lateinit var underTest: StatusBarUserChipViewModel

    private val userRepository = FakeUserRepository()
    private val keyguardRepository = FakeKeyguardRepository()
    private lateinit var guestUserInteractor: GuestUserInteractor
    private lateinit var refreshUsersScheduler: RefreshUsersScheduler

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        doAnswer { invocation ->
                val userId = invocation.arguments[0] as Int
                when (userId) {
                    USER_ID_0 -> return@doAnswer USER_IMAGE_0
                    USER_ID_1 -> return@doAnswer USER_IMAGE_1
                    USER_ID_2 -> return@doAnswer USER_IMAGE_2
                    else -> return@doAnswer mock<Bitmap>()
                }
            }
            .`when`(manager)
            .getUserIcon(anyInt())

        userRepository.isStatusBarUserChipEnabled = true

        refreshUsersScheduler =
            RefreshUsersScheduler(
                applicationScope = testScope.backgroundScope,
                mainDispatcher = testDispatcher,
                repository = userRepository,
            )
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
            )

        underTest = viewModel()
    }

    @Test
    fun `config is false - chip is disabled`() {
        // the enabled bit is set at SystemUI startup, so recreate the view model here
        userRepository.isStatusBarUserChipEnabled = false
        underTest = viewModel()

        assertThat(underTest.chipEnabled).isFalse()
    }

    @Test
    fun `config is true - chip is enabled`() {
        // the enabled bit is set at SystemUI startup, so recreate the view model here
        userRepository.isStatusBarUserChipEnabled = true
        underTest = viewModel()

        assertThat(underTest.chipEnabled).isTrue()
    }

    @Test
    fun `should show chip criteria - single user`() =
        testScope.runTest {
            userRepository.setUserInfos(listOf(USER_0))
            userRepository.setSelectedUserInfo(USER_0)
            userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))

            val values = mutableListOf<Boolean>()

            val job = launch { underTest.isChipVisible.toList(values) }
            advanceUntilIdle()

            assertThat(values).containsExactly(false)

            job.cancel()
        }

    @Test
    fun `should show chip criteria - multiple users`() =
        testScope.runTest {
            setMultipleUsers()

            var latest: Boolean? = null
            val job = underTest.isChipVisible.onEach { latest = it }.launchIn(this)
            yield()

            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun `user chip name - shows selected user info`() =
        testScope.runTest {
            setMultipleUsers()

            var latest: Text? = null
            val job = underTest.userName.onEach { latest = it }.launchIn(this)

            userRepository.setSelectedUserInfo(USER_0)
            assertThat(latest).isEqualTo(USER_NAME_0)

            userRepository.setSelectedUserInfo(USER_1)
            assertThat(latest).isEqualTo(USER_NAME_1)

            userRepository.setSelectedUserInfo(USER_2)
            assertThat(latest).isEqualTo(USER_NAME_2)

            job.cancel()
        }

    @Test
    fun `user chip avatar - shows selected user info`() =
        testScope.runTest {
            setMultipleUsers()

            // A little hacky. System server passes us bitmaps and we wrap them in the interactor.
            // Unwrap them to make sure we're always tracking the current user's bitmap
            var latest: Bitmap? = null
            val job =
                underTest.userAvatar
                    .onEach {
                        if (it !is BitmapDrawable) {
                            latest = null
                        }

                        latest = (it as BitmapDrawable).bitmap
                    }
                    .launchIn(this)

            userRepository.setSelectedUserInfo(USER_0)
            assertThat(latest).isEqualTo(USER_IMAGE_0)

            userRepository.setSelectedUserInfo(USER_1)
            assertThat(latest).isEqualTo(USER_IMAGE_1)

            userRepository.setSelectedUserInfo(USER_2)
            assertThat(latest).isEqualTo(USER_IMAGE_2)

            job.cancel()
        }

    private fun viewModel(): StatusBarUserChipViewModel {
        val featureFlags =
            FakeFeatureFlags().apply {
                set(Flags.FULL_SCREEN_USER_SWITCHER, false)
                set(Flags.FACE_AUTH_REFACTOR, true)
            }
        return StatusBarUserChipViewModel(
            context = context,
            interactor =
                UserInteractor(
                    applicationContext = context,
                    repository = userRepository,
                    activityStarter = activityStarter,
                    keyguardInteractor =
                        KeyguardInteractor(
                            repository = keyguardRepository,
                            commandQueue = commandQueue,
                            featureFlags = featureFlags,
                            bouncerRepository = FakeKeyguardBouncerRepository(),
                        ),
                    featureFlags = featureFlags,
                    manager = manager,
                    headlessSystemUserMode = headlessSystemUserMode,
                    applicationScope = testScope.backgroundScope,
                    telephonyInteractor =
                        TelephonyInteractor(
                            repository = FakeTelephonyRepository(),
                        ),
                    broadcastDispatcher = fakeBroadcastDispatcher,
                    keyguardUpdateMonitor = keyguardUpdateMonitor,
                    backgroundDispatcher = testDispatcher,
                    activityManager = activityManager,
                    refreshUsersScheduler = refreshUsersScheduler,
                    guestUserInteractor = guestUserInteractor,
                )
        )
    }

    private suspend fun setMultipleUsers() {
        userRepository.setUserInfos(listOf(USER_0, USER_1, USER_2))
        userRepository.setSelectedUserInfo(USER_0)
        userRepository.setSettings(UserSwitcherSettingsModel(isUserSwitcherEnabled = true))
    }

    companion object {
        private const val USER_ID_0 = 0
        private val USER_IMAGE_0 = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        private val USER_NAME_0 = Text.Loaded("zero")

        private const val USER_ID_1 = 1
        private val USER_IMAGE_1 = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        private val USER_NAME_1 = Text.Loaded("one")

        private const val USER_ID_2 = 2
        private val USER_IMAGE_2 = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        private val USER_NAME_2 = Text.Loaded("two")

        private val USER_0 =
            UserInfo(
                USER_ID_0,
                USER_NAME_0.text!!,
                /* iconPath */ "",
                /* flags */ UserInfo.FLAG_FULL,
                /* userType */ UserManager.USER_TYPE_FULL_SYSTEM
            )

        private val USER_1 =
            UserInfo(
                USER_ID_1,
                USER_NAME_1.text!!,
                /* iconPath */ "",
                /* flags */ UserInfo.FLAG_FULL,
                /* userType */ UserManager.USER_TYPE_FULL_SYSTEM
            )

        private val USER_2 =
            UserInfo(
                USER_ID_2,
                USER_NAME_2.text!!,
                /* iconPath */ "",
                /* flags */ UserInfo.FLAG_FULL,
                /* userType */ UserManager.USER_TYPE_FULL_SYSTEM
            )
    }
}
