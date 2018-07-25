/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.packageinstaller.permission.utils;

import android.metrics.LogMaker;
import androidx.annotation.NonNull;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

public class EventLogger {
    private static final MetricsLogger sMetricsLogger = new MetricsLogger();

    /**
     * Log that a permission was requested/denied.
     *
     * @param action the action performed
     * @param name name of the permission
     * @param packageName package permission is for
     */
    public static void logPermission(int action, @NonNull String name,
            @NonNull String packageName) {
        final LogMaker log = new LogMaker(action);
        log.setPackageName(packageName);
        log.addTaggedData(MetricsEvent.FIELD_PERMISSION, name);

        sMetricsLogger.write(log);
    }
}
