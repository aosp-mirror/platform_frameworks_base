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

import android.app.admin.DevicePolicyEventLogger
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserHandle
import android.provider.Settings
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.UiEventLogger
import com.android.internal.logging.nano.MetricsProto
import com.android.internal.util.FrameworkStatsLog
import com.android.systemui.animation.Expandable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.globalactions.GlobalActionsDialogLite
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.qs.FgsManagerController
import com.android.systemui.qs.QSSecurityFooterUtils
import com.android.systemui.qs.footer.data.model.UserSwitcherStatusModel
import com.android.systemui.qs.footer.data.repository.ForegroundServicesRepository
import com.android.systemui.qs.footer.domain.model.SecurityButtonConfig
import com.android.systemui.security.data.repository.SecurityRepository
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.user.data.repository.UserSwitcherRepository
import com.android.systemui.user.domain.interactor.UserSwitcherInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Interactor for the footer actions business logic. */
interface FooterActionsInteractor {
    /** The current [SecurityButtonConfig]. */
    val securityButtonConfig: Flow<SecurityButtonConfig?>

    /** The number of packages with a service running in the foreground. */
    val foregroundServicesCount: Flow<Int>

    /** Whether there are new packages with a service running in the foreground. */
    val hasNewForegroundServices: Flow<Boolean>

    /** The current [UserSwitcherStatusModel]. */
    val userSwitcherStatus: Flow<UserSwitcherStatusModel>

    /**
     * The flow emitting `Unit` whenever a request to show the device monitoring dialog is fired.
     */
    val deviceMonitoringDialogRequests: Flow<Unit>

    /**
     * Show the device monitoring dialog, expanded from [expandable] if it's not null.
     *
     * Important: [quickSettingsContext] *must* be the [Context] associated to the
     * [Quick Settings fragment][com.android.systemui.qs.QSFragmentLegacy].
     */
    fun showDeviceMonitoringDialog(quickSettingsContext: Context, expandable: Expandable?)

    /** Show the foreground services dialog. */
    fun showForegroundServicesDialog(expandable: Expandable)

    /** Show the power menu dialog. */
    fun showPowerMenuDialog(
        globalActionsDialogLite: GlobalActionsDialogLite,
        expandable: Expandable,
    )

    /** Show the settings. */
    fun showSettings(expandable: Expandable)

    /** Show the user switcher. */
    fun showUserSwitcher(expandable: Expandable)
}

@SysUISingleton
class FooterActionsInteractorImpl
@Inject
constructor(
    private val activityStarter: ActivityStarter,
    private val metricsLogger: MetricsLogger,
    private val uiEventLogger: UiEventLogger,
    private val deviceProvisionedController: DeviceProvisionedController,
    private val qsSecurityFooterUtils: QSSecurityFooterUtils,
    private val fgsManagerController: FgsManagerController,
    private val userSwitcherInteractor: UserSwitcherInteractor,
    securityRepository: SecurityRepository,
    foregroundServicesRepository: ForegroundServicesRepository,
    userSwitcherRepository: UserSwitcherRepository,
    broadcastDispatcher: BroadcastDispatcher,
    @Background bgDispatcher: CoroutineDispatcher,
) : FooterActionsInteractor {
    override val securityButtonConfig: Flow<SecurityButtonConfig?> =
        securityRepository.security.map { security ->
            withContext(bgDispatcher) { qsSecurityFooterUtils.getButtonConfig(security) }
        }

    override val foregroundServicesCount: Flow<Int> =
        foregroundServicesRepository.foregroundServicesCount

    override val hasNewForegroundServices: Flow<Boolean> =
        foregroundServicesRepository.hasNewChanges

    override val userSwitcherStatus: Flow<UserSwitcherStatusModel> =
        userSwitcherRepository.userSwitcherStatus

    override val deviceMonitoringDialogRequests: Flow<Unit> =
        broadcastDispatcher.broadcastFlow(
            IntentFilter(DevicePolicyManager.ACTION_SHOW_DEVICE_MONITORING_DIALOG),
            UserHandle.ALL,
            Context.RECEIVER_EXPORTED,
            null,
        )

    override fun showDeviceMonitoringDialog(
        quickSettingsContext: Context,
        expandable: Expandable?,
    ) {
        qsSecurityFooterUtils.showDeviceMonitoringDialog(quickSettingsContext, expandable)
        if (expandable != null) {
            DevicePolicyEventLogger.createEvent(
                    FrameworkStatsLog.DEVICE_POLICY_EVENT__EVENT_ID__DO_USER_INFO_CLICKED
                )
                .write()
        }
    }

    override fun showForegroundServicesDialog(expandable: Expandable) {
        fgsManagerController.showDialog(expandable)
    }

    override fun showPowerMenuDialog(
        globalActionsDialogLite: GlobalActionsDialogLite,
        expandable: Expandable,
    ) {
        uiEventLogger.log(GlobalActionsDialogLite.GlobalActionsEvent.GA_OPEN_QS)
        globalActionsDialogLite.showOrHideDialog(
            /* keyguardShowing= */ false,
            /* isDeviceProvisioned= */ true,
            expandable,
        )
    }

    override fun showSettings(expandable: Expandable) {
        if (!deviceProvisionedController.isCurrentUserSetup) {
            // If user isn't setup just unlock the device and dump them back at SUW.
            activityStarter.postQSRunnableDismissingKeyguard {}
            return
        }

        metricsLogger.action(MetricsProto.MetricsEvent.ACTION_QS_EXPANDED_SETTINGS_LAUNCH)
        activityStarter.startActivity(
            Intent(Settings.ACTION_SETTINGS),
            true /* dismissShade */,
            expandable.activityLaunchController(
                InteractionJankMonitor.CUJ_SHADE_APP_LAUNCH_FROM_SETTINGS_BUTTON
            ),
        )
    }

    override fun showUserSwitcher(expandable: Expandable) {
        userSwitcherInteractor.showUserSwitcher(expandable)
    }
}
