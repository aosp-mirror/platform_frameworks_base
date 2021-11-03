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

package com.android.server.communal;

import static android.app.ActivityManager.INTENT_SENDER_ACTIVITY;
import static android.content.Intent.ACTION_PACKAGE_REMOVED;

import static com.android.server.wm.ActivityInterceptorCallback.COMMUNAL_MODE_ORDERED_ID;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.TestApi;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.communal.ICommunalManager;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.Disabled;
import android.compat.annotation.Overridable;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.dreams.DreamManagerInternal;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.LaunchAfterAuthenticationActivity;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.wm.ActivityInterceptorCallback;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * System service for handling Communal Mode state.
 */
public final class CommunalManagerService extends SystemService {
    private static final String TAG = CommunalManagerService.class.getSimpleName();
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String DELIMITER = ",";
    private final Context mContext;
    private final ActivityTaskManagerInternal mAtmInternal;
    private final KeyguardManager mKeyguardManager;
    private final AtomicBoolean mCommunalViewIsShowing = new AtomicBoolean(false);
    private final BinderService mBinderService;
    private final PackageReceiver mPackageReceiver;
    private final PackageManager mPackageManager;
    private final DreamManagerInternal mDreamManagerInternal;

    /**
     * This change id is used to annotate packages which are allowed to run in communal mode.
     *
     * @hide
     */
    @ChangeId
    @Overridable
    @Disabled
    @TestApi
    public static final long ALLOW_COMMUNAL_MODE_WITH_USER_CONSENT = 200324021L;

    /**
     * This change id is used to annotate packages which can run in communal mode by default,
     * without requiring user opt-in.
     *
     * @hide
     */
    @ChangeId
    @Overridable
    @Disabled
    @TestApi
    public static final long ALLOW_COMMUNAL_MODE_BY_DEFAULT = 203673428L;

    private final ActivityInterceptorCallback mActivityInterceptorCallback =
            new ActivityInterceptorCallback() {
                @Nullable
                @Override
                public Intent intercept(ActivityInterceptorInfo info) {
                    if (!shouldIntercept(info.aInfo)) {
                        return null;
                    }

                    final IIntentSender target = mAtmInternal.getIntentSender(
                            INTENT_SENDER_ACTIVITY,
                            info.callingPackage,
                            info.callingFeatureId,
                            info.callingUid,
                            info.userId,
                            /* token= */null,
                            /* resultWho= */ null,
                            /* requestCode= */ 0,
                            new Intent[]{info.intent},
                            new String[]{info.resolvedType},
                            PendingIntent.FLAG_IMMUTABLE,
                            /* bOptions= */ null);

                    return LaunchAfterAuthenticationActivity.createLaunchAfterAuthenticationIntent(
                            new IntentSender(target));

                }
            };

    public CommunalManagerService(Context context) {
        super(context);
        mContext = context;
        mPackageManager = mContext.getPackageManager();
        mAtmInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
        mDreamManagerInternal = LocalServices.getService(DreamManagerInternal.class);
        mKeyguardManager = mContext.getSystemService(KeyguardManager.class);
        mBinderService = new BinderService();
        mPackageReceiver = new PackageReceiver(mContext);
    }

    @VisibleForTesting
    BinderService getBinderServiceInstance() {
        return mBinderService;
    }

    @Override
    public void onStart() {
        publishBinderService(Context.COMMUNAL_MANAGER_SERVICE, mBinderService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase != SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) return;
        mAtmInternal.registerActivityStartInterceptor(
                COMMUNAL_MODE_ORDERED_ID,
                mActivityInterceptorCallback);
        mPackageReceiver.register();
        removeUninstalledPackagesFromSettings();
    }

    @Override
    public void finalize() {
        mPackageReceiver.unregister();
    }

    private Set<String> getUserEnabledApps() {
        final String encodedApps = Settings.Secure.getStringForUser(
                mContext.getContentResolver(),
                Settings.Secure.COMMUNAL_MODE_PACKAGES,
                UserHandle.USER_SYSTEM);

        return TextUtils.isEmpty(encodedApps)
                ? Collections.emptySet()
                : new HashSet<>(Arrays.asList(encodedApps.split(DELIMITER)));
    }

    private void removeUninstalledPackagesFromSettings() {
        for (String packageName : getUserEnabledApps()) {
            if (!isPackageInstalled(packageName, mPackageManager)) {
                removePackageFromSettings(packageName);
            }
        }
    }

    private void removePackageFromSettings(String packageName) {
        Set<String> enabledPackages = getUserEnabledApps();
        if (enabledPackages.remove(packageName)) {
            Settings.Secure.putStringForUser(
                    mContext.getContentResolver(),
                    Settings.Secure.COMMUNAL_MODE_PACKAGES,
                    String.join(DELIMITER, enabledPackages),
                    UserHandle.USER_SYSTEM);
        }
    }

    @VisibleForTesting
    static boolean isPackageInstalled(String packageName, PackageManager packageManager) {
        if (packageManager == null) return false;
        try {
            return packageManager.getPackageInfo(packageName, 0) != null;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean isAppAllowed(ApplicationInfo appInfo) {
        if (isActiveDream(appInfo) || isChangeEnabled(ALLOW_COMMUNAL_MODE_BY_DEFAULT, appInfo)) {
            return true;
        }

        return isChangeEnabled(ALLOW_COMMUNAL_MODE_WITH_USER_CONSENT, appInfo)
                && getUserEnabledApps().contains(appInfo.packageName);
    }

    private boolean isActiveDream(ApplicationInfo appInfo) {
        final ComponentName activeDream = mDreamManagerInternal.getActiveDreamComponent(
                /* doze= */ false);
        final ComponentName activeDoze = mDreamManagerInternal.getActiveDreamComponent(
                /* doze= */ true);
        return isFromPackage(activeDream, appInfo) || isFromPackage(activeDoze, appInfo);
    }

    private static boolean isFromPackage(ComponentName componentName, ApplicationInfo appInfo) {
        if (componentName == null) return false;
        return TextUtils.equals(appInfo.packageName, componentName.getPackageName());
    }

    private static boolean isChangeEnabled(long changeId, ApplicationInfo appInfo) {
        return CompatChanges.isChangeEnabled(changeId, appInfo.packageName, UserHandle.SYSTEM);
    }

    private boolean shouldIntercept(ActivityInfo activityInfo) {
        if (!mCommunalViewIsShowing.get() || !mKeyguardManager.isKeyguardLocked()) return false;
        return !isAppAllowed(activityInfo.applicationInfo);
    }

    private final class BinderService extends ICommunalManager.Stub {
        /**
         * Sets whether or not we are in communal mode.
         */
        @RequiresPermission(Manifest.permission.WRITE_COMMUNAL_STATE)
        @Override
        public void setCommunalViewShowing(boolean isShowing) {
            mContext.enforceCallingPermission(Manifest.permission.WRITE_COMMUNAL_STATE,
                    Manifest.permission.WRITE_COMMUNAL_STATE
                            + "permission required to modify communal state.");
            mCommunalViewIsShowing.set(isShowing);
        }
    }

    /**
     * A {@link BroadcastReceiver} that listens on package removed events and updates any stored
     * package state in Settings.
     */
    private final class PackageReceiver extends BroadcastReceiver {
        private final Context mContext;
        private final IntentFilter mIntentFilter;

        private PackageReceiver(Context context) {
            mContext = context;
            mIntentFilter = new IntentFilter();
            mIntentFilter.addAction(ACTION_PACKAGE_REMOVED);
            mIntentFilter.addDataScheme("package");
        }

        private void register() {
            mContext.registerReceiverAsUser(
                    this,
                    UserHandle.SYSTEM,
                    mIntentFilter,
                    /* broadcastPermission= */null,
                    /* scheduler= */ null);
        }

        private void unregister() {
            mContext.unregisterReceiver(this);
        }

        @Override
        public void onReceive(@NonNull final Context context, @NonNull final Intent intent) {
            final Uri data = intent.getData();
            if (data == null) {
                Slog.w(TAG, "Failed to get package name in package receiver");
                return;
            }
            final String packageName = data.getSchemeSpecificPart();
            final String action = intent.getAction();
            if (ACTION_PACKAGE_REMOVED.equals(action)) {
                removePackageFromSettings(packageName);
            } else {
                Slog.w(TAG, "Unsupported action in package receiver: " + action);
            }
        }
    }
}
