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

import static com.android.server.wm.ActivityInterceptorCallback.COMMUNAL_MODE_ORDERED_ID;

import android.Manifest;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.communal.ICommunalManager;
import android.content.Context;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.LaunchAfterAuthenticationActivity;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.wm.ActivityInterceptorCallback;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * System service for handling Communal Mode state.
 */
public final class CommunalManagerService extends SystemService {
    private static final String DELIMITER = ",";
    private final Context mContext;
    private final ActivityTaskManagerInternal mAtmInternal;
    private final KeyguardManager mKeyguardManager;
    private final AtomicBoolean mCommunalViewIsShowing = new AtomicBoolean(false);
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Set<String> mEnabledApps = new HashSet<>();
    private final SettingsObserver mSettingsObserver;

    private final ActivityInterceptorCallback mActivityInterceptorCallback =
            new ActivityInterceptorCallback() {
                @Nullable
                @Override
                public Intent intercept(ActivityInterceptorInfo info) {
                    if (isActivityAllowed(info.aInfo)) {
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
        mSettingsObserver = new SettingsObserver();
        mAtmInternal = LocalServices.getService(ActivityTaskManagerInternal.class);
        mKeyguardManager = mContext.getSystemService(KeyguardManager.class);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.COMMUNAL_MANAGER_SERVICE, new BinderService());
        mAtmInternal.registerActivityStartInterceptor(COMMUNAL_MODE_ORDERED_ID,
                mActivityInterceptorCallback);


        updateSelectedApps();
        mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                Settings.Secure.COMMUNAL_MODE_PACKAGES), false, mSettingsObserver,
                UserHandle.USER_SYSTEM);
    }

    @VisibleForTesting
    void updateSelectedApps() {
        final String encodedApps = Settings.Secure.getStringForUser(
                mContext.getContentResolver(),
                Settings.Secure.COMMUNAL_MODE_PACKAGES,
                UserHandle.USER_SYSTEM);

        mEnabledApps.clear();

        if (!TextUtils.isEmpty(encodedApps)) {
            mEnabledApps.addAll(Arrays.asList(encodedApps.split(DELIMITER)));
        }
    }

    private boolean isActivityAllowed(ActivityInfo activityInfo) {
        if (!mCommunalViewIsShowing.get() || !mKeyguardManager.isKeyguardLocked()) return true;

        // If the activity doesn't have showWhenLocked enabled, disallow the activity.
        final boolean showWhenLocked =
                (activityInfo.flags & ActivityInfo.FLAG_SHOW_WHEN_LOCKED) != 0;
        if (!showWhenLocked) {
            return false;
        }

        // Check the cached user preferences to see if the user has allowed this app.
        return mEnabledApps.contains(activityInfo.applicationInfo.packageName);
    }

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver() {
            super(mHandler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            mContext.getMainExecutor().execute(CommunalManagerService.this::updateSelectedApps);
        }
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
}
