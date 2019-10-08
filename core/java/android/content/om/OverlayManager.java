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

package android.content.om;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;

import java.util.List;

/**
 * Updates OverlayManager state; gets information about installed overlay packages.
 * @hide
 */
@SystemApi
@SystemService(Context.OVERLAY_SERVICE)
public class OverlayManager {

    private final IOverlayManager mService;
    private final Context mContext;

    /**
     * Creates a new instance.
     *
     * @param context The current context in which to operate.
     * @param service The backing system service.
     *
     * @hide
     */
    public OverlayManager(Context context, IOverlayManager service) {
        mContext = context;
        mService = service;
    }

    /** @hide */
    public OverlayManager(Context context) {
        this(context, IOverlayManager.Stub.asInterface(
            ServiceManager.getService(Context.OVERLAY_SERVICE)));
    }

    /**
     * Request that an overlay package is enabled and any other overlay packages with the same
     * target package and category are disabled.
     *
     * If a set of overlay packages share the same category, single call to this method is
     * equivalent to multiple calls to {@link #setEnabled(String, boolean, UserHandle)}.
     *
     * @param packageName the name of the overlay package to enable.
     * @param user The user for which to change the overlay.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            "android.permission.INTERACT_ACROSS_USERS",
            "android.permission.INTERACT_ACROSS_USERS_FULL"
    })
    public void setEnabledExclusiveInCategory(@NonNull final String packageName,
            @NonNull UserHandle user) {
        try {
            if (!mService.setEnabledExclusiveInCategory(packageName, user.getIdentifier())) {
                throw new IllegalStateException("setEnabledExclusiveInCategory failed");
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request that an overlay package is enabled or disabled.
     *
     * While {@link #setEnabledExclusiveInCategory(String, UserHandle)} doesn't support disabling
     * every overlay in a category, this method allows you to disable everything.
     *
     * @param packageName the name of the overlay package to enable.
     * @param enable {@code false} if the overlay should be turned off.
     * @param user The user for which to change the overlay.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            "android.permission.INTERACT_ACROSS_USERS",
            "android.permission.INTERACT_ACROSS_USERS_FULL"
    })
    public void setEnabled(@NonNull final String packageName, final boolean enable,
            @NonNull UserHandle user) {
        try {
            if (!mService.setEnabled(packageName, enable, user.getIdentifier())) {
                throw new IllegalStateException("setEnabled failed");
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        return;
    }

    /**
     * Returns information about the overlay with the given package name for
     * the specified user.
     *
     * @param packageName The name of the package.
     * @param userHandle The user to get the OverlayInfos for.
     * @return An OverlayInfo object; if no overlays exist with the
     *         requested package name, null is returned.
     *
     * @hide
     */
    @SystemApi
    @Nullable
    public OverlayInfo getOverlayInfo(@NonNull final String packageName,
            @NonNull final UserHandle userHandle) {
        try {
            return mService.getOverlayInfo(packageName, userHandle.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns information about all overlays for the given target package for
     * the specified user. The returned list is ordered according to the
     * overlay priority with the highest priority at the end of the list.
     *
     * @param targetPackageName The name of the target package.
     * @param user The user to get the OverlayInfos for.
     * @return A list of OverlayInfo objects; if no overlays exist for the
     *         requested package, an empty list is returned.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            "android.permission.INTERACT_ACROSS_USERS",
            "android.permission.INTERACT_ACROSS_USERS_FULL"
    })
    @NonNull
    public List<OverlayInfo> getOverlayInfosForTarget(@NonNull final String targetPackageName,
            @NonNull UserHandle user) {
        try {
            return mService.getOverlayInfosForTarget(targetPackageName, user.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
