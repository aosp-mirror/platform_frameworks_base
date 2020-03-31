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

import android.app.Notification;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * This class can be overridden by a vendor-specific sys UI implementation,
 * in order to provide smart actions in the screenshot notification.
 */
public class ScreenshotNotificationSmartActionsProvider {
    /* Key provided in the notification action to get the type of smart action. */
    public static final String ACTION_TYPE = "action_type";
    public static final String DEFAULT_ACTION_TYPE = "Smart Action";

    /* Define phases of screenshot execution. */
    protected enum ScreenshotOp {
        OP_UNKNOWN,
        RETRIEVE_SMART_ACTIONS,
        REQUEST_SMART_ACTIONS,
        WAIT_FOR_SMART_ACTIONS
    }

    /* Enum to report success or failure for screenshot execution phases. */
    protected enum ScreenshotOpStatus {
        OP_STATUS_UNKNOWN,
        SUCCESS,
        ERROR,
        TIMEOUT
    }

    private static final String TAG = "ScreenshotActions";

    /**
     * Default implementation that returns an empty list.
     * This method is overridden in vendor-specific Sys UI implementation.
     *
     * @param screenshotId       A generated random unique id for the screenshot.
     * @param screenshotFileName name of the file where the screenshot will be written.
     * @param bitmap             The bitmap of the screenshot. The bitmap config must be {@link
     *                           HARDWARE}.
     * @param componentName      Contains package and activity class names where the screenshot was
     *                           taken. This is used as an additional signal to generate and rank
     *                           more relevant actions.
     * @param isManagedProfile   The screenshot was taken for a work profile app.
     */
    public CompletableFuture<List<Notification.Action>> getActions(
            String screenshotId,
            String screenshotFileName,
            Bitmap bitmap,
            ComponentName componentName,
            boolean isManagedProfile) {
        Log.d(TAG, "Returning empty smart action list.");
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
        Log.d(TAG, "Return without notify.");
    }

    /**
     * Notify screenshot notification action invoked.
     * This method is overridden in vendor-specific Sys UI implementation.
     *
     * @param screenshotId  Unique id of the screenshot.
     * @param action        type of notification action invoked.
     * @param isSmartAction whether action invoked was a smart action.
     */
    public void notifyAction(String screenshotId, String action, boolean isSmartAction) {
        Log.d(TAG, "Return without notify.");
    }
}
