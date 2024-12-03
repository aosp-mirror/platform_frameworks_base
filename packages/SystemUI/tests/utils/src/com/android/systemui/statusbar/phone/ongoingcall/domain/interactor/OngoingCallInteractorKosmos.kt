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

package com.android.systemui.statusbar.phone.ongoingcall.domain.interactor

import com.android.systemui.activity.data.repository.activityManagerRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.statusbar.notification.domain.interactor.activeNotificationsInteractor

val Kosmos.ongoingCallInteractor: OngoingCallInteractor by
    Kosmos.Fixture {
      OngoingCallInteractor(
          scope = applicationCoroutineScope,
          activeNotificationsInteractor = activeNotificationsInteractor,
          activityManagerRepository = activityManagerRepository,
          logBuffer = logcatLogBuffer("OngoingCallInteractorKosmos"),
      )
    }
