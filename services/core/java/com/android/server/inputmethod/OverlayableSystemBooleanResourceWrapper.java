/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.annotation.BoolRes;
import android.annotation.NonNull;
import android.annotation.UserHandleAware;
import android.annotation.UserIdInt;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.PatternMatcher;
import android.util.Slog;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * A wrapper object for any boolean resource defined in {@code "android"} package, in a way that is
 * aware of per-user Runtime Resource Overlay (RRO).
 */
final class OverlayableSystemBooleanResourceWrapper implements AutoCloseable {
    private static final String TAG = "OverlayableSystemBooleanResourceWrapper";

    private static final String SYSTEM_PACKAGE_NAME = "android";

    @UserIdInt
    private final int mUserId;
    @NonNull
    private final AtomicBoolean mValueRef;
    @NonNull
    private final AtomicReference<Runnable> mCleanerRef;

    /**
     * Creates {@link OverlayableSystemBooleanResourceWrapper} for the given boolean resource ID
     * with a value change callback for the user associated with the {@link Context}.
     *
     * @param userContext The {@link Context} to be used to access the resource. This needs to be
     *                    associated with the right user because the Runtime Resource Overlay (RRO)
     *                    is per-user configuration.
     * @param boolResId The resource ID to be queried.
     * @param handler {@link Handler} to be used to dispatch {@code callback}.
     * @param callback The callback to be notified when the specified value might be updated.
     *                 The callback needs to take care of spurious wakeup. The value returned from
     *                 {@link #get()} may look to be exactly the same as the previously read value
     *                 e.g. when the value is changed from {@code false} to {@code true} to
     *                 {@code false} in a very short period of time, because {@link #get()} always
     *                 does volatile-read.
     * @return New {@link OverlayableSystemBooleanResourceWrapper}.
     */
    @NonNull
    @UserHandleAware
    static OverlayableSystemBooleanResourceWrapper create(@NonNull Context userContext,
            @BoolRes int boolResId, @NonNull Handler handler,
            @NonNull Consumer<OverlayableSystemBooleanResourceWrapper> callback) {

        // Note that we cannot fully trust this initial value due to the dead time between obtaining
        // the value here and setting up a broadcast receiver for change callback below.
        // We will refresh the value again later after setting up the change callback anyway.
        final AtomicBoolean valueRef = new AtomicBoolean(evaluate(userContext, boolResId));

        final AtomicReference<Runnable> cleanerRef = new AtomicReference<>();

        final OverlayableSystemBooleanResourceWrapper object =
                new OverlayableSystemBooleanResourceWrapper(userContext.getUserId(), valueRef,
                        cleanerRef);

        final IntentFilter intentFilter = new IntentFilter(ACTION_OVERLAY_CHANGED);
        intentFilter.addDataScheme(IntentFilter.SCHEME_PACKAGE);
        intentFilter.addDataSchemeSpecificPart(SYSTEM_PACKAGE_NAME, PatternMatcher.PATTERN_LITERAL);

        final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final boolean newValue = evaluate(userContext, boolResId);
                if (newValue != valueRef.getAndSet(newValue)) {
                    callback.accept(object);
                }
            }
        };
        userContext.registerReceiver(broadcastReceiver, intentFilter,
                null /* broadcastPermission */, handler,
                Context.RECEIVER_NOT_EXPORTED);
        cleanerRef.set(() -> userContext.unregisterReceiver(broadcastReceiver));

        // Make sure that the initial observable value is obtained after the change callback is set.
        valueRef.set(evaluate(userContext, boolResId));
        return object;
    }

    private OverlayableSystemBooleanResourceWrapper(@UserIdInt int userId,
            @NonNull AtomicBoolean valueRef, @NonNull AtomicReference<Runnable> cleanerRef) {
        mUserId = userId;
        mValueRef = valueRef;
        mCleanerRef = cleanerRef;
    }

    /**
     * @return The boolean resource value.
     */
    @AnyThread
    boolean get() {
        return mValueRef.get();
    }

    /**
     * @return The user ID associated with this resource reader.
     */
    @AnyThread
    @UserIdInt
    int getUserId() {
        return mUserId;
    }

    @AnyThread
    private static boolean evaluate(@NonNull Context context, @BoolRes int boolResId) {
        try {
            return context.getPackageManager()
                    .getResourcesForApplication(SYSTEM_PACKAGE_NAME)
                    .getBoolean(boolResId);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "getResourcesForApplication(\"" + SYSTEM_PACKAGE_NAME + "\") failed", e);
            return false;
        }
    }

    /**
     * Cleans up the callback.
     */
    @AnyThread
    @Override
    public void close() {
        final Runnable cleaner = mCleanerRef.getAndSet(null);
        if (cleaner != null) {
            cleaner.run();
        }
    }
}
