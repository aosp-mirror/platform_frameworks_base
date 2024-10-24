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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.notification.stack.domain.interactor

import android.content.applicationContext
import com.android.systemui.common.ui.domain.interactor.configurationInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceEntryUdfpsInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.shade.largeScreenHeaderHelper
import com.android.systemui.statusbar.policy.splitShadeStateController
import kotlinx.coroutines.ExperimentalCoroutinesApi

val Kosmos.sharedNotificationContainerInteractor by
    Kosmos.Fixture {
        SharedNotificationContainerInteractor(
            context = applicationContext,
            splitShadeStateController = { splitShadeStateController },
            shadeInteractor = { shadeInteractor },
            configurationInteractor = configurationInteractor,
            keyguardInteractor = keyguardInteractor,
            deviceEntryUdfpsInteractor = deviceEntryUdfpsInteractor,
            largeScreenHeaderHelperLazy = { largeScreenHeaderHelper },
        )
    }
