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

package android.apphibernation;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;

import java.util.List;

/**
 * This class provides an API surface for system apps to manipulate the app hibernation
 * state of a package for the user provided in the context.
 * @hide
 */
@SystemApi
@SystemService(Context.APP_HIBERNATION_SERVICE)
public class AppHibernationManager {
    private static final String TAG = "AppHibernationManager";
    private final Context mContext;
    private final IAppHibernationService mIAppHibernationService;

    /**
     * Creates a new instance.
     *
     * @param context The current context associated with the user
     *
     * @hide
     */
    public AppHibernationManager(@NonNull Context context) {
        mContext = context;
        mIAppHibernationService = IAppHibernationService.Stub.asInterface(
                ServiceManager.getService(Context.APP_HIBERNATION_SERVICE));
    }

    /**
     * Returns true if the package is hibernating for this context's user, false otherwise.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(value = android.Manifest.permission.MANAGE_APP_HIBERNATION)
    public boolean isHibernatingForUser(@NonNull String packageName) {
        try {
            return mIAppHibernationService.isHibernatingForUser(packageName, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set whether the package is hibernating for this context's user.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(value = android.Manifest.permission.MANAGE_APP_HIBERNATION)
    public void setHibernatingForUser(@NonNull String packageName, boolean isHibernating) {
        try {
            mIAppHibernationService.setHibernatingForUser(packageName, mContext.getUserId(),
                    isHibernating);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if app is hibernating globally / at the package level.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(value = android.Manifest.permission.MANAGE_APP_HIBERNATION)
    public boolean isHibernatingGlobally(@NonNull String packageName) {
        try {
            return mIAppHibernationService.isHibernatingGlobally(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set whether a package should be globally hibernating. This hibernates the package at a
     * package level. User-level hibernation (e.g.. {@link #isHibernatingForUser} is independent
     * from global hibernation.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(value = android.Manifest.permission.MANAGE_APP_HIBERNATION)
    public void setHibernatingGlobally(@NonNull String packageName, boolean isHibernating) {
        try {
            mIAppHibernationService.setHibernatingGlobally(packageName, isHibernating);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the hibernating packages for the user. This is equivalent to the list of packages for
     * the user that return true for {@link #isHibernatingForUser}.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(value = android.Manifest.permission.MANAGE_APP_HIBERNATION)
    public @NonNull List<String> getHibernatingPackagesForUser() {
        try {
            return mIAppHibernationService.getHibernatingPackagesForUser(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
