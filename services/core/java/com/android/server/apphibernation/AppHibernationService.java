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

package com.android.server.apphibernation;

import static android.provider.DeviceConfig.NAMESPACE_APP_HIBERNATION;

import android.annotation.NonNull;
import android.apphibernation.IAppHibernationService;
import android.content.Context;
import android.provider.DeviceConfig;

import com.android.server.SystemService;

/**
 * System service that manages app hibernation state, a state apps can enter that means they are
 * not being actively used and can be optimized for storage. The actual policy for determining
 * if an app should hibernate is managed by PermissionController code.
 */
public final class AppHibernationService extends SystemService {

    private final Context mContext;

    /**
     * Initializes the system service.
     * <p>
     * Subclasses must define a single argument constructor that accepts the context
     * and passes it to super.
     * </p>
     *
     * @param context The system server context.
     */
    public AppHibernationService(@NonNull Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onStart() {
        publishBinderService(Context.APP_HIBERNATION_SERVICE, mServiceStub);
    }

    /**
     * Whether a package is hibernating for a given user.
     *
     * @param packageName the package to check
     * @param userId the user to check
     * @return true if package is hibernating for the user
     */
    public boolean isHibernating(String packageName, int userId) {
        // Stub
        throw new UnsupportedOperationException("Hibernation state management not implemented yet");
    }

    /**
     * Set whether the package is hibernating for the given user.
     *
     * @param packageName package to modify state
     * @param userId user
     * @param isHibernating new hibernation state
     */
    public void setHibernating(String packageName, int userId, boolean isHibernating) {
        // Stub
        throw new UnsupportedOperationException("Hibernation state management not implemented yet");
    }

    private final AppHibernationServiceStub mServiceStub = new AppHibernationServiceStub(this);

    static final class AppHibernationServiceStub extends IAppHibernationService.Stub {
        final AppHibernationService mService;

        AppHibernationServiceStub(AppHibernationService service) {
            mService = service;
        }

        @Override
        public boolean isHibernating(String packageName, int userId) {
            return mService.isHibernating(packageName, userId);
        }

        @Override
        public void setHibernating(String packageName, int userId, boolean isHibernating) {
            mService.setHibernating(packageName, userId, isHibernating);
        }
    }

    /**
     * Whether app hibernation is enabled on this device.
     *
     * @return true if enabled, false otherwise
     */
    public static boolean isAppHibernationEnabled() {
        return DeviceConfig.getBoolean(
                NAMESPACE_APP_HIBERNATION,
                AppHibernationConstants.KEY_APP_HIBERNATION_ENABLED,
                false /* defaultValue */);
    }
}
