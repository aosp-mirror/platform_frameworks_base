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
import com.android.systemui.scene.shared.flag.SceneContainerFlags
import com.android.systemui.statusbar.notification.stack.domain.interactor.NotificationStackAppearanceInteractor
import com.android.systemui.statusbar.notification.stack.shared.flexiNotifsEnabled
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel used by the Notification placeholders inside the scene container to update the
 * [NotificationStackAppearanceInteractor], and by extension control the NSSL.
 */
@SysUISingleton
class NotificationsPlaceholderViewModel
@Inject
constructor(
    private val interactor: NotificationStackAppearanceInteractor,
    flags: SceneContainerFlags,
    featureFlags: FeatureFlagsClassic,
) {
    /** DEBUG: whether the placeholder "Notifications" text should be shown. */
    val isPlaceholderTextVisible: Boolean = !flags.flexiNotifsEnabled()

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
        interactor.setStackBounds(NotificationContainerBounds(left, top, right, bottom))
    }

    /** The corner radius of the placeholder, in dp. */
    val cornerRadiusDp: StateFlow<Float> = interactor.cornerRadiusDp
}
