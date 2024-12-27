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

package com.android.systemui.statusbar.notification.headsup

import android.content.applicationContext
import android.view.accessibility.accessibilityManagerWrapper
import com.android.internal.logging.uiEventLoggerFake
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.statusbar.statusBarStateController
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.statusbar.notification.collection.provider.visualStabilityProvider
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager
import com.android.systemui.statusbar.phone.keyguardBypassController
import com.android.systemui.statusbar.policy.configurationController
import com.android.systemui.util.concurrency.mockExecutorHandler
import com.android.systemui.util.kotlin.JavaAdapter
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.settings.fakeGlobalSettings
import com.android.systemui.util.time.fakeSystemClock

var Kosmos.mockHeadsUpManager by Fixture { mock<HeadsUpManager>() }

var Kosmos.headsUpManager: HeadsUpManager by Fixture {
    HeadsUpManagerImpl(
        applicationContext,
        headsUpManagerLogger,
        statusBarStateController,
        keyguardBypassController,
        mock<GroupMembershipManager>(),
        visualStabilityProvider,
        configurationController,
        mockExecutorHandler(fakeExecutor),
        fakeGlobalSettings,
        fakeSystemClock,
        fakeExecutor,
        accessibilityManagerWrapper,
        uiEventLoggerFake,
        JavaAdapter(testScope.backgroundScope),
        shadeInteractor,
        avalancheController,
    )
}
