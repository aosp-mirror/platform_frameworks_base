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
import android.provider.DeviceConfigInterface;
import android.util.ArraySet;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;

import java.io.PrintWriter;

/**
 * A Denylist for packages that should force the display out of high refresh rate.
 */
class HighRefreshRateDenylist {

    private final ArraySet<String> mDenylistedPackages = new ArraySet<>();
    @NonNull
    private final String[] mDefaultDenylist;
    private final Object mLock = new Object();

    static HighRefreshRateDenylist create(@NonNull Resources r) {
        return new HighRefreshRateDenylist(r, DeviceConfigInterface.REAL);
    }

    @VisibleForTesting
    HighRefreshRateDenylist(Resources r, DeviceConfigInterface deviceConfig) {
        mDefaultDenylist = r.getStringArray(R.array.config_highRefreshRateBlacklist);
        deviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                BackgroundThread.getExecutor(), new OnPropertiesChangedListener());
        final String property = deviceConfig.getProperty(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                KEY_HIGH_REFRESH_RATE_BLACKLIST);
        updateDenylist(property);
    }

    private void updateDenylist(@Nullable String property) {
        synchronized (mLock) {
            mDenylistedPackages.clear();
            if (property != null) {
                String[] packages = property.split(",");
                for (String pkg : packages) {
                    String pkgName = pkg.trim();
                    if (!pkgName.isEmpty()) {
                        mDenylistedPackages.add(pkgName);
                    }
                }
            } else {
                // If there's no config, or the config has been deleted, fallback to the device's
                // default denylist
                for (String pkg : mDefaultDenylist) {
                    mDenylistedPackages.add(pkg);
                }
            }
        }
    }

    boolean isDenylisted(String packageName) {
        synchronized (mLock) {
            return mDenylistedPackages.contains(packageName);
        }
    }
    void dump(PrintWriter pw) {
        pw.println("High Refresh Rate Denylist");
        pw.println("  Packages:");
        synchronized (mLock) {
            for (String pkg : mDenylistedPackages) {
                pw.println("    " + pkg);
            }
        }
    }

    private class OnPropertiesChangedListener implements DeviceConfig.OnPropertiesChangedListener {
        public void onPropertiesChanged(@NonNull DeviceConfig.Properties properties) {
            if (properties.getKeyset().contains(KEY_HIGH_REFRESH_RATE_BLACKLIST)) {
                updateDenylist(
                        properties.getString(KEY_HIGH_REFRESH_RATE_BLACKLIST, null /*default*/));
            }
        }
    }
}

