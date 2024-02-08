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

package com.android.systemui.user.domain.interactor

import android.app.activityManager
import android.content.applicationContext
import android.os.userManager
import com.android.internal.logging.uiEventLogger
import com.android.keyguard.keyguardUpdateMonitor
import com.android.systemui.broadcast.broadcastDispatcher
import com.android.systemui.flags.featureFlagsClassic
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.plugins.activityStarter
import com.android.systemui.process.processWrapper
import com.android.systemui.telephony.domain.interactor.telephonyInteractor
import com.android.systemui.user.data.repository.userRepository
import com.android.systemui.utils.userRestrictionChecker

val Kosmos.userSwitcherInteractor by
    Kosmos.Fixture {
        UserSwitcherInteractor(
            applicationContext = applicationContext,
            repository = userRepository,
            activityStarter = activityStarter,
            keyguardInteractor = keyguardInteractor,
            featureFlags = featureFlagsClassic,
            manager = userManager,
            headlessSystemUserMode = headlessSystemUserMode,
            applicationScope = applicationCoroutineScope,
            telephonyInteractor = telephonyInteractor,
            broadcastDispatcher = broadcastDispatcher,
            keyguardUpdateMonitor = keyguardUpdateMonitor,
            backgroundDispatcher = testDispatcher,
            mainDispatcher = testDispatcher,
            activityManager = activityManager,
            refreshUsersScheduler = refreshUsersScheduler,
            guestUserInteractor = guestUserInteractor,
            uiEventLogger = uiEventLogger,
            userRestrictionChecker = userRestrictionChecker,
            processWrapper = processWrapper,
        )
    }
