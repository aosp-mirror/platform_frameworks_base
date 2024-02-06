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
 *
 */

package com.android.systemui.statusbar.notification.stack.domain.interactor

import com.android.systemui.common.shared.model.NotificationContainerBounds
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.notification.stack.data.repository.NotificationStackAppearanceRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An interactor which controls the appearance of the NSSL */
@SysUISingleton
class NotificationStackAppearanceInteractor
@Inject
constructor(
    private val repository: NotificationStackAppearanceRepository,
) {
    /** The bounds of the notification stack in the current scene. */
    val stackBounds: StateFlow<NotificationContainerBounds> = repository.stackBounds.asStateFlow()

    /**
     * The height in px of the contents of notification stack. Depending on the number of
     * notifications, this can exceed the space available on screen to show notifications, at which
     * point the notification stack should become scrollable.
     */
    val intrinsicContentHeight: StateFlow<Float> = repository.intrinsicContentHeight.asStateFlow()

    /** The y-coordinate in px of top of the contents of the notification stack. */
    val contentTop: StateFlow<Float> = repository.contentTop.asStateFlow()

    /**
     * Whether the notification stack is scrolled to the top; i.e., it cannot be scrolled down any
     * further.
     */
    val scrolledToTop: StateFlow<Boolean> = repository.scrolledToTop.asStateFlow()

    /** Sets the position of the notification stack in the current scene. */
    fun setStackBounds(bounds: NotificationContainerBounds) {
        check(bounds.top <= bounds.bottom) { "Invalid bounds: $bounds" }
        repository.stackBounds.value = bounds
    }

    /** Sets the height of the contents of the notification stack. */
    fun setIntrinsicContentHeight(height: Float) {
        repository.intrinsicContentHeight.value = height
    }

    /** Sets the y-coord in px of the top of the contents of the notification stack. */
    fun setContentTop(startY: Float) {
        repository.contentTop.value = startY
    }

    /** Sets whether the notification stack is scrolled to the top. */
    fun setScrolledToTop(scrolledToTop: Boolean) {
        repository.scrolledToTop.value = scrolledToTop
    }
}
