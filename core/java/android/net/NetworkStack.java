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

import static android.Manifest.permission.NETWORK_STACK;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.content.Context;
import android.os.IBinder;
import android.os.ServiceManager;

import java.util.ArrayList;
import java.util.Arrays;
/**
 * Constants and utilities for client code communicating with the network stack service.
 * @hide
 */
@SystemApi
@TestApi
public class NetworkStack {
    /**
     * Permission granted only to the NetworkStack APK, defined in NetworkStackStub with signature
     * protection level.
     * @hide
     */
    @SystemApi
    @TestApi
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
    @TestApi
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
     */
    public static void checkNetworkStackPermission(final @NonNull Context context) {
        checkNetworkStackPermissionOr(context);
    }

    /**
     * If the NetworkStack, MAINLINE_NETWORK_STACK or other specified permissions are not allowed
     * for a particular process, throw a {@link SecurityException}.
     *
     * @param context {@link android.content.Context} for the process.
     * @param otherPermissions The set of permissions that could be the candidate permissions , or
     *                         empty string if none of other permissions needed.
     * @hide
     */
    public static void checkNetworkStackPermissionOr(final @NonNull Context context,
            final @NonNull String... otherPermissions) {
        ArrayList<String> permissions = new ArrayList<String>(Arrays.asList(otherPermissions));
        permissions.add(NETWORK_STACK);
        permissions.add(PERMISSION_MAINLINE_NETWORK_STACK);
        enforceAnyPermissionOf(context, permissions.toArray(new String[0]));
    }

    private static void enforceAnyPermissionOf(final @NonNull Context context,
            final @NonNull String... permissions) {
        if (!checkAnyPermissionOf(context, permissions)) {
            throw new SecurityException("Requires one of the following permissions: "
                + String.join(", ", permissions) + ".");
        }
    }

    private static boolean checkAnyPermissionOf(final @NonNull Context context,
            final @NonNull String... permissions) {
        for (String permission : permissions) {
            if (context.checkCallingOrSelfPermission(permission) == PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

}
