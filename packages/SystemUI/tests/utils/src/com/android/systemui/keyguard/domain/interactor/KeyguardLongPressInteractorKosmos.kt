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

package com.android.systemui.keyguard.domain.interactor

import android.content.applicationContext
import android.view.accessibility.accessibilityManagerWrapper
import com.android.internal.logging.uiEventLogger
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.deviceentry.domain.interactor.deviceEntryFaceAuthInteractor
import com.android.systemui.flags.featureFlagsClassic
import com.android.systemui.keyguard.data.repository.keyguardRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.shade.pulsingGestureListener

val Kosmos.keyguardTouchHandlingInteractor by
    Kosmos.Fixture {
        KeyguardTouchHandlingInteractor(
            appContext = applicationContext,
            scope = applicationCoroutineScope,
            transitionInteractor = keyguardTransitionInteractor,
            repository = keyguardRepository,
            logger = uiEventLogger,
            featureFlags = featureFlagsClassic,
            broadcastDispatcher = broadcastDispatcher,
            accessibilityManager = accessibilityManagerWrapper,
            pulsingGestureListener = pulsingGestureListener,
            faceAuthInteractor = deviceEntryFaceAuthInteractor,
        )
    }
