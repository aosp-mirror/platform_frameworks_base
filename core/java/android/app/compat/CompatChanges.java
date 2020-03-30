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

package android.app.compat;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.compat.Compatibility;
import android.content.Context;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;

import com.android.internal.compat.IPlatformCompat;

/**
 * CompatChanges APIs - to be used by platform code only (including mainline
 * modules).
 *
 * @hide
 */
@SystemApi
public final class CompatChanges {
    private CompatChanges() {}

    /**
     * Query if a given compatibility change is enabled for the current process. This method is
     * intended to be called by code running inside a process of the affected app only.
     *
     * <p>If this method returns {@code true}, the calling code should implement the compatibility
     * change, resulting in differing behaviour compared to earlier releases. If this method returns
     * {@code false}, the calling code should behave as it did in earlier releases.
     *
     * @param changeId The ID of the compatibility change in question.
     * @return {@code true} if the change is enabled for the current app.
     */
    public static boolean isChangeEnabled(long changeId) {
        return Compatibility.isChangeEnabled(changeId);
    }

    /**
     * Same as {@code #isChangeEnabled(long)}, except this version should be called on behalf of an
     * app from a different process that's performing work for the app.
     *
     * <p> Note that this involves a binder call to the system server (unless running in the system
     * server). If the binder call fails, a {@code RuntimeException} will be thrown.
     *
     * @param changeId    The ID of the compatibility change in question.
     * @param packageName The package name of the app in question.
     * @param user        The user that the operation is done for.
     * @return {@code true} if the change is enabled for the current app.
     */
    @RequiresPermission(allOf = {android.Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
            android.Manifest.permission.LOG_COMPAT_CHANGE})
    public static boolean isChangeEnabled(long changeId, @NonNull String packageName,
            @NonNull UserHandle user) {
        IPlatformCompat platformCompat = IPlatformCompat.Stub.asInterface(
                ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));
        final long token = Binder.clearCallingIdentity();
        try {
            return platformCompat.isChangeEnabledByPackageName(changeId, packageName,
                    user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Same as {@code #isChangeEnabled(long)}, except this version should be called on behalf of an
     * app from a different process that's performing work for the app.
     *
     * <p> Note that this involves a binder call to the system server (unless running in the system
     * server). If the binder call fails, {@code RuntimeException}  will be thrown.
     *
     * <p> Returns {@code true} if there are no installed packages for the required UID, or if the
     * change is enabled for ALL of the installed packages associated with the provided UID. Please
     * use a more specific API if you want a different behaviour for multi-package UIDs.
     *
     * @param changeId The ID of the compatibility change in question.
     * @param uid      The UID of the app in question.
     * @return {@code true} if the change is enabled for the current app.
     */
    @RequiresPermission(allOf = {android.Manifest.permission.READ_COMPAT_CHANGE_CONFIG,
            android.Manifest.permission.LOG_COMPAT_CHANGE})
    public static boolean isChangeEnabled(long changeId, int uid) {
        IPlatformCompat platformCompat = IPlatformCompat.Stub.asInterface(
                ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));
        final long token = Binder.clearCallingIdentity();
        try {
            return platformCompat.isChangeEnabledByUid(changeId, uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }
}
