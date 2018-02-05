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

import android.content.Context;
import android.util.StatsLog;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

/**
 * Used to wrap different logging calls in one, so that client side code base is clean and more
 * readable.
 */
public class MetricsLoggerWrapper {

    private static final int METRIC_VALUE_DISMISSED_BY_TAP = 0;
    private static final int METRIC_VALUE_DISMISSED_BY_DRAG = 1;

    public static void logPictureInPictureDismissByTap(Context context) {
        MetricsLogger.action(context, MetricsEvent.ACTION_PICTURE_IN_PICTURE_DISMISSED,
                METRIC_VALUE_DISMISSED_BY_TAP);
        StatsLog.write(StatsLog.PICTURE_IN_PICTURE_STATE_CHANGED,
                context.getUserId(),
                context.getApplicationInfo().packageName,
                context.getApplicationInfo().className,
                StatsLog.PICTURE_IN_PICTURE_STATE_CHANGED__STATE__DISMISSED);
    }

    public static void logPictureInPictureDismissByDrag(Context context) {
        MetricsLogger.action(context,
                MetricsEvent.ACTION_PICTURE_IN_PICTURE_DISMISSED,
                METRIC_VALUE_DISMISSED_BY_DRAG);
        StatsLog.write(StatsLog.PICTURE_IN_PICTURE_STATE_CHANGED,
                context.getUserId(),
                context.getApplicationInfo().packageName,
                context.getApplicationInfo().className,
                StatsLog.PICTURE_IN_PICTURE_STATE_CHANGED__STATE__DISMISSED);
    }

    public static void logPictureInPictureMinimize(Context context, boolean isMinimized) {
        MetricsLogger.action(context, MetricsEvent.ACTION_PICTURE_IN_PICTURE_MINIMIZED,
                isMinimized);
        if (isMinimized) {
            StatsLog.write(StatsLog.PICTURE_IN_PICTURE_STATE_CHANGED,
                    context.getUserId(),
                    context.getApplicationInfo().packageName,
                    context.getApplicationInfo().className,
                    StatsLog.PICTURE_IN_PICTURE_STATE_CHANGED__STATE__MINIMIZED);
        } else {
            StatsLog.write(StatsLog.PICTURE_IN_PICTURE_STATE_CHANGED,
                    context.getUserId(),
                    context.getApplicationInfo().packageName,
                    context.getApplicationInfo().className,
                    StatsLog.PICTURE_IN_PICTURE_STATE_CHANGED__STATE__EXPANDED_TO_FULL_SCREEN);
        }
    }

    public static void logPictureInPictureMenuVisible(Context context, boolean menuStateFull) {
        MetricsLogger.visibility(context, MetricsEvent.ACTION_PICTURE_IN_PICTURE_MENU,
                menuStateFull);
    }

    public static void logPictureInPictureEnter(Context context,
            boolean supportsEnterPipOnTaskSwitch) {
        MetricsLogger.action(context, MetricsEvent.ACTION_PICTURE_IN_PICTURE_ENTERED,
                supportsEnterPipOnTaskSwitch);
        if (supportsEnterPipOnTaskSwitch) {
            StatsLog.write(StatsLog.PICTURE_IN_PICTURE_STATE_CHANGED, context.getUserId(),
                    context.getApplicationInfo().packageName,
                    context.getApplicationInfo().className,
                    StatsLog.PICTURE_IN_PICTURE_STATE_CHANGED__STATE__ENTERED);
        }
    }

    public static void logPictureInPictureFullScreen(Context context) {
        MetricsLogger.action(context,
                MetricsEvent.ACTION_PICTURE_IN_PICTURE_EXPANDED_TO_FULLSCREEN);
        StatsLog.write(StatsLog.PICTURE_IN_PICTURE_STATE_CHANGED,
                context.getUserId(),
                context.getApplicationInfo().packageName,
                context.getApplicationInfo().className,
                StatsLog.PICTURE_IN_PICTURE_STATE_CHANGED__STATE__EXPANDED_TO_FULL_SCREEN);
    }

    public static void logAppOverlayEnter(int uid, String packageName, boolean usingAlertWindow) {
        StatsLog.write(StatsLog.OVERLAY_STATE_CHANGED, uid, packageName, usingAlertWindow,
                StatsLog.OVERLAY_STATE_CHANGED__STATE__ENTERED);
    }

    public static void logAppOverlayExit(int uid, String packageName, boolean usingAlertWindow) {
        StatsLog.write(StatsLog.OVERLAY_STATE_CHANGED, uid, packageName, usingAlertWindow,
                StatsLog.OVERLAY_STATE_CHANGED__STATE__EXITED);
    }
}
