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

package com.android.systemui.screenshot;

import static com.android.systemui.screenshot.LogConfig.DEBUG_ACTIONS;
import static com.android.systemui.screenshot.LogConfig.logTag;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.UserHandle;
import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * This class can be overridden by a vendor-specific sys UI implementation,
 * in order to provide smart actions in the screenshot notification.
 */
public class ScreenshotNotificationSmartActionsProvider {

    private static final String TAG = logTag(ScreenshotNotificationSmartActionsProvider.class);

    /* Key provided in the notification action to get the type of smart action. */
    public static final String ACTION_TYPE = "action_type";
    public static final String DEFAULT_ACTION_TYPE = "Smart Action";

    /* Define phases of screenshot execution. */
    public enum ScreenshotOp {
        OP_UNKNOWN,
        RETRIEVE_SMART_ACTIONS,
        REQUEST_SMART_ACTIONS,
        WAIT_FOR_SMART_ACTIONS
    }

    /* Enum to report success or failure for screenshot execution phases. */
    public enum ScreenshotOpStatus {
        OP_STATUS_UNKNOWN,
        SUCCESS,
        ERROR,
        TIMEOUT
    }

    /* Enum to define screenshot smart action types. */
    public enum ScreenshotSmartActionType {
        REGULAR_SMART_ACTIONS,
        QUICK_SHARE_ACTION
    }

    /**
     * Default implementation that returns an empty list.
     * This method is overridden in vendor-specific Sys UI implementation.
     *
     * @param screenshotId       a unique id for the screenshot
     * @param screenshotUri      uri where the screenshot has been stored
     * @param bitmap             the screenshot, config must be {@link Bitmap.Config#HARDWARE}
     * @param componentName      name of the foreground component when the screenshot was taken
     * @param userHandle         user handle of the foreground task owner
     */
    public CompletableFuture<List<Notification.Action>> getActions(String screenshotId,
            Uri screenshotUri, Bitmap bitmap, ComponentName componentName,
            ScreenshotSmartActionType actionType, UserHandle userHandle) {
        if (DEBUG_ACTIONS) {
            Log.d(TAG, "Returning empty smart action list.");
        }
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    /**
     * Notify exceptions and latency encountered during generating smart actions.
     * This method is overridden in vendor-specific Sys UI implementation.
     *
     * @param screenshotId unique id of the screenshot.
     * @param op           screenshot execution phase defined in {@link ScreenshotOp}
     * @param status       {@link ScreenshotOpStatus} to report success or failure.
     * @param durationMs   latency experienced in different phases of screenshots.
     */
    public void notifyOp(String screenshotId, ScreenshotOp op, ScreenshotOpStatus status,
            long durationMs) {
        if (DEBUG_ACTIONS) {
            Log.d(TAG, "SmartActions: notifyOp() - return without notify");
        }
    }

    /**
     * Notify screenshot notification action invoked.
     * This method is overridden in vendor-specific Sys UI implementation.
     *
     * @param screenshotId  Unique id of the screenshot.
     * @param action        type of notification action invoked.
     * @param isSmartAction whether action invoked was a smart action.
     */
    public void notifyAction(String screenshotId, String action, boolean isSmartAction,
            Intent intent) {
        if (DEBUG_ACTIONS) {
            Log.d(TAG, "SmartActions: notifyAction: return without notify");
        }
    }
}
