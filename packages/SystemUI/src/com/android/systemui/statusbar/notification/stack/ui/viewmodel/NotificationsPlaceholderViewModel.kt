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

package com.android.systemui.statusbar.notification.stack.ui.viewmodel

import com.android.systemui.common.shared.model.NotificationContainerBounds
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.stack.domain.interactor.NotificationStackAppearanceInteractor
import com.android.systemui.statusbar.notification.stack.shared.flexiNotifsEnabled
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * ViewModel used by the Notification placeholders inside the scene container to update the
 * [NotificationStackAppearanceInteractor], and by extension control the NSSL.
 */
@SysUISingleton
class NotificationsPlaceholderViewModel
@Inject
constructor(
    private val interactor: NotificationStackAppearanceInteractor,
    shadeInteractor: ShadeInteractor,
    flags: SceneContainerFlags,
    featureFlags: FeatureFlagsClassic,
    private val keyguardInteractor: KeyguardInteractor,
) {
    /** DEBUG: whether the placeholder "Notifications" text should be shown. */
    val isPlaceholderTextVisible: Boolean =
        !flags.flexiNotifsEnabled() && SceneContainerFlag.isEnabled

    /** DEBUG: whether the placeholder should be made slightly visible for positional debugging. */
    val isVisualDebuggingEnabled: Boolean = featureFlags.isEnabled(Flags.NSSL_DEBUG_LINES)

    /** DEBUG: whether the debug logging should be output. */
    val isDebugLoggingEnabled: Boolean = flags.flexiNotifsEnabled()

    /**
     * Notifies that the bounds of the notification placeholder have changed.
     *
     * @param top The position of the top of the container in its window coordinate system, in
     *   pixels.
     * @param bottom The position of the bottom of the container in its window coordinate system, in
     *   pixels.
     */
    fun onBoundsChanged(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        val notificationContainerBounds =
            NotificationContainerBounds(top = top, bottom = bottom, left = left, right = right)
        keyguardInteractor.setNotificationContainerBounds(notificationContainerBounds)
        interactor.setStackBounds(notificationContainerBounds)
    }

    /**
     * The height in px of the contents of notification stack. Depending on the number of
     * notifications, this can exceed the space available on screen to show notifications, at which
     * point the notification stack should become scrollable.
     */
    val intrinsicContentHeight = interactor.intrinsicContentHeight

    /**
     * The amount [0-1] that the shade has been opened. At 0, the shade is closed; at 1, the shade
     * is open.
     */
    val expandFraction: Flow<Float> = shadeInteractor.shadeExpansion

    /** Sets the y-coord in px of the top of the contents of the notification stack. */
    fun onContentTopChanged(padding: Float) {
        interactor.setContentTop(padding)
    }

    /** Sets whether the notification stack is scrolled to the top. */
    fun setScrolledToTop(scrolledToTop: Boolean) {
        interactor.setScrolledToTop(scrolledToTop)
    }
}
