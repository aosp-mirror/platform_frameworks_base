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

package com.android.systemui.shade.domain.interactor

import android.content.mockedContext
import android.window.WindowContext
import com.android.systemui.common.ui.data.repository.configurationRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.shade.ShadeDisplayChangeLatencyTracker
import com.android.systemui.shade.ShadeWindowLayoutParams
import com.android.systemui.shade.data.repository.fakeShadeDisplaysRepository
import com.android.systemui.shade.data.repository.shadeExpansionIntent
import com.android.systemui.statusbar.notification.domain.interactor.activeNotificationsInteractor
import com.android.systemui.statusbar.notification.row.notificationRebindingTracker
import com.android.systemui.statusbar.notification.stack.notificationStackRebindingHider
import com.android.systemui.statusbar.policy.configurationController
import java.util.Optional
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

val Kosmos.shadeLayoutParams by Kosmos.Fixture { ShadeWindowLayoutParams.create(mockedContext) }

val Kosmos.mockedWindowContext by
    Kosmos.Fixture {
        mock<WindowContext>().apply {
            whenever(reparentToDisplay(any())).thenAnswer { displayIdParam ->
                whenever(displayId).thenReturn(displayIdParam.arguments[0] as Int)
            }
        }
    }
val Kosmos.mockedShadeDisplayChangeLatencyTracker by
    Kosmos.Fixture { mock<ShadeDisplayChangeLatencyTracker>() }
val Kosmos.shadeDisplaysInteractor by
    Kosmos.Fixture {
        ShadeDisplaysInteractor(
            fakeShadeDisplaysRepository,
            mockedWindowContext,
            configurationRepository,
            testScope.backgroundScope,
            testScope.backgroundScope.coroutineContext,
            mockedShadeDisplayChangeLatencyTracker,
            Optional.of(shadeExpandedStateInteractor),
            shadeExpansionIntent,
            activeNotificationsInteractor,
            notificationRebindingTracker,
            Optional.of(notificationStackRebindingHider),
            configurationController,
        )
    }
