/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.logging;

import com.android.internal.util.FrameworkStatsLog;

/**
 * Standard implementation of UiEventLogger, writing to FrameworkStatsLog.
 *
 * See UiEventReported atom in atoms.proto for more context.
 */
public class UiEventLoggerImpl implements UiEventLogger {
    @Override
    public void log(UiEventEnum event) {
        log(event, 0, null);
    }

    @Override
    public void log(UiEventEnum event, int uid, String packageName) {
        final int eventID = event.getId();
        if (eventID > 0) {
            FrameworkStatsLog.write(FrameworkStatsLog.UI_EVENT_REPORTED, eventID, uid, packageName);
        }
    }

    @Override
    public void logWithInstanceId(UiEventEnum event, int uid, String packageName,
            InstanceId instance) {
        final int eventID = event.getId();
        if ((eventID > 0)  && (instance != null)) {
            FrameworkStatsLog.write(FrameworkStatsLog.UI_EVENT_REPORTED, eventID, uid, packageName,
                    instance.getId());
        } else {
            log(event, uid, packageName);
        }
    }
}
