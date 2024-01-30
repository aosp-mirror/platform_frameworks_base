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

import android.app.admin.devicePolicyManager
import android.content.applicationContext
import android.os.userManager
import com.android.internal.logging.uiEventLogger
import com.android.systemui.guestResetOrExitSessionReceiver
import com.android.systemui.guestResumeSessionReceiver
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.statusbar.policy.deviceProvisionedController
import com.android.systemui.user.data.repository.userRepository

val Kosmos.guestUserInteractor by
    Kosmos.Fixture {
        GuestUserInteractor(
            applicationContext = applicationContext,
            applicationScope = applicationCoroutineScope,
            mainDispatcher = testDispatcher,
            backgroundDispatcher = testDispatcher,
            manager = userManager,
            repository = userRepository,
            deviceProvisionedController = deviceProvisionedController,
            devicePolicyManager = devicePolicyManager,
            refreshUsersScheduler = refreshUsersScheduler,
            uiEventLogger = uiEventLogger,
            resumeSessionReceiver = guestResumeSessionReceiver,
            resetOrExitSessionReceiver = guestResetOrExitSessionReceiver,
        )
    }
