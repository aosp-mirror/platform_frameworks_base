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
 */

package com.android.systemui.qs.footer

import android.content.Context
import android.os.Handler
import android.os.UserManager
import android.provider.Settings
import android.testing.TestableLooper
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.UiEventLogger
import com.android.internal.logging.testing.FakeMetricsLogger
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.globalactions.GlobalActionsDialogLite
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.FakeFgsManagerController
import com.android.systemui.qs.FgsManagerController
import com.android.systemui.qs.QSSecurityFooterUtils
import com.android.systemui.qs.footer.data.repository.ForegroundServicesRepository
import com.android.systemui.qs.footer.data.repository.ForegroundServicesRepositoryImpl
import com.android.systemui.qs.footer.domain.interactor.FooterActionsInteractor
import com.android.systemui.qs.footer.domain.interactor.FooterActionsInteractorImpl
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel
import com.android.systemui.security.data.repository.SecurityRepository
import com.android.systemui.security.data.repository.SecurityRepositoryImpl
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.FakeSecurityController
import com.android.systemui.statusbar.policy.FakeUserInfoController
import com.android.systemui.statusbar.policy.SecurityController
import com.android.systemui.statusbar.policy.UserInfoController
import com.android.systemui.statusbar.policy.UserSwitcherController
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.user.data.repository.UserSwitcherRepository
import com.android.systemui.user.data.repository.UserSwitcherRepositoryImpl
import com.android.systemui.user.domain.interactor.UserSwitcherInteractor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.settings.FakeGlobalSettings
import com.android.systemui.util.settings.GlobalSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * Util class to create real implementations of the FooterActions repositories, viewModel and
 * interactor to be used in tests.
 */
class FooterActionsTestUtils(
    private val context: Context,
    private val testableLooper: TestableLooper,
    private val scheduler: TestCoroutineScheduler,
) {
    private val mockActivityStarter: ActivityStarter = mock<ActivityStarter>()
    /** Enable or disable the user switcher in the settings. */
    fun setUserSwitcherEnabled(settings: GlobalSettings, enabled: Boolean) {
        settings.putBool(Settings.Global.USER_SWITCHER_ENABLED, enabled)

        // The settings listener is processing messages on the bgHandler (usually backed by a
        // testableLooper in tests), so let's make sure we process the callback before continuing.
        testableLooper.processAllMessages()
    }

    /** Create a [FooterActionsViewModel] to be used in tests. */
    fun footerActionsViewModel(
        @Application context: Context = this.context.applicationContext,
        footerActionsInteractor: FooterActionsInteractor = footerActionsInteractor(),
        falsingManager: FalsingManager = FalsingManagerFake(),
        globalActionsDialogLite: GlobalActionsDialogLite = mock(),
        showPowerButton: Boolean = true,
    ): FooterActionsViewModel {
        return FooterActionsViewModel(
            context,
            footerActionsInteractor,
            falsingManager,
            globalActionsDialogLite,
            mockActivityStarter,
            showPowerButton,
        )
    }

    /** Create a [FooterActionsInteractor] to be used in tests. */
    fun footerActionsInteractor(
        activityStarter: ActivityStarter = mockActivityStarter,
        metricsLogger: MetricsLogger = FakeMetricsLogger(),
        uiEventLogger: UiEventLogger = UiEventLoggerFake(),
        deviceProvisionedController: DeviceProvisionedController = mock(),
        qsSecurityFooterUtils: QSSecurityFooterUtils = mock(),
        fgsManagerController: FgsManagerController = mock(),
        userSwitcherInteractor: UserSwitcherInteractor = mock(),
        securityRepository: SecurityRepository = securityRepository(),
        foregroundServicesRepository: ForegroundServicesRepository = foregroundServicesRepository(),
        userSwitcherRepository: UserSwitcherRepository = userSwitcherRepository(),
        broadcastDispatcher: BroadcastDispatcher = mock(),
        bgDispatcher: CoroutineDispatcher = StandardTestDispatcher(scheduler),
    ): FooterActionsInteractor {
        return FooterActionsInteractorImpl(
            activityStarter,
            metricsLogger,
            uiEventLogger,
            deviceProvisionedController,
            qsSecurityFooterUtils,
            fgsManagerController,
            userSwitcherInteractor,
            securityRepository,
            foregroundServicesRepository,
            userSwitcherRepository,
            broadcastDispatcher,
            bgDispatcher,
        )
    }

    /** Create a [SecurityRepository] to be used in tests. */
    fun securityRepository(
        securityController: SecurityController = FakeSecurityController(),
        bgDispatcher: CoroutineDispatcher = StandardTestDispatcher(scheduler),
    ): SecurityRepository {
        return SecurityRepositoryImpl(
            securityController,
            bgDispatcher,
        )
    }

    /** Create a [SecurityRepository] to be used in tests. */
    fun foregroundServicesRepository(
        fgsManagerController: FakeFgsManagerController = FakeFgsManagerController(),
    ): ForegroundServicesRepository {
        return ForegroundServicesRepositoryImpl(fgsManagerController)
    }

    /** Create a [UserSwitcherRepository] to be used in tests. */
    fun userSwitcherRepository(
        @Application context: Context = this.context.applicationContext,
        bgHandler: Handler = Handler(testableLooper.looper),
        bgDispatcher: CoroutineDispatcher = StandardTestDispatcher(scheduler),
        userManager: UserManager = mock(),
        userRepository: UserRepository = FakeUserRepository(),
        userSwitcherController: UserSwitcherController = mock(),
        userInfoController: UserInfoController = FakeUserInfoController(),
        settings: GlobalSettings = FakeGlobalSettings(),
    ): UserSwitcherRepository {
        return UserSwitcherRepositoryImpl(
            context,
            bgHandler,
            bgDispatcher,
            userManager,
            userSwitcherController,
            userInfoController,
            settings,
            userRepository,
        )
    }
}
