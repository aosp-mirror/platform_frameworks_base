/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.qs

import android.os.UserManager
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.UiEventLogger
import com.android.systemui.globalactions.GlobalActionsDialogLite
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.FooterActionsController.ExpansionState
import com.android.systemui.qs.dagger.QSFlagsModule
import com.android.systemui.statusbar.phone.MultiUserSwitchController
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.UserInfoController
import com.android.systemui.tuner.TunerService
import javax.inject.Inject
import javax.inject.Named

class FooterActionsControllerBuilder @Inject constructor(
    private val qsPanelController: QSPanelController,
    private val activityStarter: ActivityStarter,
    private val userManager: UserManager,
    private val userInfoController: UserInfoController,
    private val multiUserSwitchControllerFactory: MultiUserSwitchController.Factory,
    private val deviceProvisionedController: DeviceProvisionedController,
    private val falsingManager: FalsingManager,
    private val metricsLogger: MetricsLogger,
    private val tunerService: TunerService,
    private val globalActionsDialog: GlobalActionsDialogLite,
    private val uiEventLogger: UiEventLogger,
    @Named(QSFlagsModule.PM_LITE_ENABLED) private val showPMLiteButton: Boolean
) {
    private lateinit var view: FooterActionsView
    private lateinit var buttonsVisibleState: ExpansionState

    fun withView(view: FooterActionsView): FooterActionsControllerBuilder {
        this.view = view
        return this
    }

    fun withButtonsVisibleWhen(state: ExpansionState): FooterActionsControllerBuilder {
        buttonsVisibleState = state
        return this
    }

    fun build(): FooterActionsController {
        return FooterActionsController(view, qsPanelController, activityStarter, userManager,
                userInfoController, multiUserSwitchControllerFactory.create(view),
                deviceProvisionedController, falsingManager, metricsLogger, tunerService,
                globalActionsDialog, uiEventLogger, showPMLiteButton, buttonsVisibleState)
    }
}