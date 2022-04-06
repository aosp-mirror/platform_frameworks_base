/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.dreams;

import android.annotation.NonNull;

import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;

/**
 * Logging interface for Dream UI events. Normal implementation is DreamUiEventLoggerImpl.
 *
 * See DreamUiEventReported atom in atoms.proto for more context.
 * @hide
 */
public interface DreamUiEventLogger {
    /** Put your Event IDs in enums that implement this interface, and document them using the
     * UiEventEnum annotation.
     * Event IDs must be globally unique. This will be enforced by tooling (forthcoming).
     * OEMs should use event IDs above 100000 and below 1000000 (1 million).
     */
    enum DreamUiEventEnum implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "The screensaver has started.")
        DREAM_START(577),

        @UiEvent(doc = "The screensaver has stopped.")
        DREAM_STOP(578);

        private final int mId;

        DreamUiEventEnum(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    /**
     * Log a simple event with dream component name, with no package information. Does nothing if
     * event.getId() <= 0.
     * @param event an enum implementing UiEventEnum interface.
     * @param dreamComponentName the component name of the dream in use.
     */
    void log(@NonNull UiEventLogger.UiEventEnum event, @NonNull String dreamComponentName);
}
