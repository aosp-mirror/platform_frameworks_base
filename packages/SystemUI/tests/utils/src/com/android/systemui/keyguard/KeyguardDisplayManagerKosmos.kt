/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.keyguard

import android.content.testableContext
import com.android.keyguard.ConnectedDisplayKeyguardPresentation
import com.android.keyguard.KeyguardDisplayManager
import com.android.keyguard.KeyguardDisplayManager.DeviceStateHelper
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.navigationbar.navigationBarController
import com.android.systemui.settings.displayTracker
import com.android.systemui.shade.data.repository.shadeDisplaysRepository
import com.android.systemui.statusbar.policy.keyguardStateController
import org.mockito.kotlin.mock

var Kosmos.keyguardDisplayManager by
    Kosmos.Fixture {
        KeyguardDisplayManager(
            testableContext,
            { navigationBarController },
            displayTracker,
            fakeExecutor,
            fakeExecutor,
            mock<DeviceStateHelper>(),
            keyguardStateController,
            mock<ConnectedDisplayKeyguardPresentation.Factory>(),
            { shadeDisplaysRepository },
            applicationCoroutineScope,
        )
    }
