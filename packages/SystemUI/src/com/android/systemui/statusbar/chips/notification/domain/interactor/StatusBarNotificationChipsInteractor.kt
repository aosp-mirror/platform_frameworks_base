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

package com.android.systemui.statusbar.chips.notification.domain.interactor

import android.annotation.SuppressLint
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** An interactor for the notification chips shown in the status bar. */
@SysUISingleton
class StatusBarNotificationChipsInteractor @Inject constructor() {

    // Each chip tap is an individual event, *not* a state, which is why we're using SharedFlow not
    // StateFlow. There shouldn't be multiple updates per frame, which should avoid performance
    // problems.
    @SuppressLint("SharedFlowCreation")
    private val _promotedNotificationChipTapEvent = MutableSharedFlow<String>()

    /**
     * SharedFlow that emits each time a promoted notification's status bar chip is tapped. The
     * emitted value is the promoted notification's key.
     */
    val promotedNotificationChipTapEvent: SharedFlow<String> =
        _promotedNotificationChipTapEvent.asSharedFlow()

    suspend fun onPromotedNotificationChipTapped(key: String) {
        StatusBarNotifChips.assertInNewMode()
        _promotedNotificationChipTapEvent.emit(key)
    }
}
