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
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

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

    /**
     * Binds to a sdk sandbox service, creating it if needed. You can through the arguments
     * here have the system bring up multiple concurrent processes hosting their own instance of
     * that service. The {@code processName} you provide here identifies the different instances.
     *
     * @param service Identifies the sdk sandbox process service to connect to. The Intent must
     *        specify an explicit component name. This value cannot be null.
     * @param conn Receives information as the service is started and stopped.
     *        This must be a valid ServiceConnection object; it must not be null.
     * @param clientAppUid Uid of the app for which the sdk sandbox process needs to be spawned.
     * @param clientApplicationThread ApplicationThread object of the app for which the sdk sandboox
     *                                is spawned.
     * @param clientAppPackage Package of the app for which the sdk sandbox process needs to
     *        be spawned. This package must belong to the clientAppUid.
     * @param processName Unique identifier for the service instance. Each unique name here will
     *        result in a different service instance being created. Identifiers must only contain
     *        ASCII letters, digits, underscores, and periods.
     * @param flags Operation options provided by Context class for the binding.
     * @return {@code true} if the system is in the process of bringing up a
     *         service that your client has permission to bind to; {@code false}
     *         if the system couldn't find the service or if your client doesn't
     *         have permission to bind to it.
     * @throws RemoteException If the service could not be brought up.
     * @see Context#bindService(Intent, ServiceConnection, int)
     */
    @SuppressLint("RethrowRemoteException")
    boolean bindSdkSandboxService(@NonNull Intent service, @NonNull ServiceConnection conn,
            int clientAppUid, @NonNull IBinder clientApplicationThread,
            @NonNull String clientAppPackage, @NonNull String processName,
            @Context.BindServiceFlags int flags)
            throws RemoteException;

    /**
     * @deprecated Please use
     * {@link #bindSdkSandboxService(Intent, ServiceConnection, int, IBinder, String, String, int)}
     *
     * This API can't be deleted yet because it can be used by early AdService module versions.
     */
    @SuppressLint("RethrowRemoteException")
    boolean bindSdkSandboxService(@NonNull Intent service, @NonNull ServiceConnection conn,
            int clientAppUid, @NonNull String clientAppPackage, @NonNull String processName,
            @Context.BindServiceFlags int flags)
            throws RemoteException;

    /**
     * Kill an app process associated with an SDK sandbox.
     *
     * @param clientApplicationThreadBinder binder value of the
     *        {@link android.app.IApplicationThread} of a client app process associated with a
     *        sandbox. This is obtained using {@link Context#getIApplicationThreadBinder()}.
     */
    void killSdkSandboxClientAppProcess(@NonNull IBinder clientApplicationThreadBinder);
}
