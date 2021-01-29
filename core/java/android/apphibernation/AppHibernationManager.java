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
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;

/**
 * This class provides an API surface for system apps to manipulate the app hibernation
 * state of a package for the user provided in the context.
 * @hide
 */
@SystemApi
@SystemService(Context.APP_HIBERNATION_SERVICE)
public final class AppHibernationManager {
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
     * Returns true if the package is hibernating, false otherwise.
     *
     * @hide
     */
    @SystemApi
    public boolean isHibernating(@NonNull String packageName) {
        try {
            return mIAppHibernationService.isHibernating(packageName, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set whether the package is hibernating.
     *
     * @hide
     */
    @SystemApi
    public void setHibernating(@NonNull String packageName, boolean isHibernating) {
        try {
            mIAppHibernationService.setHibernating(packageName, mContext.getUserId(),
                    isHibernating);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
