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
import android.os.UserManager
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.UserSwitcherController
import com.android.systemui.telephony.data.repository.FakeTelephonyRepository
import com.android.systemui.telephony.domain.interactor.TelephonyInteractor
import com.android.systemui.user.data.repository.FakeUserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestCoroutineScope
import org.mockito.Mock
import org.mockito.MockitoAnnotations

abstract class UserInteractorTest : SysuiTestCase() {

    @Mock protected lateinit var controller: UserSwitcherController
    @Mock protected lateinit var activityStarter: ActivityStarter
    @Mock protected lateinit var manager: UserManager
    @Mock protected lateinit var activityManager: ActivityManager
    @Mock protected lateinit var deviceProvisionedController: DeviceProvisionedController
    @Mock protected lateinit var devicePolicyManager: DevicePolicyManager
    @Mock protected lateinit var uiEventLogger: UiEventLogger

    protected lateinit var underTest: UserInteractor

    protected lateinit var testCoroutineScope: TestCoroutineScope
    protected lateinit var userRepository: FakeUserRepository
    protected lateinit var keyguardRepository: FakeKeyguardRepository
    protected lateinit var telephonyRepository: FakeTelephonyRepository

    abstract fun isRefactored(): Boolean

    open fun setUp() {
        MockitoAnnotations.initMocks(this)

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
                controller = controller,
                activityStarter = activityStarter,
                keyguardInteractor =
                    KeyguardInteractor(
                        repository = keyguardRepository,
                    ),
                featureFlags =
                    FakeFeatureFlags().apply {
                        set(Flags.USER_INTERACTOR_AND_REPO_USE_CONTROLLER, !isRefactored())
                    },
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
                    )
            )
    }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
    }
}
