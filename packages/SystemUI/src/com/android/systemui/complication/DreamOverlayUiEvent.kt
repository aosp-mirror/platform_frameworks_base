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

package com.android.systemui.complication

import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger.UiEventEnum

/** UI log events for the dream overlay. */
enum class DreamOverlayUiEvent(private val mId: Int) : UiEventEnum {
    @UiEvent(doc = "The home controls on the screensaver has been tapped.")
    DREAM_HOME_CONTROLS_TAPPED(1212),
    @UiEvent(doc = "The weather on the screensaver has been tapped") DREAM_WEATHER_TAPPED(1441);

    override fun getId(): Int {
        return mId
    }
}
