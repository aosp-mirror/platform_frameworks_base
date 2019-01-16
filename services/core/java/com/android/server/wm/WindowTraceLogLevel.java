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

package com.android.server.wm;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

@IntDef({
        WindowTraceLogLevel.ALL,
        WindowTraceLogLevel.TRIM,
        WindowTraceLogLevel.CRITICAL,
})
@Retention(RetentionPolicy.SOURCE)
@interface WindowTraceLogLevel{
    /**
     * Logs all elements with maximum amount of information.
     *
     * Used to store the current window manager state when generating a bug report
     */
    int ALL = 0;
    /**
     * Logs all elements but doesn't write all configuration data
     *
     * Default log level for manually activated Winscope traces
     */
    int TRIM = 1;
    /**
     * Logs only visible elements, with the minimum amount of performance overhead
     *
     * Default log level for continuous traces
     */
    int CRITICAL = 2;
}
