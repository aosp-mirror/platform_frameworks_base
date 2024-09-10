/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.inputmethod;

import static android.content.Intent.ACTION_OVERLAY_CHANGED;

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.PatternMatcher;
import android.os.UserHandle;
import android.util.Slog;

final class InputMethodDrawsNavBarResourceMonitor {
    private static final String TAG = "InputMethodDrawsNavBarResourceMonitor";

    private static final String SYSTEM_PACKAGE_NAME = "android";

    /**
     * Not intended to be instantiated.
     */
    private InputMethodDrawsNavBarResourceMonitor() {
    }

    @WorkerThread
    static boolean evaluate(@NonNull Context context, @UserIdInt int userId) {
        final Context userAwareContext;
        if (context.getUserId() == userId) {
            userAwareContext = context;
        } else {
            userAwareContext = context.createContextAsUser(UserHandle.of(userId), 0 /* flags */);
        }
        try {
            return userAwareContext.getPackageManager()
                    .getResourcesForApplication(SYSTEM_PACKAGE_NAME)
                    .getBoolean(com.android.internal.R.bool.config_imeDrawsImeNavBar);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "getResourcesForApplication(\"" + SYSTEM_PACKAGE_NAME + "\") failed",
                    e);
            return false;
        }
    }

    @FunctionalInterface
    interface OnUpdateCallback {
        void onUpdate(@UserIdInt int userId);
    }

    @SuppressLint("MissingPermission")
    @AnyThread
    static void registerCallback(@NonNull Context context, @NonNull Handler ioHandler,
            @NonNull OnUpdateCallback callback) {
        final IntentFilter intentFilter = new IntentFilter(ACTION_OVERLAY_CHANGED);
        intentFilter.addDataScheme(IntentFilter.SCHEME_PACKAGE);
        intentFilter.addDataSchemeSpecificPart(SYSTEM_PACKAGE_NAME, PatternMatcher.PATTERN_LITERAL);

        final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int userId = getSendingUserId();
                callback.onUpdate(userId);
            }
        };
        context.registerReceiverAsUser(broadcastReceiver, UserHandle.ALL, intentFilter,
                null /* broadcastPermission */, ioHandler, Context.RECEIVER_NOT_EXPORTED);
    }
}
