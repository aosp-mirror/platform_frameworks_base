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

package android.app.supervision;

import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.annotation.UserHandleAware;
import android.annotation.UserIdInt;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.os.RemoteException;

/**
 * Service for handling parental supervision.
 *
 * @hide
 */
@SystemService(Context.SUPERVISION_SERVICE)
public class SupervisionManager {
    private final Context mContext;
    @Nullable private final ISupervisionManager mService;

    /**
     * Activity action: ask the human user to enable supervision for this user. Only the app that
     * holds the {@code SYSTEM_SUPERVISION} role can launch this intent.
     *
     * <p>The intent must be invoked via {@link Activity#startActivityForResult} to receive the
     * result of whether or not the user approved the action. If approved, the result will be {@link
     * Activity#RESULT_OK}.
     *
     * <p>If supervision is already enabled, the operation will return a failure result.
     *
     * @hide
     */
    public static final String ACTION_ENABLE_SUPERVISION = "android.app.action.ENABLE_SUPERVISION";

    /**
     * Activity action: ask the human user to disable supervision for this user. Only the app that
     * holds the {@code SYSTEM_SUPERVISION} role can launch this intent.
     *
     * <p>The intent must be invoked via {@link Activity#startActivityForResult} to receive the
     * result of whether or not the user approved the action. If approved, the result will be {@link
     * Activity#RESULT_OK}.
     *
     * <p>If supervision is not enabled, the operation will return a failure result.
     *
     * @hide
     */
    public static final String ACTION_DISABLE_SUPERVISION =
            "android.app.action.DISABLE_SUPERVISION";

    /** @hide */
    @UnsupportedAppUsage
    public SupervisionManager(Context context, @Nullable ISupervisionManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Returns whether the device is supervised.
     *
     * @hide
     */
    @UserHandleAware
    public boolean isSupervisionEnabled() {
        return isSupervisionEnabledForUser(mContext.getUserId());
    }

    /**
     * Returns whether the device is supervised.
     *
     * <p>The caller must be from the same user as the target or hold the {@link
     * android.Manifest.permission#INTERACT_ACROSS_USERS} permission.
     *
     * @hide
     */
    @RequiresPermission(
            value = android.Manifest.permission.INTERACT_ACROSS_USERS,
            conditional = true)
    public boolean isSupervisionEnabledForUser(@UserIdInt int userId) {
        if (mService != null) {
            try {
                return mService.isSupervisionEnabledForUser(userId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return false;
    }

    /**
     * Sets whether the device is supervised for the current user.
     *
     * @hide
     */
    @UserHandleAware
    public void setSupervisionEnabled(boolean enabled) {
        setSupervisionEnabledForUser(mContext.getUserId(), enabled);
    }

    /**
     * Sets whether the device is supervised for a given user.
     *
     * <p>The caller must be from the same user as the target or hold the {@link
     * android.Manifest.permission#INTERACT_ACROSS_USERS} permission.
     *
     * @hide
     */
    @RequiresPermission(
            value = android.Manifest.permission.INTERACT_ACROSS_USERS,
            conditional = true)
    public void setSupervisionEnabledForUser(@UserIdInt int userId, boolean enabled) {
        if (mService != null) {
            try {
                mService.setSupervisionEnabledForUser(userId, enabled);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns the package name of the app that is acting as the active supervision app or null if
     * supervision is disabled.
     *
     * @hide
     */
    @UserHandleAware
    @Nullable
    public String getActiveSupervisionAppPackage() {
        if (mService != null) {
            try {
                return mService.getActiveSupervisionAppPackage(mContext.getUserId());
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        return null;
    }
}
