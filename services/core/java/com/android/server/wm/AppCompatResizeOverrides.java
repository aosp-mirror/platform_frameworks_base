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

package com.android.server.wm;

import static android.content.pm.ActivityInfo.FORCE_NON_RESIZE_APP;
import static android.content.pm.ActivityInfo.FORCE_RESIZE_APP;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY;

import static com.android.server.wm.AppCompatUtils.isChangeEnabled;

import android.annotation.NonNull;
import android.content.pm.PackageManager;

import com.android.server.wm.utils.OptPropFactory;

import java.util.function.BooleanSupplier;

/**
 * Encapsulate app compat logic about resizability.
 */
class AppCompatResizeOverrides {

    @NonNull
    private final ActivityRecord mActivityRecord;

    @NonNull
    private final OptPropFactory.OptProp mAllowForceResizeOverrideOptProp;

    @NonNull
    private final BooleanSupplier mAllowRestrictedResizability;

    AppCompatResizeOverrides(@NonNull ActivityRecord activityRecord,
            @NonNull PackageManager packageManager,
            @NonNull OptPropFactory optPropBuilder) {
        mActivityRecord = activityRecord;
        mAllowForceResizeOverrideOptProp = optPropBuilder.create(
                PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES);
        mAllowRestrictedResizability = AppCompatUtils.asLazy(() -> {
            // Application level.
            if (allowRestrictedResizability(packageManager, mActivityRecord.packageName)) {
                return true;
            }
            // Activity level.
            try {
                return packageManager.getPropertyAsUser(
                        PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY,
                        mActivityRecord.mActivityComponent.getPackageName(),
                        mActivityRecord.mActivityComponent.getClassName(),
                        mActivityRecord.mUserId).getBoolean();
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        });
    }

    static boolean allowRestrictedResizability(PackageManager pm, String packageName) {
        try {
            return pm.getProperty(PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY, packageName)
                    .getBoolean();
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Whether we should apply the force resize per-app override. When this override is applied it
     * forces the packages it is applied to to be resizable. It won't change whether the app can be
     * put into multi-windowing mode, but allow the app to resize without going into size-compat
     * mode when the window container resizes, such as display size change or screen rotation.
     *
     * <p>This method returns {@code true} when the following conditions are met:
     * <ul>
     *     <li>Opt-out component property isn't enabled
     *     <li>Per-app override is enabled
     * </ul>
     */
    boolean shouldOverrideForceResizeApp() {
        return mAllowForceResizeOverrideOptProp.shouldEnableWithOptInOverrideAndOptOutProperty(
                isChangeEnabled(mActivityRecord, FORCE_RESIZE_APP));
    }

    /**
     * Whether we should apply the force non resize per-app override. When this override is applied
     * it forces the packages it is applied to to be non-resizable.
     *
     * <p>This method returns {@code true} when the following conditions are met:
     * <ul>
     *     <li>Opt-out component property isn't enabled
     *     <li>Per-app override is enabled
     * </ul>
     */
    boolean shouldOverrideForceNonResizeApp() {
        return mAllowForceResizeOverrideOptProp.shouldEnableWithOptInOverrideAndOptOutProperty(
                isChangeEnabled(mActivityRecord, FORCE_NON_RESIZE_APP));
    }

    /** @see android.view.WindowManager#PROPERTY_COMPAT_ALLOW_RESTRICTED_RESIZABILITY */
    boolean allowRestrictedResizability() {
        return mAllowRestrictedResizability.getAsBoolean();
    }
}
