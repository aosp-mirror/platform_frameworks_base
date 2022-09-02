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

package com.android.server.wm;

import static android.provider.DeviceConfig.NAMESPACE_WINDOW_MANAGER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.provider.DeviceConfig;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Handles filtering the list of package that don't use the system splash screen.
 * The list is backed by a {@link DeviceConfig} property.
 * <p>
 * An application can manually opt-out of the exception list by setting the &lt;meta-data
 * {@value OPT_OUT_METADATA_FLAG}="true"/&gt; in the <code>&lt;application&gt;</code> section of the
 * manifest.
 */
class SplashScreenExceptionList {

    private static final boolean DEBUG = Build.isDebuggable();
    private static final String LOG_TAG = "SplashScreenExceptionList";
    private static final String KEY_SPLASH_SCREEN_EXCEPTION_LIST = "splash_screen_exception_list";
    private static final String NAMESPACE = NAMESPACE_WINDOW_MANAGER;
    private static final String OPT_OUT_METADATA_FLAG = "android.splashscreen.exception_opt_out";

    @GuardedBy("mLock")
    private final HashSet<String> mDeviceConfigExcludedPackages = new HashSet<>();
    private final Object mLock = new Object();

    @VisibleForTesting
    final DeviceConfig.OnPropertiesChangedListener mOnPropertiesChangedListener;

    SplashScreenExceptionList(@NonNull Executor executor) {
        updateDeviceConfig(DeviceConfig.getString(NAMESPACE, KEY_SPLASH_SCREEN_EXCEPTION_LIST, ""));
        mOnPropertiesChangedListener = properties -> updateDeviceConfig(
                properties.getString(KEY_SPLASH_SCREEN_EXCEPTION_LIST, ""));
        DeviceConfig.addOnPropertiesChangedListener(NAMESPACE, executor,
                mOnPropertiesChangedListener);
    }

    @VisibleForTesting
    void updateDeviceConfig(String values) {
        parseDeviceConfigPackageList(values);
    }

    /**
     * Returns true if the packageName is in the list and the target sdk is before or including T.
     *
     * @param packageName  The package name of the application to check
     * @param targetSdk    The target sdk of the application
     * @param infoSupplier A {@link Supplier} that returns an {@link ApplicationInfo} used to
     *                     check if the application wants to opt-out of the exception list in the
     *                     manifest metadata. Evaluated only if the application is in the exception
     *                     list.
     */
    @SuppressWarnings("AndroidFrameworkCompatChange") // Target sdk check
    public boolean isException(@NonNull String packageName, int targetSdk,
            @Nullable Supplier<ApplicationInfo> infoSupplier) {
        if (targetSdk > Build.VERSION_CODES.TIRAMISU) {
            return false;
        }

        synchronized (mLock) {
            if (DEBUG) {
                Slog.v(LOG_TAG, String.format(Locale.US,
                        "SplashScreen checking exception for package %s (target sdk:%d) -> %s",
                        packageName, targetSdk,
                        mDeviceConfigExcludedPackages.contains(packageName)));
            }
            if (!mDeviceConfigExcludedPackages.contains(packageName)) {
                return false;
            }
        }
        return !isOptedOut(infoSupplier);
    }

    /**
     * An application can manually opt-out of the exception list by setting the meta-data
     * {@value OPT_OUT_METADATA_FLAG} = true in the <code>application</code> section of the manifest
     */
    private static boolean isOptedOut(@Nullable Supplier<ApplicationInfo> infoProvider) {
        if (infoProvider == null) {
            return false;
        }
        ApplicationInfo info = infoProvider.get();
        return info != null && info.metaData != null && info.metaData.getBoolean(
                OPT_OUT_METADATA_FLAG, false);
    }

    private void parseDeviceConfigPackageList(String rawList) {
        synchronized (mLock) {
            mDeviceConfigExcludedPackages.clear();
            String[] packages = rawList.split(",");
            for (String packageName : packages) {
                String packageNameTrimmed = packageName.trim();
                if (!packageNameTrimmed.isEmpty()) {
                    mDeviceConfigExcludedPackages.add(packageNameTrimmed);
                }
            }
        }
    }
}
