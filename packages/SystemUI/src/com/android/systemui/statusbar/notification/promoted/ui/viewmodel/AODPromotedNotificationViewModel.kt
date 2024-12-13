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

package com.android.systemui.statusbar.notification.promoted.ui.viewmodel

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.promoted.domain.interactor.AODPromotedNotificationInteractor
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel.Identity
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

@SysUISingleton
class AODPromotedNotificationViewModel
@Inject
constructor(interactor: AODPromotedNotificationInteractor) {
    private val content: Flow<PromotedNotificationContentModel?> = interactor.content
    private val identity: Flow<Identity?> = content.mapNonNullsKeepingNulls { it.identity }

    val notification: Flow<PromotedNotificationViewModel?> =
        identity.distinctUntilChanged().mapNonNullsKeepingNulls { identity ->
            val updates = interactor.content.filterNotNull().filter { it.identity == identity }
            PromotedNotificationViewModel(identity, updates)
        }
}

private fun <T, R> Flow<T?>.mapNonNullsKeepingNulls(block: (T) -> R): Flow<R?> = map {
    it?.let(block)
}
