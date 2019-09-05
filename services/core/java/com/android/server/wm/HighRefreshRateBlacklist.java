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

package com.android.server.wm;

import static android.hardware.display.DisplayManager.DeviceConfig.KEY_HIGH_REFRESH_RATE_BLACKLIST;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Resources;
import android.provider.DeviceConfig;
import android.util.ArraySet;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;

import java.io.PrintWriter;
import java.util.concurrent.Executor;

/**
 * A Blacklist for packages that should force the display out of high refresh rate.
 */
class HighRefreshRateBlacklist {

    private final ArraySet<String> mBlacklistedPackages = new ArraySet<>();
    @NonNull
    private final String[] mDefaultBlacklist;
    private final Object mLock = new Object();

    static HighRefreshRateBlacklist create(@NonNull Resources r) {
        return new HighRefreshRateBlacklist(r, new DeviceConfigInterface() {
            @Override
            public @Nullable String getProperty(@NonNull String namespace, @NonNull String name) {
                return DeviceConfig.getProperty(namespace, name);
            }
            public void addOnPropertyChangedListener(@NonNull String namespace,
                    @NonNull Executor executor,
                    @NonNull DeviceConfig.OnPropertyChangedListener listener) {
                DeviceConfig.addOnPropertyChangedListener(namespace, executor, listener);
            }
        });
    }

    @VisibleForTesting
    HighRefreshRateBlacklist(Resources r, DeviceConfigInterface deviceConfig) {
        mDefaultBlacklist = r.getStringArray(R.array.config_highRefreshRateBlacklist);
        deviceConfig.addOnPropertyChangedListener(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                BackgroundThread.getExecutor(), new OnPropertyChangedListener());
        final String property = deviceConfig.getProperty(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                KEY_HIGH_REFRESH_RATE_BLACKLIST);
        updateBlacklist(property);
    }

    private void updateBlacklist(@Nullable String property) {
        synchronized (mLock) {
            mBlacklistedPackages.clear();
            if (property != null) {
                String[] packages = property.split(",");
                for (String pkg : packages) {
                    String pkgName = pkg.trim();
                    if (!pkgName.isEmpty()) {
                        mBlacklistedPackages.add(pkgName);
                    }
                }
            } else {
                // If there's no config, or the config has been deleted, fallback to the device's
                // default blacklist
                for (String pkg : mDefaultBlacklist) {
                    mBlacklistedPackages.add(pkg);
                }
            }
        }
    }

    boolean isBlacklisted(String packageName) {
        synchronized (mLock) {
            return mBlacklistedPackages.contains(packageName);
        }
    }
    void dump(PrintWriter pw) {
        pw.println("High Refresh Rate Blacklist");
        pw.println("  Packages:");
        synchronized (mLock) {
            for (String pkg : mBlacklistedPackages) {
                pw.println("    " + pkg);
            }
        }
    }

    interface DeviceConfigInterface {
        @Nullable String getProperty(@NonNull String namespace, @NonNull String name);
        void addOnPropertyChangedListener(@NonNull String namespace, @NonNull Executor executor,
                @NonNull DeviceConfig.OnPropertyChangedListener listener);
    }

    private class OnPropertyChangedListener implements DeviceConfig.OnPropertyChangedListener {
        public void onPropertyChanged(@NonNull String namespace, @NonNull String name,
                @Nullable String value) {
            if (KEY_HIGH_REFRESH_RATE_BLACKLIST.equals(name)) {
                updateBlacklist(value);
            }
        }
    }
}

