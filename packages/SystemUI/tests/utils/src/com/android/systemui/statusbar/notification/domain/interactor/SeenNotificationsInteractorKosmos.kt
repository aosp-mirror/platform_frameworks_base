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

package com.android.systemui.statusbar.notification.domain.interactor

import android.os.UserHandle
import android.provider.Settings
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.statusbar.notification.data.repository.activeNotificationListRepository
import com.android.systemui.util.settings.fakeSettings

val Kosmos.seenNotificationsInteractor by Fixture {
    SeenNotificationsInteractor(
        bgDispatcher = testDispatcher,
        notificationListRepository = activeNotificationListRepository,
        secureSettings = fakeSettings,
    )
}

var Kosmos.lockScreenShowOnlyUnseenNotificationsSetting: Boolean
    get() =
        fakeSettings.getIntForUser(
            Settings.Secure.LOCK_SCREEN_SHOW_ONLY_UNSEEN_NOTIFICATIONS,
            UserHandle.USER_CURRENT,
        ) == 1
    set(value) {
        fakeSettings.putIntForUser(
            Settings.Secure.LOCK_SCREEN_SHOW_ONLY_UNSEEN_NOTIFICATIONS,
            if (value) 1 else 2,
            UserHandle.USER_CURRENT,
        )
    }
