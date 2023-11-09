/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.net;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.Context;
import android.os.IBinder;
import android.os.ServiceManager;

import com.android.net.flags.Flags;
import com.android.net.module.util.PermissionUtils;
/**
 * Constants and utilities for client code communicating with the network stack service.
 * @hide
 */
@SystemApi
public class NetworkStack {
    /**
     * Permission granted only to the NetworkStack APK, defined in NetworkStackStub with signature
     * protection level.
     * @hide
     */
    @SystemApi
    public static final String PERMISSION_MAINLINE_NETWORK_STACK =
            "android.permission.MAINLINE_NETWORK_STACK";

    @Nullable
    private static volatile IBinder sMockService;

    /**
     * Get an {@link IBinder} representing the NetworkStack stable AIDL Interface, if registered.
     * @hide
     */
    @Nullable
    @SystemApi
    public static IBinder getService() {
        final IBinder mockService = sMockService;
        if (mockService != null) return mockService;
        return ServiceManager.getService(Context.NETWORK_STACK_SERVICE);
    }

    /**
     * Set a mock service for testing, to be returned by future calls to {@link #getService()}.
     *
     * <p>Passing a {@code null} {@code mockService} resets {@link #getService()} to normal
     * behavior.
     * @hide
     */
    @TestApi
    public static void setServiceForTest(@Nullable IBinder mockService) {
        sMockService = mockService;
    }

    private NetworkStack() {}

    /**
     * If the NetworkStack, MAINLINE_NETWORK_STACK are not allowed for a particular process, throw a
     * {@link SecurityException}.
     *
     * @param context {@link android.content.Context} for the process.
     *
     * @hide
     *
     * @deprecated Use {@link PermissionUtils#enforceNetworkStackPermission} instead.
     *
     * TODO: remove this method and let the users call to PermissionUtils directly.
     */
    @Deprecated
    public static void checkNetworkStackPermission(final @NonNull Context context) {
        PermissionUtils.enforceNetworkStackPermission(context);
    }

    /**
     * If the NetworkStack, MAINLINE_NETWORK_STACK or other specified permissions are not allowed
     * for a particular process, throw a {@link SecurityException}.
     *
     * @param context {@link android.content.Context} for the process.
     * @param otherPermissions The set of permissions that could be the candidate permissions , or
     *                         empty string if none of other permissions needed.
     * @hide
     *
     * @deprecated Use {@link PermissionUtils#enforceNetworkStackPermissionOr} instead.
     *
     * TODO: remove this method and let the users call to PermissionUtils directly.
     */
    @Deprecated
    public static void checkNetworkStackPermissionOr(final @NonNull Context context,
            final @NonNull String... otherPermissions) {
        PermissionUtils.enforceNetworkStackPermissionOr(context, otherPermissions);
    }

    /**
     * Get setting of the "set_data_saver_via_cm" flag.
     *
     * @hide
     */
    // A workaround for aconfig. Currently, aconfig value read from platform and mainline code can
    // be inconsistent. To avoid the problem, CTS for mainline code can get the flag value by this
    // method.
    public static boolean getDataSaverViaCmFlag() {
        return Flags.setDataSaverViaCm();
    }
}
