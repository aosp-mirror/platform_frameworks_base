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

import com.android.internal.annotations.GuardedBy
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.internal.logging.UiEventLogger.UiEventEnum
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject

/**
 * Handles logging UiEvent stats for simple UI interactions.
 *
 * See go/uievent
 */
class AccessibilityLogger
@Inject
constructor(private val uiEventLogger: UiEventLogger, private val clock: SystemClock) {

    @GuardedBy("clock") private var lastTimeThrottledMs: Long = 0
    @GuardedBy("clock") private var lastEventThrottled: UiEventEnum? = null

    /**
     * Logs the event, but any additional calls within the given delay window are ignored. The
     * window resets every time a new event is received. i.e. it will only log one time until you
     * wait at least [delayBeforeLoggingMs] before sending the next event.
     *
     * <p>Additionally, if a different type of event is passed in, the delay window for the previous
     * one is forgotten. e.g. if you send two types of events interlaced all within the delay
     * window, e.g. A->B->A within 1000ms, all three will be logged.
     */
    @JvmOverloads fun logThrottled(event: UiEventEnum, delayBeforeLoggingMs: Int = 2000) {
        synchronized(clock) {
            val currentTimeMs = clock.elapsedRealtime()
            val shouldThrottle =
                event == lastEventThrottled &&
                    currentTimeMs - lastTimeThrottledMs < delayBeforeLoggingMs
            lastEventThrottled = event
            lastTimeThrottledMs = currentTimeMs
            if (shouldThrottle) {
                return
            }
        }
        log(event)
    }

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

        @UiEvent(doc = "Magnification settings panel zoom slider changed")
        MAGNIFICATION_SETTINGS_ZOOM_SLIDER_CHANGED(1385),

        @UiEvent(
            doc =
                "Magnification settings panel window size selected. The selection rank is " +
                    "which size was selected."
        )
        MAGNIFICATION_SETTINGS_WINDOW_SIZE_SELECTED(1386);

        override fun getId(): Int = this.id
    }
}
