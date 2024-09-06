/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.phone

import android.os.powerManager
import com.android.keyguard.keyguardUpdateMonitor
import com.android.systemui.assist.assistManager
import com.android.systemui.biometrics.authController
import com.android.systemui.doze.dozeLog
import com.android.systemui.keyguard.domain.interactor.dozeInteractor
import com.android.systemui.keyguard.wakefulnessLifecycle
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.plugins.statusbar.statusBarStateController
import com.android.systemui.shade.domain.interactor.shadeLockscreenInteractor
import com.android.systemui.statusbar.notificationShadeWindowController
import com.android.systemui.statusbar.policy.batteryController
import com.android.systemui.statusbar.policy.deviceProvisionedController
import com.android.systemui.statusbar.policy.headsUpManager
import com.android.systemui.statusbar.pulseExpansionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
val Kosmos.dozeServiceHost: DozeServiceHost by
    Kosmos.Fixture {
        DozeServiceHost(
            dozeLog,
            powerManager,
            wakefulnessLifecycle,
            statusBarStateController,
            deviceProvisionedController,
            headsUpManager,
            batteryController,
            scrimController,
            { biometricUnlockController },
            { assistManager },
            dozeScrimController,
            keyguardUpdateMonitor,
            pulseExpansionHandler,
            notificationShadeWindowController,
            notificationWakeUpCoordinator,
            authController,
            notificationIconAreaController,
            shadeLockscreenInteractor,
            dozeInteractor,
        )
    }
