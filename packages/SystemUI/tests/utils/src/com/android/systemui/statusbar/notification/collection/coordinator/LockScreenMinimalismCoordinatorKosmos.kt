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

package com.android.systemui.statusbar.notification.collection.coordinator

import com.android.systemui.dump.dumpManager
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.statusbar.statusBarStateController
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.statusbar.notification.domain.interactor.seenNotificationsInteractor
import com.android.systemui.statusbar.notification.stack.domain.interactor.headsUpNotificationInteractor

var Kosmos.lockScreenMinimalismCoordinator by
    Kosmos.Fixture {
        LockScreenMinimalismCoordinator(
            dumpManager = dumpManager,
            headsUpInteractor = headsUpNotificationInteractor,
            logger = lockScreenMinimalismCoordinatorLogger,
            scope = testScope.backgroundScope,
            seenNotificationsInteractor = seenNotificationsInteractor,
            statusBarStateController = statusBarStateController,
            shadeInteractor = shadeInteractor,
        )
    }
