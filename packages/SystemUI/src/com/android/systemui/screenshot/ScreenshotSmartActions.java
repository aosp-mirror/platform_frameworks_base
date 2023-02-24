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
import static com.android.systemui.screenshot.ScreenshotNotificationSmartActionsProvider.ScreenshotSmartActionType;

import android.app.ActivityManager;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.shared.system.ActivityManagerWrapper;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Collects the static functions for retrieving and acting on smart actions.
 */
@SysUISingleton
public class ScreenshotSmartActions {
    private static final String TAG = logTag(ScreenshotSmartActions.class);
    private final Provider<ScreenshotNotificationSmartActionsProvider>
            mScreenshotNotificationSmartActionsProviderProvider;

    @Inject
    public ScreenshotSmartActions(
            Provider<ScreenshotNotificationSmartActionsProvider>
                    screenshotNotificationSmartActionsProviderProvider
    ) {
        mScreenshotNotificationSmartActionsProviderProvider =
                screenshotNotificationSmartActionsProviderProvider;
    }

    @VisibleForTesting
    CompletableFuture<List<Notification.Action>> getSmartActionsFuture(
            String screenshotId, Uri screenshotUri, Bitmap image,
            ScreenshotNotificationSmartActionsProvider smartActionsProvider,
            ScreenshotSmartActionType actionType,
            boolean smartActionsEnabled, UserHandle userHandle) {
        if (DEBUG_ACTIONS) {
            Log.d(TAG, String.format(
                    "getSmartActionsFuture id=%s, uri=%s, provider=%s, actionType=%s, "
                            + "smartActionsEnabled=%b, userHandle=%s",
                    screenshotId, screenshotUri, smartActionsProvider.getClass(), actionType,
                    smartActionsEnabled, userHandle));
        }
        if (!smartActionsEnabled) {
            if (DEBUG_ACTIONS) {
                Log.d(TAG, "Screenshot Intelligence not enabled, returning empty list.");
            }
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        if (image.getConfig() != Bitmap.Config.HARDWARE) {
            if (DEBUG_ACTIONS) {
                Log.d(TAG, String.format("Bitmap expected: Hardware, Bitmap found: %s. "
                                + "Returning empty list.", image.getConfig()));
            }
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        CompletableFuture<List<Notification.Action>> smartActionsFuture;
        long startTimeMs = SystemClock.uptimeMillis();
        try {
            ActivityManager.RunningTaskInfo runningTask =
                    ActivityManagerWrapper.getInstance().getRunningTask();
            ComponentName componentName =
                    (runningTask != null && runningTask.topActivity != null)
                            ? runningTask.topActivity
                            : new ComponentName("", "");
            smartActionsFuture = smartActionsProvider.getActions(screenshotId, screenshotUri, image,
                    componentName, actionType, userHandle);
        } catch (Throwable e) {
            long waitTimeMs = SystemClock.uptimeMillis() - startTimeMs;
            smartActionsFuture = CompletableFuture.completedFuture(Collections.emptyList());
            if (DEBUG_ACTIONS) {
                Log.e(TAG, "Failed to get future for screenshot notification smart actions.", e);
            }
            notifyScreenshotOp(screenshotId, smartActionsProvider,
                    ScreenshotNotificationSmartActionsProvider.ScreenshotOp.REQUEST_SMART_ACTIONS,
                    ScreenshotNotificationSmartActionsProvider.ScreenshotOpStatus.ERROR,
                    waitTimeMs);
        }
        return smartActionsFuture;
    }

    @VisibleForTesting
    List<Notification.Action> getSmartActions(String screenshotId,
            CompletableFuture<List<Notification.Action>> smartActionsFuture, int timeoutMs,
            ScreenshotNotificationSmartActionsProvider smartActionsProvider,
            ScreenshotSmartActionType actionType) {
        long startTimeMs = SystemClock.uptimeMillis();
        if (DEBUG_ACTIONS) {
            Log.d(TAG,
                    String.format("getSmartActions id=%s, timeoutMs=%d, actionType=%s, provider=%s",
                            screenshotId, timeoutMs, actionType, smartActionsProvider.getClass()));
        }
        try {
            List<Notification.Action> actions = smartActionsFuture.get(timeoutMs,
                    TimeUnit.MILLISECONDS);
            long waitTimeMs = SystemClock.uptimeMillis() - startTimeMs;
            if (DEBUG_ACTIONS) {
                Log.d(TAG, String.format("Got %d smart actions. Wait time: %d ms, actionType=%s",
                        actions.size(), waitTimeMs, actionType));
            }
            notifyScreenshotOp(screenshotId, smartActionsProvider,
                    ScreenshotNotificationSmartActionsProvider.ScreenshotOp.WAIT_FOR_SMART_ACTIONS,
                    ScreenshotNotificationSmartActionsProvider.ScreenshotOpStatus.SUCCESS,
                    waitTimeMs);
            return actions;
        } catch (Throwable e) {
            long waitTimeMs = SystemClock.uptimeMillis() - startTimeMs;
            if (DEBUG_ACTIONS) {
                Log.e(TAG, String.format(
                        "Error getting smart actions. Wait time: %d ms, actionType=%s",
                        waitTimeMs, actionType), e);
            }
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

    void notifyScreenshotOp(String screenshotId,
            ScreenshotNotificationSmartActionsProvider smartActionsProvider,
            ScreenshotNotificationSmartActionsProvider.ScreenshotOp op,
            ScreenshotNotificationSmartActionsProvider.ScreenshotOpStatus status, long durationMs) {
        if (DEBUG_ACTIONS) {
            Log.d(TAG, String.format("%s notifyOp: %s id=%s, status=%s, durationMs=%d",
                    smartActionsProvider.getClass(), op, screenshotId, status, durationMs));
        }
        try {
            smartActionsProvider.notifyOp(screenshotId, op, status, durationMs);
        } catch (Throwable e) {
            Log.e(TAG, "Error in notifyScreenshotOp: ", e);
        }
    }

    void notifyScreenshotAction(String screenshotId, String action,
            boolean isSmartAction, Intent intent) {
        try {
            ScreenshotNotificationSmartActionsProvider provider =
                    mScreenshotNotificationSmartActionsProviderProvider.get();
            if (DEBUG_ACTIONS) {
                Log.d(TAG, String.format("%s notifyAction: %s id=%s, isSmartAction=%b",
                        provider.getClass(), action, screenshotId, isSmartAction));
            }
            provider.notifyAction(screenshotId, action, isSmartAction, intent);
        } catch (Throwable e) {
            Log.e(TAG, "Error in notifyScreenshotAction: ", e);
        }
    }
}
