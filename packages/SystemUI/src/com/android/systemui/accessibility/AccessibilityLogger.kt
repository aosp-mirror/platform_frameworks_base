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

package com.android.systemui.accessibility

import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.internal.logging.UiEventLogger.UiEventEnum
import javax.inject.Inject

/**
 * Handles logging UiEvent stats for simple UI interactions.
 *
 * See go/uievent
 */
class AccessibilityLogger @Inject constructor(private val uiEventLogger: UiEventLogger) {
    /** Logs the given event */
    fun log(event: UiEventEnum) {
        uiEventLogger.log(event)
    }

    /**
     * Logs the given event with an integer rank/position value.
     *
     * @param event the event to log
     * @param position the rank or position value that the user interacted with in the UI
     */
    fun logWithPosition(event: UiEventEnum, position: Int) {
        uiEventLogger.logWithPosition(event, /* uid= */ 0, /* packageName= */ null, position)
    }

    /** Events regarding interaction with the magnifier settings panel */
    enum class MagnificationSettingsEvent constructor(private val id: Int) :
        UiEventEnum {
        @UiEvent(
            doc =
                "Magnification settings panel opened. The selection rank is from which " +
                    "magnifier mode it was opened (fullscreen or window)"
        )
        MAGNIFICATION_SETTINGS_PANEL_OPENED(1381),

        @UiEvent(doc = "Magnification settings panel closed")
        MAGNIFICATION_SETTINGS_PANEL_CLOSED(1382),

        @UiEvent(doc = "Magnification settings panel edit size button clicked")
        MAGNIFICATION_SETTINGS_SIZE_EDITING_ACTIVATED(1383),

        @UiEvent(doc = "Magnification settings panel edit size save button clicked")
        MAGNIFICATION_SETTINGS_SIZE_EDITING_DEACTIVATED(1384),

        @UiEvent(
            doc =
                "Magnification settings panel window size selected. The selection rank is " +
                    "which size was selected."
        )
        MAGNIFICATION_SETTINGS_WINDOW_SIZE_SELECTED(1386);

        override fun getId(): Int = this.id
    }
}
