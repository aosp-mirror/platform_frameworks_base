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

package com.android.systemui.statusbar.policy.domain.interactor

import android.content.testableContext
import com.android.settingslib.notification.modes.zenIconLoader
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.shared.notifications.data.repository.notificationSettingsRepository
import com.android.systemui.statusbar.policy.data.repository.deviceProvisioningRepository
import com.android.systemui.statusbar.policy.data.repository.userSetupRepository
import com.android.systemui.statusbar.policy.data.repository.zenModeRepository

val Kosmos.zenModeInteractor by Fixture {
    ZenModeInteractor(
        context = testableContext,
        zenModeRepository = zenModeRepository,
        notificationSettingsRepository = notificationSettingsRepository,
        bgDispatcher = testDispatcher,
        iconLoader = zenIconLoader,
        deviceProvisioningRepository = deviceProvisioningRepository,
        userSetupRepository = userSetupRepository,
    )
}
