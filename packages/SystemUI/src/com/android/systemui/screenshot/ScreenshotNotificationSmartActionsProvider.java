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
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * This class can be overridden by a vendor-specific sys UI implementation,
 * in order to provide smart actions in the screenshot notification.
 */
public class ScreenshotNotificationSmartActionsProvider {
    private static final String TAG = "ScreenshotActions";

    /**
     * Default implementation that returns an empty list.
     * This method is overridden in vendor-specific Sys UI implementation.
     *
     * @param bitmap           The bitmap of the screenshot. The bitmap config must be {@link
     *                         HARDWARE}.
     * @param context          The current app {@link Context}.
     * @param executor         A {@link Executor} that can be used to execute tasks in parallel.
     * @param handler          A {@link Handler} to possibly run UI-thread code.
     * @param componentName    Contains package and activity class names where the screenshot was
     *                         taken. This is used as an additional signal to generate and rank more
     *                         relevant actions.
     * @param isManagedProfile The screenshot was taken for a work profile app.
     */
    public CompletableFuture<List<Notification.Action>> getActions(Bitmap bitmap, Context context,
            Executor executor, Handler handler, ComponentName componentName,
            boolean isManagedProfile) {
        Log.d(TAG, "Returning empty smart action list.");
        return CompletableFuture.completedFuture(Collections.emptyList());
    }
}
