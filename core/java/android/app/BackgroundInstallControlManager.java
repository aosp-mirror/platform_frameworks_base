/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.Manifest.permission.QUERY_ALL_PACKAGES;
import static android.annotation.SystemApi.Client.PRIVILEGED_APPS;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.content.pm.IBackgroundInstallControlService;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IRemoteCallback;
import android.os.ServiceManager;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * BackgroundInstallControlManager client allows apps to query apps installed in background.
 *
 * <p>Apps are considered to be installed in background is determined by analysis of user-activity
 * logs. Any applications that was installed without an accompanying installer UI activity paired
 * with recorded user interaction event is considered background installed.
 *
 * <p>Warning: BackgroundInstallControl should not be considered a reliable or accurate
 * determination of background install application. Consumers can use this as a supplementary
 * signal, but must perform additional due diligence to confirm the install nature of the package.
 *
 * @hide
 */
@FlaggedApi(Flags.FLAG_BIC_CLIENT)
@SystemApi(client = PRIVILEGED_APPS)
@SystemService(Context.BACKGROUND_INSTALL_CONTROL_SERVICE)
public final class BackgroundInstallControlManager {

    private static final String TAG = "BackgroundInstallControlManager";
    private static IBackgroundInstallControlService sService;
    private static ArrayList<CallbackDelegate> sRegisteredDelegates = new ArrayList<>();

    private final Context mContext;

    BackgroundInstallControlManager(Context context) {
        mContext = context;
    }

    private static IBackgroundInstallControlService getService() {
        if (sService == null) {
            sService =
                    IBackgroundInstallControlService.Stub.asInterface(
                            ServiceManager.getService(Context.BACKGROUND_INSTALL_CONTROL_SERVICE));
        }
        return sService;
    }

    /**
     * Returns a list of {@link PackageInfo} that are considered installed in the background.
     *
     * <p>Refer to top level doc {@link BackgroundInstallControlManager} for more details on
     * background-installed applications.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_BIC_CLIENT)
    @SystemApi
    @RequiresPermission(allOf = {QUERY_ALL_PACKAGES, INTERACT_ACROSS_USERS_FULL})
    public @NonNull List<PackageInfo> getBackgroundInstalledPackages(
            @PackageManager.PackageInfoFlagsBits long flags) {
        try {
            return getService()
                    .getBackgroundInstalledPackages(flags, mContext.getUserId())
                    .getList();
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Registers user supplied callback function for invocation in the event of an app installed in
     * the background.
     *
     * <p>Note: This API does not back-propagate events prior to successful registration. If client
     * is looking for historical background installs, use getBackgroundInstalledPackages retrieve
     * those records.
     *
     * <p>Relevant event details passed back to callback via {@link InstallEvent} object.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_BIC_CLIENT)
    @SystemApi
    @RequiresPermission(allOf = {QUERY_ALL_PACKAGES, INTERACT_ACROSS_USERS_FULL})
    public void registerBackgroundInstallCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<InstallEvent> callback) {
        CallbackDelegate delegate = new CallbackDelegate(executor, callback);
        try {
            getService().registerBackgroundInstallCallback(delegate.mBicCallback);
            sRegisteredDelegates.add(delegate);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Unregisters the provided callback from {@link BackgroundInstallControlManager}.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_BIC_CLIENT)
    @SystemApi
    @RequiresPermission(allOf = {QUERY_ALL_PACKAGES, INTERACT_ACROSS_USERS_FULL})
    public void unregisterBackgroundInstallCallback(@NonNull Consumer<InstallEvent> callback) {
        try {
            Iterator<CallbackDelegate> it = sRegisteredDelegates.iterator();
            CallbackDelegate delegate;
            while (it.hasNext()) {
                delegate = it.next();
                if (delegate.mCallback.equals(callback)) {
                    getService().unregisterBackgroundInstallCallback(delegate.mBicCallback);
                    it.remove();
                    return;
                }
            }
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Passed-in parameter for BackgroundInstallControlManager callbacks.
     *
     * <p>User provided callbacks access to relevant info of the install event is access through
     * this class.
     */
    public static final class InstallEvent {
        @NonNull private String mPackageName;

        @NonNull private int mUserId;

        /**
         * Constructor for InstallEvent parameter. Used and instantiated by CallbackDelegate to pass
         * into user provided callback.
         *
         * @param packageName - package name of the background installed app.
         * @param userId - ID of user the package was installed for.
         */
        public InstallEvent(@NonNull int userId, @NonNull String packageName) {
            mUserId = userId;
            mPackageName = packageName;
        }

        /**
         * Getter for package name of the background installed app.
         *
         * @return packageName
         */
        @NonNull
        public String getPackageName() {
            return mPackageName;
        }

        /**
         * Getter for user ID the app was installed for.
         *
         * @return userId
         */
        @NonNull
        public int getUserId() {
            return mUserId;
        }
    }

    private static class CallbackDelegate {
        static final String FLAGGED_PACKAGE_NAME_KEY = "packageName";
        static final String FLAGGED_USER_ID_KEY = "userId";
        final Executor mExecutor;
        final Consumer<InstallEvent> mCallback;
        final IRemoteCallback mBicCallback =
                new IRemoteCallback.Stub() {
                    @Override
                    public void sendResult(Bundle extras) {
                        mExecutor.execute(
                                () ->
                                        mCallback.accept(
                                                new InstallEvent(
                                                        extras.getInt(FLAGGED_USER_ID_KEY),
                                                        extras.getString(
                                                                FLAGGED_PACKAGE_NAME_KEY))));
                    }
                };

        CallbackDelegate(Executor executor, Consumer<InstallEvent> callback) {
            mExecutor = executor;
            mCallback = callback;
        }
    }
}
