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

import static android.os.AsyncTask.THREAD_POOL_EXECUTOR;

import android.app.ActivityManager;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.shared.system.ActivityManagerWrapper;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Collects the static functions for retrieving and acting on smart actions.
 */
public class ScreenshotSmartActions {
    private static final String TAG = "ScreenshotSmartActions";

    @VisibleForTesting
    static CompletableFuture<List<Notification.Action>> getSmartActionsFuture(
            String screenshotId, String screenshotFileName, Bitmap image,
            ScreenshotNotificationSmartActionsProvider smartActionsProvider,
            boolean smartActionsEnabled, boolean isManagedProfile) {
        if (!smartActionsEnabled) {
            Slog.i(TAG, "Screenshot Intelligence not enabled, returning empty list.");
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        if (image.getConfig() != Bitmap.Config.HARDWARE) {
            Slog.w(TAG, String.format(
                    "Bitmap expected: Hardware, Bitmap found: %s. Returning empty list.",
                    image.getConfig()));
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        Slog.d(TAG, "Screenshot from a managed profile: " + isManagedProfile);
        CompletableFuture<List<Notification.Action>> smartActionsFuture;
        long startTimeMs = SystemClock.uptimeMillis();
        try {
            ActivityManager.RunningTaskInfo runningTask =
                    ActivityManagerWrapper.getInstance().getRunningTask();
            ComponentName componentName =
                    (runningTask != null && runningTask.topActivity != null)
                            ? runningTask.topActivity
                            : new ComponentName("", "");
            smartActionsFuture = smartActionsProvider.getActions(
                    screenshotId, screenshotFileName, image, componentName, isManagedProfile);
        } catch (Throwable e) {
            long waitTimeMs = SystemClock.uptimeMillis() - startTimeMs;
            smartActionsFuture = CompletableFuture.completedFuture(Collections.emptyList());
            Slog.e(TAG, "Failed to get future for screenshot notification smart actions.", e);
            notifyScreenshotOp(screenshotId, smartActionsProvider,
                    ScreenshotNotificationSmartActionsProvider.ScreenshotOp.REQUEST_SMART_ACTIONS,
                    ScreenshotNotificationSmartActionsProvider.ScreenshotOpStatus.ERROR,
                    waitTimeMs);
        }
        return smartActionsFuture;
    }

    @VisibleForTesting
    static List<Notification.Action> getSmartActions(String screenshotId, String screenshotFileName,
            CompletableFuture<List<Notification.Action>> smartActionsFuture, int timeoutMs,
            ScreenshotNotificationSmartActionsProvider smartActionsProvider) {
        long startTimeMs = SystemClock.uptimeMillis();
        try {
            List<Notification.Action> actions = smartActionsFuture.get(timeoutMs,
                    TimeUnit.MILLISECONDS);
            long waitTimeMs = SystemClock.uptimeMillis() - startTimeMs;
            Slog.d(TAG, String.format("Got %d smart actions. Wait time: %d ms",
                    actions.size(), waitTimeMs));
            notifyScreenshotOp(screenshotId, smartActionsProvider,
                    ScreenshotNotificationSmartActionsProvider.ScreenshotOp.WAIT_FOR_SMART_ACTIONS,
                    ScreenshotNotificationSmartActionsProvider.ScreenshotOpStatus.SUCCESS,
                    waitTimeMs);
            return actions;
        } catch (Throwable e) {
            long waitTimeMs = SystemClock.uptimeMillis() - startTimeMs;
            Slog.e(TAG, String.format("Error getting smart actions. Wait time: %d ms", waitTimeMs),
                    e);
            ScreenshotNotificationSmartActionsProvider.ScreenshotOpStatus status =
                    (e instanceof TimeoutException)
                            ? ScreenshotNotificationSmartActionsProvider.ScreenshotOpStatus.TIMEOUT
                            : ScreenshotNotificationSmartActionsProvider.ScreenshotOpStatus.ERROR;
            notifyScreenshotOp(screenshotId, smartActionsProvider,
                    ScreenshotNotificationSmartActionsProvider.ScreenshotOp.WAIT_FOR_SMART_ACTIONS,
                    status, waitTimeMs);
            return Collections.emptyList();
        }
    }

    static void notifyScreenshotOp(String screenshotId,
            ScreenshotNotificationSmartActionsProvider smartActionsProvider,
            ScreenshotNotificationSmartActionsProvider.ScreenshotOp op,
            ScreenshotNotificationSmartActionsProvider.ScreenshotOpStatus status, long durationMs) {
        try {
            smartActionsProvider.notifyOp(screenshotId, op, status, durationMs);
        } catch (Throwable e) {
            Slog.e(TAG, "Error in notifyScreenshotOp: ", e);
        }
    }

    static void notifyScreenshotAction(Context context, String screenshotId, String action,
            boolean isSmartAction) {
        try {
            ScreenshotNotificationSmartActionsProvider provider =
                    SystemUIFactory.getInstance().createScreenshotNotificationSmartActionsProvider(
                            context, THREAD_POOL_EXECUTOR, new Handler());
            provider.notifyAction(screenshotId, action, isSmartAction);
        } catch (Throwable e) {
            Slog.e(TAG, "Error in notifyScreenshotAction: ", e);
        }
    }
}
