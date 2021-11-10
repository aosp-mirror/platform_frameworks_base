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

package android.app;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserHandleAware;
import android.content.Context;
import android.content.res.Configuration;
import android.os.LocaleList;
import android.os.RemoteException;

/**
 * This class gives access to system locale services. These services allow applications to control
 * granular locale settings (such as per-app locales).
 *
 * <p> Third party applications should treat this as a write-side surface, and continue reading
 * locales via their in-process {@link LocaleList}s.
 */
@SystemService(Context.LOCALE_SERVICE)
public class LocaleManager {
    private static final String TAG = "LocaleManager";

    /** Context required for getting the user for which API calls are made. */
    private Context mContext;
    private ILocaleManager mService;

    /** @hide Instantiated by ContextImpl */
    public LocaleManager(Context context, ILocaleManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Sets the UI locales for the calling app.
     *
     * <p>Pass a {@link LocaleList#getEmptyLocaleList()} to reset to the system locale.
     *
     * @param locales the desired locales for the calling app.
     */
    @UserHandleAware
    public void setApplicationLocales(@NonNull LocaleList locales) {
        setApplicationLocales(mContext.getPackageName(), locales);
    }

    /**
     * Sets the UI locales for a specified app (described by package name).
     *
     * <p>Pass a {@link LocaleList#getEmptyLocaleList()} to reset to the system locale.
     *
     * @param appPackageName the package name of the app for which to set the locales.
     * @param locales the desired locales for the specified app.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.CHANGE_CONFIGURATION)
    @UserHandleAware
    public void setApplicationLocales(@NonNull String appPackageName, @NonNull LocaleList locales) {
        try {
            mService.setApplicationLocales(appPackageName, mContext.getUser().getIdentifier(),
                    locales);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the UI locales for the calling app.
     *
     * <p>Returns a {@link LocaleList#getEmptyLocaleList()} if no app-specific locales are set.
     */
    @UserHandleAware
    @NonNull
    public LocaleList getApplicationLocales() {
        return getApplicationLocales(mContext.getPackageName());
    }

    /**
     * Returns the current UI locales for a specific app (described by package name).
     *
     * <p>Returns a {@link LocaleList#getEmptyLocaleList()} if no app-specific locales are set.
     *
     * <b>Note:</b> Non-system apps should read Locale information via their in-process
     * LocaleLists.
     *
     * @param appPackageName the package name of the app for which to retrieve the locales.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_APP_SPECIFIC_LOCALES)
    @UserHandleAware
    @NonNull
    public LocaleList getApplicationLocales(@NonNull String appPackageName) {
        try {
            return mService.getApplicationLocales(appPackageName, mContext.getUser()
                    .getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the current system locales to the provided value.
     *
     * @hide
     */
    @TestApi
    public void setSystemLocales(@NonNull LocaleList locales) {
        try {
            Configuration conf = ActivityManager.getService().getConfiguration();
            conf.setLocales(locales);
            ActivityManager.getService().updatePersistentConfiguration(conf);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current system locales for the device.
     *
     * @hide
     */
    @TestApi
    @Nullable
    public LocaleList getSystemLocales() {
        try {
            return ActivityManager.getService().getConfiguration().getLocales();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

}
