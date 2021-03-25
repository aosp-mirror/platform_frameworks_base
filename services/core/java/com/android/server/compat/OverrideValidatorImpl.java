/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.compat;

import static android.Manifest.permission.OVERRIDE_COMPAT_CHANGE_CONFIG_ON_RELEASE_BUILD;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static com.android.internal.compat.OverrideAllowedState.ALLOWED;
import static com.android.internal.compat.OverrideAllowedState.DEFERRED_VERIFICATION;
import static com.android.internal.compat.OverrideAllowedState.DISABLED_NON_TARGET_SDK;
import static com.android.internal.compat.OverrideAllowedState.DISABLED_NOT_DEBUGGABLE;
import static com.android.internal.compat.OverrideAllowedState.DISABLED_TARGET_SDK_TOO_HIGH;
import static com.android.internal.compat.OverrideAllowedState.LOGGING_ONLY_CHANGE;
import static com.android.internal.compat.OverrideAllowedState.PLATFORM_TOO_OLD;

import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.ContentObserver;
import android.os.Handler;
import android.provider.Settings;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.compat.AndroidBuildClassifier;
import com.android.internal.compat.IOverrideValidator;
import com.android.internal.compat.OverrideAllowedState;

/**
 * Implementation of the policy for allowing compat change overrides.
 */
public class OverrideValidatorImpl extends IOverrideValidator.Stub {

    private AndroidBuildClassifier mAndroidBuildClassifier;
    private Context mContext;
    private CompatConfig mCompatConfig;
    private boolean mForceNonDebuggableFinalBuild;

    private class SettingsObserver extends ContentObserver {
        SettingsObserver() {
            super(new Handler());
        }
        @Override
        public void onChange(boolean selfChange) {
            mForceNonDebuggableFinalBuild = Settings.Global.getInt(
                mContext.getContentResolver(),
                Settings.Global.FORCE_NON_DEBUGGABLE_FINAL_BUILD_FOR_COMPAT,
                0) == 1;
        }
    }

    @VisibleForTesting
    OverrideValidatorImpl(AndroidBuildClassifier androidBuildClassifier,
                          Context context, CompatConfig config) {
        mAndroidBuildClassifier = androidBuildClassifier;
        mContext = context;
        mCompatConfig = config;
        mForceNonDebuggableFinalBuild = false;
    }

    /**
     * Check the allowed state for the given changeId and packageName on a recheck.
     *
     * <p>Recheck happens when the given app is getting updated. In this case we cannot do a
     * permission check on the caller, so we're using the fact that the override was present as
     * proof that the original caller was allowed to set this override.
     */
    OverrideAllowedState getOverrideAllowedStateForRecheck(long changeId,
            @NonNull String packageName) {
        return getOverrideAllowedStateInternal(changeId, packageName, true);
    }

    @Override
    public OverrideAllowedState getOverrideAllowedState(long changeId, String packageName) {
        return getOverrideAllowedStateInternal(changeId, packageName, false);
    }

    private OverrideAllowedState getOverrideAllowedStateInternal(long changeId, String packageName,
            boolean isRecheck) {
        if (mCompatConfig.isLoggingOnly(changeId)) {
            return new OverrideAllowedState(LOGGING_ONLY_CHANGE, -1, -1);
        }

        boolean debuggableBuild = mAndroidBuildClassifier.isDebuggableBuild()
                                    && !mForceNonDebuggableFinalBuild;
        boolean finalBuild = mAndroidBuildClassifier.isFinalBuild()
                                || mForceNonDebuggableFinalBuild;
        int maxTargetSdk = mCompatConfig.maxTargetSdkForChangeIdOptIn(changeId);
        boolean disabled = mCompatConfig.isDisabled(changeId);

        // Allow any override for userdebug or eng builds.
        if (debuggableBuild) {
            return new OverrideAllowedState(ALLOWED, -1, -1);
        }
        if (maxTargetSdk >= mAndroidBuildClassifier.platformTargetSdk()) {
            return new OverrideAllowedState(PLATFORM_TOO_OLD, -1, maxTargetSdk);
        }
        PackageManager packageManager = mContext.getPackageManager();
        if (packageManager == null) {
            throw new IllegalStateException("No PackageManager!");
        }
        ApplicationInfo applicationInfo;
        try {
            applicationInfo = packageManager.getApplicationInfo(packageName, 0);
        } catch (NameNotFoundException e) {
            return new OverrideAllowedState(DEFERRED_VERIFICATION, -1, -1);
        }
        // If the change is annotated as @Overridable, apps with the specific permission can
        // set the override even on production builds. When rechecking the override, e.g. during an
        // app update we can bypass this check, as it wouldn't have been here in the first place.
        if (mCompatConfig.isOverridable(changeId)
                && (isRecheck
                        || mContext.checkCallingOrSelfPermission(
                                OVERRIDE_COMPAT_CHANGE_CONFIG_ON_RELEASE_BUILD)
                                        == PERMISSION_GRANTED)) {
            return new OverrideAllowedState(ALLOWED, -1, -1);
        }
        int appTargetSdk = applicationInfo.targetSdkVersion;
        // Only allow overriding debuggable apps.
        if ((applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            return new OverrideAllowedState(DISABLED_NOT_DEBUGGABLE, -1, -1);
        }
        // Allow overriding any change for debuggable apps on non-final builds.
        if (!finalBuild) {
            return new OverrideAllowedState(ALLOWED, appTargetSdk, maxTargetSdk);
        }
        // Do not allow overriding default enabled changes on user builds
        if (maxTargetSdk == -1 && !disabled) {
            return new OverrideAllowedState(DISABLED_NON_TARGET_SDK, appTargetSdk, maxTargetSdk);
        }
        // Only allow to opt-in for a targetSdk gated change.
        if (disabled || appTargetSdk <= maxTargetSdk) {
            return new OverrideAllowedState(ALLOWED, appTargetSdk, maxTargetSdk);
        }
        return new OverrideAllowedState(DISABLED_TARGET_SDK_TOO_HIGH, appTargetSdk, maxTargetSdk);
    }

    void registerContentObserver() {
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(
                    Settings.Global.FORCE_NON_DEBUGGABLE_FINAL_BUILD_FOR_COMPAT),
                false,
                new SettingsObserver());
    }

    void forceNonDebuggableFinalForTest(boolean value) {
        mForceNonDebuggableFinalBuild = value;
    }
}
