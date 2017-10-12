/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.content.pm.crossprofile;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;

import java.util.List;

/**
 * Class for handling cross profile operations. Apps can use this class to interact with its
 * instance in any profile that is in {@link #getTargetUserProfiles()}. For example, app can
 * use this class to start its main activity in managed profile.
 */
public class CrossProfileApps {
    private final Context mContext;
    private final ICrossProfileApps mService;

    /** @hide */
    public CrossProfileApps(Context context, ICrossProfileApps service) {
        mContext = context;
        mService = service;
    }

    /**
     * Starts the specified main activity of the caller package in the specified profile.
     *
     * @param component The ComponentName of the activity to launch, it must be exported and has
     *        action {@link android.content.Intent#ACTION_MAIN}, category
     *        {@link android.content.Intent#CATEGORY_LAUNCHER}. Otherwise, SecurityException will
     *        be thrown.
     * @param user The UserHandle of the profile, must be one of the users returned by
     *        {@link #getTargetUserProfiles()}, otherwise a {@link SecurityException} will
     *        be thrown.
     * @param sourceBounds The Rect containing the source bounds of the clicked icon, see
     *                     {@link android.content.Intent#setSourceBounds(Rect)}.
     * @param startActivityOptions Options to pass to startActivity
     */
    public void startMainActivity(@NonNull ComponentName component, @NonNull UserHandle user,
            @Nullable Rect sourceBounds, @Nullable Bundle startActivityOptions) {
        try {
            mService.startActivityAsUser(mContext.getPackageName(),
                    component, sourceBounds, startActivityOptions, user);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Return a list of user profiles that that the caller can use when calling other APIs in this
     * class.
     * <p>
     * A user profile would be considered as a valid target user profile, provided that:
     * <ul>
     * <li>It gets caller app installed</li>
     * <li>It is not equal to the calling user</li>
     * <li>It is in the same profile group of calling user profile</li>
     * <li>It is enabled</li>
     * </ul>
     *
     * @see UserManager#getUserProfiles()
     */
    public @NonNull List<UserHandle> getTargetUserProfiles() {
        try {
            return mService.getTargetUserProfiles(mContext.getPackageName());
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }
}
