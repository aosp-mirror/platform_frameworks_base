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

import com.android.internal.logging.UiEventLogger;
import com.android.internal.util.FrameworkStatsLog;

/**
 * Standard implementation of DreamUiEventLogger, writing to FrameworkStatsLog.
 *
 * See DreamUiEventReported atom in atoms.proto for more context.
 * @hide
 */
public class DreamUiEventLoggerImpl implements DreamUiEventLogger {
    private final String[] mLoggableDreamPrefixes;

    DreamUiEventLoggerImpl(String[] loggableDreamPrefixes) {
        mLoggableDreamPrefixes = loggableDreamPrefixes;
    }

    @Override
    public void log(UiEventLogger.UiEventEnum event, String dreamComponentName) {
        final int eventID = event.getId();
        if (eventID <= 0) {
            return;
        }
        FrameworkStatsLog.write(FrameworkStatsLog.DREAM_UI_EVENT_REPORTED,
                /* uid = 1 */ 0,
                /* event_id = 2 */ eventID,
                /* instance_id = 3 */ 0,
                /* dream_component_name = 4 */
                isFirstPartyDream(dreamComponentName) ? dreamComponentName : "other");
    }

    private boolean isFirstPartyDream(String dreamComponentName) {
        for (int i = 0; i < mLoggableDreamPrefixes.length; ++i) {
            if (dreamComponentName.startsWith(mLoggableDreamPrefixes[i])) {
                return true;
            }
        }
        return false;
    }
}
