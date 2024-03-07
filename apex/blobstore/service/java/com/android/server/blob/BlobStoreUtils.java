/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.blob;

import static com.android.server.blob.BlobStoreConfig.TAG;

import android.annotation.IdRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.text.format.TimeMigrationUtils;
import android.util.Slog;

class BlobStoreUtils {
    private static final String DESC_RES_TYPE_STRING = "string";

    @Nullable
    static Resources getPackageResources(@NonNull Context context,
            @NonNull String packageName, int userId) {
        try {
            return context.createContextAsUser(UserHandle.of(userId), /* flags */ 0)
                    .getPackageManager().getResourcesForApplication(packageName);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.d(TAG, "Unknown package in user " + userId + ": "
                    + packageName, e);
            return null;
        }
    }

    @IdRes
    static int getDescriptionResourceId(@NonNull Resources resources,
            @NonNull String resourceEntryName, @NonNull String packageName) {
        return resources.getIdentifier(resourceEntryName, DESC_RES_TYPE_STRING, packageName);
    }

    @IdRes
    static int getDescriptionResourceId(@NonNull Context context,
            @NonNull String resourceEntryName, @NonNull String packageName, int userId) {
        final Resources resources = getPackageResources(context, packageName, userId);
        return resources == null
                ? Resources.ID_NULL
                : getDescriptionResourceId(resources, resourceEntryName, packageName);
    }

    @NonNull
    static String formatTime(long timeMs) {
        return TimeMigrationUtils.formatMillisWithFixedFormat(timeMs);
    }

    private static Handler sRevocableFdHandler;
    private static final Object sLock = new Object();

    // By default, when using a RevocableFileDescriptor, callbacks will be sent to the process'
    // main looper. In this case that would be system_server's main looper, which is a heavily
    // contended thread. It can also cause deadlocks, because the volume daemon 'vold' holds a lock
    // while making these callbacks to the system_server, while at the same time the system_server
    // main thread can make a call into vold, which requires that same vold lock. To avoid these
    // issues, use a separate thread for the RevocableFileDescriptor's requests, so that it can
    // make progress independently of system_server.
    static @NonNull Handler getRevocableFdHandler() {
        synchronized (sLock) {
            if (sRevocableFdHandler != null) {
                return sRevocableFdHandler;
            }
            final HandlerThread t = new HandlerThread("BlobFuseLooper");
            t.start();
            sRevocableFdHandler = new Handler(t.getLooper());

            return sRevocableFdHandler;
        }
    }
}
