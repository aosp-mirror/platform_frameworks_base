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

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.util.Pair;
import android.util.StatsLog;
import android.view.WindowManager.LayoutParams;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

/**
 * Used to wrap different logging calls in one, so that client side code base is clean and more
 * readable.
 */
public class MetricsLoggerWrapper {

    private static final int METRIC_VALUE_DISMISSED_BY_TAP = 0;
    private static final int METRIC_VALUE_DISMISSED_BY_DRAG = 1;

    public static void logPictureInPictureDismissByTap(Context context,
            Pair<ComponentName, Integer> topActivityInfo) {
        MetricsLogger.action(context, MetricsEvent.ACTION_PICTURE_IN_PICTURE_DISMISSED,
                METRIC_VALUE_DISMISSED_BY_TAP);
        StatsLog.write(StatsLog.PICTURE_IN_PICTURE_STATE_CHANGED,
                getUid(context, topActivityInfo.first, topActivityInfo.second),
                topActivityInfo.first.flattenToString(),
                StatsLog.PICTURE_IN_PICTURE_STATE_CHANGED__STATE__DISMISSED);
    }

    public static void logPictureInPictureDismissByDrag(Context context,
            Pair<ComponentName, Integer> topActivityInfo) {
        MetricsLogger.action(context,
                MetricsEvent.ACTION_PICTURE_IN_PICTURE_DISMISSED,
                METRIC_VALUE_DISMISSED_BY_DRAG);
        StatsLog.write(StatsLog.PICTURE_IN_PICTURE_STATE_CHANGED,
                getUid(context, topActivityInfo.first, topActivityInfo.second),
                topActivityInfo.first.flattenToString(),
                StatsLog.PICTURE_IN_PICTURE_STATE_CHANGED__STATE__DISMISSED);
    }

    public static void logPictureInPictureMinimize(Context context, boolean isMinimized,
            Pair<ComponentName, Integer> topActivityInfo) {
        MetricsLogger.action(context, MetricsEvent.ACTION_PICTURE_IN_PICTURE_MINIMIZED,
                isMinimized);
        StatsLog.write(StatsLog.PICTURE_IN_PICTURE_STATE_CHANGED,
                getUid(context, topActivityInfo.first, topActivityInfo.second),
                topActivityInfo.first.flattenToString(),
                StatsLog.PICTURE_IN_PICTURE_STATE_CHANGED__STATE__MINIMIZED);
    }

    /**
     * Get uid from component name and user Id
     * @return uid. -1 if not found.
     */
    private static int getUid(Context context, ComponentName componentName, int userId) {
        int uid = -1;
        if (componentName == null) {
            return uid;
        }
        try {
            uid = context.getPackageManager().getApplicationInfoAsUser(
                    componentName.getPackageName(), 0, userId).uid;
        } catch (NameNotFoundException e) {
        }
        return uid;
    }

    public static void logPictureInPictureMenuVisible(Context context, boolean menuStateFull) {
        MetricsLogger.visibility(context, MetricsEvent.ACTION_PICTURE_IN_PICTURE_MENU,
                menuStateFull);
    }

    public static void logPictureInPictureEnter(Context context,
            int uid, String shortComponentName, boolean supportsEnterPipOnTaskSwitch) {
        MetricsLogger.action(context, MetricsEvent.ACTION_PICTURE_IN_PICTURE_ENTERED,
                supportsEnterPipOnTaskSwitch);
        StatsLog.write(StatsLog.PICTURE_IN_PICTURE_STATE_CHANGED, uid,
                shortComponentName,
                StatsLog.PICTURE_IN_PICTURE_STATE_CHANGED__STATE__ENTERED);
    }

    public static void logPictureInPictureFullScreen(Context context, int uid,
            String shortComponentName) {
        MetricsLogger.action(context,
                MetricsEvent.ACTION_PICTURE_IN_PICTURE_EXPANDED_TO_FULLSCREEN);
        StatsLog.write(StatsLog.PICTURE_IN_PICTURE_STATE_CHANGED,
                uid,
                shortComponentName,
                StatsLog.PICTURE_IN_PICTURE_STATE_CHANGED__STATE__EXPANDED_TO_FULL_SCREEN);
    }

    public static void logAppOverlayEnter(int uid, String packageName, boolean changed, int type, boolean usingAlertWindow) {
        if (changed) {
            if (type != LayoutParams.TYPE_APPLICATION_OVERLAY) {
                StatsLog.write(StatsLog.OVERLAY_STATE_CHANGED, uid, packageName, true,
                        StatsLog.OVERLAY_STATE_CHANGED__STATE__ENTERED);
            } else if (!usingAlertWindow){
                StatsLog.write(StatsLog.OVERLAY_STATE_CHANGED, uid, packageName, false,
                        StatsLog.OVERLAY_STATE_CHANGED__STATE__ENTERED);
            }
        }
    }

    public static void logAppOverlayExit(int uid, String packageName, boolean changed, int type, boolean usingAlertWindow) {
        if (changed) {
            if (type != LayoutParams.TYPE_APPLICATION_OVERLAY) {
                StatsLog.write(StatsLog.OVERLAY_STATE_CHANGED, uid, packageName, true,
                        StatsLog.OVERLAY_STATE_CHANGED__STATE__EXITED);
            } else if (!usingAlertWindow){
                StatsLog.write(StatsLog.OVERLAY_STATE_CHANGED, uid, packageName, false,
                        StatsLog.OVERLAY_STATE_CHANGED__STATE__EXITED);
            }
        }
    }
}
