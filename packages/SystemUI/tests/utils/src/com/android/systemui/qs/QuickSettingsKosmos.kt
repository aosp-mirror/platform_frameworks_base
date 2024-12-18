/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.app.admin.devicePolicyManager
import android.content.applicationContext
import android.os.fakeExecutorHandler
import android.os.looper
import com.android.internal.logging.metricsLogger
import com.android.internal.logging.uiEventLogger
import com.android.internal.logging.uiEventLoggerFake
import com.android.systemui.InstanceIdSequenceFake
import com.android.systemui.animation.dialogTransitionAnimator
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.classifier.falsingManager
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.plugins.activityStarter
import com.android.systemui.plugins.qs.QSFactory
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.footer.domain.interactor.FooterActionsInteractorImpl
import com.android.systemui.qs.footer.foregroundServicesRepository
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel
import com.android.systemui.security.data.repository.securityRepository
import com.android.systemui.settings.userTracker
import com.android.systemui.statusbar.policy.deviceProvisionedController
import com.android.systemui.statusbar.policy.securityController
import com.android.systemui.user.data.repository.userSwitcherRepository
import com.android.systemui.user.domain.interactor.userSwitcherInteractor
import com.android.systemui.util.mockito.mock

val Kosmos.instanceIdSequenceFake: InstanceIdSequenceFake by Fixture { InstanceIdSequenceFake(0) }
val Kosmos.qsEventLogger: QsEventLoggerFake by Fixture {
    QsEventLoggerFake(uiEventLoggerFake, instanceIdSequenceFake)
}

var Kosmos.qsTileFactory by Fixture<QSFactory> { FakeQSFactory(::tileCreator) }

val Kosmos.fgsManagerController by Fixture { FakeFgsManagerController() }

val Kosmos.footerActionsController by Fixture {
    FooterActionsController(
        fgsManagerController = fgsManagerController,
    )
}

val Kosmos.qsSecurityFooterUtils by Fixture {
    QSSecurityFooterUtils(
        applicationContext,
        devicePolicyManager,
        userTracker,
        fakeExecutorHandler,
        activityStarter,
        securityController,
        looper,
        dialogTransitionAnimator,
    )
}

val Kosmos.footerActionsInteractor by Fixture {
    FooterActionsInteractorImpl(
        activityStarter = activityStarter,
        metricsLogger = metricsLogger,
        uiEventLogger = uiEventLogger,
        deviceProvisionedController = deviceProvisionedController,
        qsSecurityFooterUtils = qsSecurityFooterUtils,
        fgsManagerController = fgsManagerController,
        userSwitcherInteractor = userSwitcherInteractor,
        securityRepository = securityRepository,
        foregroundServicesRepository = foregroundServicesRepository,
        userSwitcherRepository = userSwitcherRepository,
        broadcastDispatcher = broadcastDispatcher,
        bgDispatcher = testDispatcher,
    )
}

val Kosmos.footerActionsViewModelFactory by Fixture {
    FooterActionsViewModel.Factory(
        context = applicationContext,
        falsingManager = falsingManager,
        footerActionsInteractor = footerActionsInteractor,
        globalActionsDialogLiteProvider = { mock() },
        activityStarter,
        showPowerButton = true,
    )
}

private fun tileCreator(spec: String): QSTile {
    return FakeQSTile(0).apply { tileSpec = spec }
}
