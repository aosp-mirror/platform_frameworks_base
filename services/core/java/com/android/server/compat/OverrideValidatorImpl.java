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

import static com.android.internal.compat.OverrideAllowedState.ALLOWED;
import static com.android.internal.compat.OverrideAllowedState.DISABLED_NON_TARGET_SDK;
import static com.android.internal.compat.OverrideAllowedState.DISABLED_NOT_DEBUGGABLE;
import static com.android.internal.compat.OverrideAllowedState.DISABLED_TARGET_SDK_TOO_HIGH;
import static com.android.internal.compat.OverrideAllowedState.LOGGING_ONLY_CHANGE;
import static com.android.internal.compat.OverrideAllowedState.PACKAGE_DOES_NOT_EXIST;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;

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

    @VisibleForTesting
    OverrideValidatorImpl(AndroidBuildClassifier androidBuildClassifier,
                          Context context, CompatConfig config) {
        mAndroidBuildClassifier = androidBuildClassifier;
        mContext = context;
        mCompatConfig = config;
    }

    @Override
    public OverrideAllowedState getOverrideAllowedState(long changeId, String packageName) {
        if (mCompatConfig.isLoggingOnly(changeId)) {
            return new OverrideAllowedState(LOGGING_ONLY_CHANGE, -1, -1);
        }

        boolean debuggableBuild = mAndroidBuildClassifier.isDebuggableBuild();
        boolean finalBuild = mAndroidBuildClassifier.isFinalBuild();
        int minTargetSdk = mCompatConfig.minTargetSdkForChangeId(changeId);
        boolean disabled = mCompatConfig.isDisabled(changeId);

        // Allow any override for userdebug or eng builds.
        if (debuggableBuild) {
            return new OverrideAllowedState(ALLOWED, -1, -1);
        }
        PackageManager packageManager = mContext.getPackageManager();
        if (packageManager == null) {
            throw new IllegalStateException("No PackageManager!");
        }
        ApplicationInfo applicationInfo;
        try {
            applicationInfo = packageManager.getApplicationInfo(packageName, 0);
        } catch (NameNotFoundException e) {
            return new OverrideAllowedState(PACKAGE_DOES_NOT_EXIST, -1, -1);
        }
        int appTargetSdk = applicationInfo.targetSdkVersion;
        // Only allow overriding debuggable apps.
        if ((applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            return new OverrideAllowedState(DISABLED_NOT_DEBUGGABLE, -1, -1);
        }
        // Allow overriding any change for debuggable apps on non-final builds.
        if (!finalBuild) {
            return new OverrideAllowedState(ALLOWED, appTargetSdk, minTargetSdk);
        }
        // Do not allow overriding default enabled changes on user builds
        if (minTargetSdk == -1 && !disabled) {
            return new OverrideAllowedState(DISABLED_NON_TARGET_SDK, appTargetSdk, minTargetSdk);
        }
        // Only allow to opt-in for a targetSdk gated change.
        if (disabled || appTargetSdk <= minTargetSdk) {
            return new OverrideAllowedState(ALLOWED, appTargetSdk, minTargetSdk);
        }
        return new OverrideAllowedState(DISABLED_TARGET_SDK_TOO_HIGH, appTargetSdk, minTargetSdk);
    }
}
