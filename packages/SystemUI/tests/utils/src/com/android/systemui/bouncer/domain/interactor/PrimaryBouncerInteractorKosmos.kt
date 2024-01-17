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

package com.android.systemui.bouncer.domain.interactor

import android.content.applicationContext
import com.android.keyguard.keyguardSecurityModel
import com.android.keyguard.keyguardUpdateMonitor
import com.android.systemui.bouncer.data.repository.keyguardBouncerRepository
import com.android.systemui.bouncer.ui.BouncerView
import com.android.systemui.classifier.falsingCollector
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryFaceAuthInteractor
import com.android.systemui.keyguard.DismissCallbackRegistry
import com.android.systemui.keyguard.data.repository.trustRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.statusbar.policy.KeyguardStateControllerImpl
import com.android.systemui.user.domain.interactor.selectedUserInteractor
import com.android.systemui.util.concurrency.mockExecutorHandler
import com.android.systemui.util.mockito.mock

var Kosmos.mockPrimaryBouncerInteractor by Kosmos.Fixture { mock<PrimaryBouncerInteractor>() }
var Kosmos.primaryBouncerInteractor by
    Kosmos.Fixture {
        PrimaryBouncerInteractor(
            repository = keyguardBouncerRepository,
            primaryBouncerView = mock<BouncerView>(),
            mainHandler = mockExecutorHandler(executor = fakeExecutor),
            keyguardStateController = mock<KeyguardStateControllerImpl>(),
            keyguardSecurityModel = keyguardSecurityModel,
            primaryBouncerCallbackInteractor = mock<PrimaryBouncerCallbackInteractor>(),
            falsingCollector = falsingCollector,
            dismissCallbackRegistry = mock<DismissCallbackRegistry>(),
            context = applicationContext,
            keyguardUpdateMonitor = keyguardUpdateMonitor,
            trustRepository = trustRepository,
            applicationScope = applicationCoroutineScope,
            selectedUserInteractor = selectedUserInteractor,
            deviceEntryFaceAuthInteractor = deviceEntryFaceAuthInteractor,
        )
    }
