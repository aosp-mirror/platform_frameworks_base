/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.internal.os.logging;

import android.view.WindowManager.LayoutParams;

import com.android.internal.util.FrameworkStatsLog;

/**
 * Used to wrap different logging calls in one, so that client side code base is clean and more
 * readable.
 */
public class MetricsLoggerWrapper {

    public static void logAppOverlayEnter(int uid, String packageName, boolean changed, int type, boolean usingAlertWindow) {
        if (changed) {
            if (type != LayoutParams.TYPE_APPLICATION_OVERLAY) {
                FrameworkStatsLog.write(FrameworkStatsLog.OVERLAY_STATE_CHANGED, uid, packageName,
                        true, FrameworkStatsLog.OVERLAY_STATE_CHANGED__STATE__ENTERED);
            } else if (!usingAlertWindow){
                FrameworkStatsLog.write(FrameworkStatsLog.OVERLAY_STATE_CHANGED, uid, packageName,
                        false, FrameworkStatsLog.OVERLAY_STATE_CHANGED__STATE__ENTERED);
            }
        }
    }

    public static void logAppOverlayExit(int uid, String packageName, boolean changed, int type, boolean usingAlertWindow) {
        if (changed) {
            if (type != LayoutParams.TYPE_APPLICATION_OVERLAY) {
                FrameworkStatsLog.write(FrameworkStatsLog.OVERLAY_STATE_CHANGED, uid, packageName,
                        true, FrameworkStatsLog.OVERLAY_STATE_CHANGED__STATE__EXITED);
            } else if (!usingAlertWindow){
                FrameworkStatsLog.write(FrameworkStatsLog.OVERLAY_STATE_CHANGED, uid, packageName,
                        false, FrameworkStatsLog.OVERLAY_STATE_CHANGED__STATE__EXITED);
            }
        }
    }
}
