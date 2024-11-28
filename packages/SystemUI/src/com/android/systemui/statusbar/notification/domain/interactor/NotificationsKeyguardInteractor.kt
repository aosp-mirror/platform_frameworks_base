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

import com.android.systemui.statusbar.notification.data.repository.NotificationsKeyguardViewStateRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/** Domain logic pertaining to notifications on the keyguard. */
class NotificationsKeyguardInteractor
@Inject
constructor(private val repository: NotificationsKeyguardViewStateRepository) {
    /** Are notifications fully hidden from view? */
    val areNotificationsFullyHidden: Flow<Boolean> = repository.areNotificationsFullyHidden

    /** Updates whether notifications are fully hidden from view. */
    fun setNotificationsFullyHidden(fullyHidden: Boolean) {
        repository.areNotificationsFullyHidden.value = fullyHidden
    }
}
