/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.am;

import android.annotation.NonNull;
import android.annotation.SystemApi;

/**
 * Interface for in-process calls into
 * {@link android.content.Context#ACTIVITY_SERVICE ActivityManager system service}.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public interface ActivityManagerLocal {
    /**
     * Checks whether an app will be able to start a foreground service or not.
     *
     * @param pid The process id belonging to the app to be checked.
     * @param uid The UID of the app to be checked.
     * @param packageName The package name of the app to be checked.
     * @return whether the app will be able to start a foreground service or not.
     */
    boolean canStartForegroundService(int pid, int uid, @NonNull String packageName);

    /**
     * Returns {@code true} if a foreground service started by an uid is allowed to have
     * while-in-use permissions.
     *
     * @param pid The process id belonging to the app to be checked.
     * @param uid The UID of the app to be checked.
     * @param packageName The package name of the app to be checked.
     * @return whether the foreground service is allowed to have while-in-use permissions.
     * @hide
     */
    boolean canAllowWhileInUsePermissionInFgs(int pid, int uid, @NonNull String packageName);

    /**
     * Temporarily allow foreground service started by an uid to have while-in-use permission
     * for durationMs.
     *
     * @param uid The UID of the app that starts the foreground service.
     * @param durationMs elapsedRealTime duration in milliseconds.
     * @hide
     */
    void tempAllowWhileInUsePermissionInFgs(int uid, long durationMs);
}
