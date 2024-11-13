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

package com.android.server.display;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.DeviceConfigInterface;
import android.util.ArrayMap;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.server.LocalServices;
import com.android.server.pm.pkg.PackageStateInternal;

import java.io.PrintWriter;
import java.util.Map;

final class SmallAreaDetectionController {
    private static native void nativeUpdateSmallAreaDetection(int[] appIds, float[] thresholds);
    private static native void nativeSetSmallAreaDetectionThreshold(int appId, float threshold);

    // TODO(b/281720315): Move this to DeviceConfig once server side ready.
    private static final String KEY_SMALL_AREA_DETECTION_ALLOWLIST =
            "small_area_detection_allowlist";

    private final Object mLock = new Object();
    private final Context mContext;
    private final PackageManagerInternal mPackageManager;
    @GuardedBy("mLock")
    private final Map<String, Float> mAllowPkgMap = new ArrayMap<>();

    static SmallAreaDetectionController create(@NonNull Context context) {
        final SmallAreaDetectionController controller =
                new SmallAreaDetectionController(context, DeviceConfigInterface.REAL);
        final String property = DeviceConfigInterface.REAL.getProperty(
                DeviceConfig.NAMESPACE_DISPLAY_MANAGER, KEY_SMALL_AREA_DETECTION_ALLOWLIST);
        controller.updateAllowlist(property);
        return controller;
    }

    @VisibleForTesting
    SmallAreaDetectionController(Context context, DeviceConfigInterface deviceConfig) {
        mContext = context;
        mPackageManager = LocalServices.getService(PackageManagerInternal.class);
        deviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                BackgroundThread.getExecutor(),
                new SmallAreaDetectionController.OnPropertiesChangedListener());
        mPackageManager.getPackageList(new PackageReceiver());
    }

    @VisibleForTesting
    void updateAllowlist(@Nullable String property) {
        final Map<String, Float> allowPkgMap = new ArrayMap<>();
        synchronized (mLock) {
            mAllowPkgMap.clear();
            if (property != null) {
                final String[] mapStrings = property.split(",");
                for (String mapString : mapStrings) putToAllowlist(mapString);
            } else {
                final String[] defaultMapStrings = mContext.getResources()
                        .getStringArray(R.array.config_smallAreaDetectionAllowlist);
                for (String defaultMapString : defaultMapStrings) putToAllowlist(defaultMapString);
            }

            if (mAllowPkgMap.isEmpty()) return;
            allowPkgMap.putAll(mAllowPkgMap);
        }
        updateSmallAreaDetection(allowPkgMap);
    }

    @GuardedBy("mLock")
    private void putToAllowlist(String rowData) {
        // Data format: package:threshold - e.g. "com.abc.music:0.05"
        final String[] items = rowData.split(":");
        if (items.length == 2) {
            try {
                final String pkg = items[0];
                final float threshold = Float.valueOf(items[1]);
                mAllowPkgMap.put(pkg, threshold);
            } catch (Exception e) {
                // Just skip if items[1] - the threshold is not parsable number
            }
        }
    }

    private void updateSmallAreaDetection(Map<String, Float> allowPkgMap) {
        final SparseArray<Float> appIdThresholdList = new SparseArray(allowPkgMap.size());
        for (String pkg : allowPkgMap.keySet()) {
            final float threshold = allowPkgMap.get(pkg);
            final PackageStateInternal stage = mPackageManager.getPackageStateInternal(pkg);
            if (stage != null) {
                appIdThresholdList.put(stage.getAppId(), threshold);
            }
        }

        final int[] appIds = new int[appIdThresholdList.size()];
        final float[] thresholds = new float[appIdThresholdList.size()];
        for (int i = 0; i < appIdThresholdList.size();  i++) {
            appIds[i] = appIdThresholdList.keyAt(i);
            thresholds[i] = appIdThresholdList.valueAt(i);
        }
        updateSmallAreaDetection(appIds, thresholds);
    }

    @VisibleForTesting
    void updateSmallAreaDetection(int[] appIds, float[] thresholds) {
        nativeUpdateSmallAreaDetection(appIds, thresholds);
    }

    void setSmallAreaDetectionThreshold(int appId, float threshold) {
        nativeSetSmallAreaDetectionThreshold(appId, threshold);
    }

    void dump(PrintWriter pw) {
        pw.println("Small area detection allowlist:");
        pw.println("-------------------------------");
        pw.println("  Packages:");
        synchronized (mLock) {
            for (String pkg : mAllowPkgMap.keySet()) {
                pw.println("    " + pkg + " threshold = " + mAllowPkgMap.get(pkg));
            }
        }
    }

    private class OnPropertiesChangedListener implements DeviceConfig.OnPropertiesChangedListener {
        public void onPropertiesChanged(@NonNull DeviceConfig.Properties properties) {
            if (properties.getKeyset().contains(KEY_SMALL_AREA_DETECTION_ALLOWLIST)) {
                updateAllowlist(
                        properties.getString(KEY_SMALL_AREA_DETECTION_ALLOWLIST, null /*default*/));
            }
        }
    }

    private final class PackageReceiver implements PackageManagerInternal.PackageListObserver {
        @Override
        public void onPackageAdded(@NonNull String packageName, int uid) {
            float threshold = 0.0f;
            synchronized (mLock) {
                if (mAllowPkgMap.containsKey(packageName)) {
                    threshold = mAllowPkgMap.get(packageName);
                }
            }
            if (threshold > 0.0f) {
                setSmallAreaDetectionThreshold(UserHandle.getAppId(uid), threshold);
            }
        }
    }
}
