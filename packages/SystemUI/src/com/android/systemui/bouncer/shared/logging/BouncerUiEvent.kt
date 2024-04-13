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

package com.android.systemui.bouncer.shared.logging

import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger

/**
 * Legacy bouncer UI events {@link com.android.keyguard.KeyguardSecurityContainer.BouncerUiEvent}.
 * Only contains that used by metrics.
 */
enum class BouncerUiEvent(private val _id: Int) : UiEventLogger.UiEventEnum {
    @UiEvent(doc = "Bouncer is dismissed using extended security access.")
    BOUNCER_DISMISS_EXTENDED_ACCESS(413),

    // PASSWORD here includes password, pattern, and pin.
    @UiEvent(doc = "Bouncer is successfully unlocked using password.")
    BOUNCER_PASSWORD_SUCCESS(418),
    @UiEvent(doc = "An attempt to unlock bouncer using password has failed.")
    BOUNCER_PASSWORD_FAILURE(419);

    override fun getId() = _id
}
