/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package com.android.systemui.statusbar.notification.row.domain.interactor

import com.android.systemui.statusbar.notification.row.data.repository.NotificationRowRepository
import com.android.systemui.statusbar.notification.row.shared.TimerContentModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance

/** Interactor specific to a particular notification row. */
class NotificationRowInteractor @Inject constructor(repository: NotificationRowRepository) {
    /** Content of a rich ongoing timer notification. */
    val timerContentModel: Flow<TimerContentModel> =
        repository.richOngoingContentModel.filterIsInstance<TimerContentModel>()
}
