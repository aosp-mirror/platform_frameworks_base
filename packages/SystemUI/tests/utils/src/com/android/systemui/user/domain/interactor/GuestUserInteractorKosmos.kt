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

import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.context
import com.android.systemui.kosmos.lifecycleScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.userManager
import com.android.systemui.user.data.repository.userRepository
import com.android.systemui.util.mockito.mock

val Kosmos.guestUserInteractor by
    Kosmos.Fixture {
        GuestUserInteractor(
            applicationContext = context,
            applicationScope = lifecycleScope,
            mainDispatcher = testDispatcher,
            backgroundDispatcher = testDispatcher,
            manager = userManager,
            deviceProvisionedController = mock(),
            repository = userRepository,
            devicePolicyManager = mock(),
            refreshUsersScheduler = refreshUsersScheduler,
            uiEventLogger = mock(),
            resumeSessionReceiver = mock(),
            resetOrExitSessionReceiver = mock(),
        )
    }
