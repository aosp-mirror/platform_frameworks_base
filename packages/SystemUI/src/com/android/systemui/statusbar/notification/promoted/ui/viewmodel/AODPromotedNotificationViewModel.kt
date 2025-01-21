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

import androidx.compose.runtime.getValue
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.statusbar.notification.promoted.domain.interactor.AODPromotedNotificationInteractor
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

class AODPromotedNotificationViewModel
@AssistedInject
constructor(interactor: AODPromotedNotificationInteractor) : ExclusiveActivatable() {
    private val hydrator = Hydrator("AODPromotedNotificationViewModel.hydrator")

    val content: PromotedNotificationContentModel? by
        hydrator.hydratedStateOf(
            traceName = "content",
            initialValue = null,
            source = interactor.content,
        )

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
    }

    @AssistedFactory
    interface Factory {
        fun create(): AODPromotedNotificationViewModel
    }
}
