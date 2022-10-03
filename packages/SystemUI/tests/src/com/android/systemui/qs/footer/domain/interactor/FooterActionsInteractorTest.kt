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

package com.android.systemui.qs.footer.domain.interactor

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.provider.Settings
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import androidx.test.filters.SmallTest
import com.android.internal.logging.nano.MetricsProto
import com.android.internal.logging.testing.FakeMetricsLogger
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.globalactions.GlobalActionsDialogLite
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.QSSecurityFooterUtils
import com.android.systemui.qs.footer.FooterActionsTestUtils
import com.android.systemui.qs.user.UserSwitchDialogController
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.truth.correspondence.FakeUiEvent
import com.android.systemui.truth.correspondence.LogMaker
import com.android.systemui.user.UserSwitcherActivity
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.nullable
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class FooterActionsInteractorTest : SysuiTestCase() {
    private lateinit var utils: FooterActionsTestUtils

    @Before
    fun setUp() {
        utils = FooterActionsTestUtils(context, TestableLooper.get(this))
    }

    @Test
    fun showDeviceMonitoringDialog() {
        val qsSecurityFooterUtils = mock<QSSecurityFooterUtils>()
        val underTest = utils.footerActionsInteractor(qsSecurityFooterUtils = qsSecurityFooterUtils)

        val quickSettingsContext = mock<Context>()
        underTest.showDeviceMonitoringDialog(quickSettingsContext)
        verify(qsSecurityFooterUtils).showDeviceMonitoringDialog(quickSettingsContext, null)

        val view = mock<View>()
        whenever(view.context).thenReturn(quickSettingsContext)
        underTest.showDeviceMonitoringDialog(view)
        verify(qsSecurityFooterUtils).showDeviceMonitoringDialog(quickSettingsContext, null)
    }

    @Test
    fun showPowerMenuDialog() {
        val uiEventLogger = UiEventLoggerFake()
        val underTest = utils.footerActionsInteractor(uiEventLogger = uiEventLogger)

        val globalActionsDialogLite = mock<GlobalActionsDialogLite>()
        val view = mock<View>()
        underTest.showPowerMenuDialog(globalActionsDialogLite, view)

        // Event is logged.
        val logs = uiEventLogger.logs
        assertThat(logs)
            .comparingElementsUsing(FakeUiEvent.EVENT_ID)
            .containsExactly(GlobalActionsDialogLite.GlobalActionsEvent.GA_OPEN_QS.id)

        // Dialog is shown.
        verify(globalActionsDialogLite)
            .showOrHideDialog(
                /* keyguardShowing= */ false,
                /* isDeviceProvisioned= */ true,
                view,
            )
    }

    @Test
    fun showSettings_userSetUp() {
        val activityStarter = mock<ActivityStarter>()
        val deviceProvisionedController = mock<DeviceProvisionedController>()
        val metricsLogger = FakeMetricsLogger()

        // User is set up.
        whenever(deviceProvisionedController.isCurrentUserSetup).thenReturn(true)

        val underTest =
            utils.footerActionsInteractor(
                activityStarter = activityStarter,
                deviceProvisionedController = deviceProvisionedController,
                metricsLogger = metricsLogger,
            )

        underTest.showSettings(mock())

        // Event is logged.
        assertThat(metricsLogger.logs.toList())
            .comparingElementsUsing(LogMaker.CATEGORY)
            .containsExactly(MetricsProto.MetricsEvent.ACTION_QS_EXPANDED_SETTINGS_LAUNCH)

        // Activity is started.
        val intentCaptor = argumentCaptor<Intent>()
        verify(activityStarter)
            .startActivity(
                intentCaptor.capture(),
                /* dismissShade= */ eq(true),
                nullable() as? ActivityLaunchAnimator.Controller,
            )
        assertThat(intentCaptor.value.action).isEqualTo(Settings.ACTION_SETTINGS)
    }

    @Test
    fun showSettings_userNotSetUp() {
        val activityStarter = mock<ActivityStarter>()
        val deviceProvisionedController = mock<DeviceProvisionedController>()

        // User is not set up.
        whenever(deviceProvisionedController.isCurrentUserSetup).thenReturn(false)

        val underTest =
            utils.footerActionsInteractor(
                activityStarter = activityStarter,
                deviceProvisionedController = deviceProvisionedController,
            )

        underTest.showSettings(mock())

        // We only unlock the device.
        verify(activityStarter).postQSRunnableDismissingKeyguard(any())
    }

    @Test
    fun showUserSwitcher_fullScreenDisabled() {
        val featureFlags = FakeFeatureFlags().apply { set(Flags.FULL_SCREEN_USER_SWITCHER, false) }
        val userSwitchDialogController = mock<UserSwitchDialogController>()
        val underTest =
            utils.footerActionsInteractor(
                featureFlags = featureFlags,
                userSwitchDialogController = userSwitchDialogController,
            )

        val view = mock<View>()
        underTest.showUserSwitcher(view)

        // Dialog is shown.
        verify(userSwitchDialogController).showDialog(view)
    }

    @Test
    fun showUserSwitcher_fullScreenEnabled() {
        val featureFlags = FakeFeatureFlags().apply { set(Flags.FULL_SCREEN_USER_SWITCHER, true) }
        val activityStarter = mock<ActivityStarter>()
        val underTest =
            utils.footerActionsInteractor(
                featureFlags = featureFlags,
                activityStarter = activityStarter,
            )

        // The clicked view. The context is necessary because it's used to build the intent, that
        // we check below.
        val view = mock<View>()
        whenever(view.context).thenReturn(context)

        underTest.showUserSwitcher(view)

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
}
