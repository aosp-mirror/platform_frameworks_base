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
import android.provider.DeviceConfig;
import android.provider.DeviceConfigInterface;
import android.util.ArrayMap;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Map;

final class SmallAreaDetectionController {
    private static native void nativeUpdateSmallAreaDetection(int[] uids, float[] thresholds);
    private static native void nativeSetSmallAreaDetectionThreshold(int uid, float threshold);

    // TODO(b/281720315): Move this to DeviceConfig once server side ready.
    private static final String KEY_SMALL_AREA_DETECTION_ALLOWLIST =
            "small_area_detection_allowlist";

    private final Object mLock = new Object();
    private final Context mContext;
    private final PackageManagerInternal mPackageManager;
    private final UserManagerInternal mUserManager;
    @GuardedBy("mLock")
    private final Map<String, Float> mAllowPkgMap = new ArrayMap<>();
    // TODO(b/298722189): Update allowlist when user changes
    @GuardedBy("mLock")
    private int[] mUserIds;

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
        mUserManager = LocalServices.getService(UserManagerInternal.class);
        deviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_DISPLAY_MANAGER,
                BackgroundThread.getExecutor(),
                new SmallAreaDetectionController.OnPropertiesChangedListener());
        mPackageManager.getPackageList(new PackageReceiver());
    }

    @VisibleForTesting
    void updateAllowlist(@Nullable String property) {
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
            updateSmallAreaDetection();
        }
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

    @GuardedBy("mLock")
    private void updateUidListForAllUsers(SparseArray<Float> list, String pkg, float threshold) {
        for (int i = 0; i < mUserIds.length; i++) {
            final int userId = mUserIds[i];
            final int uid = mPackageManager.getPackageUid(pkg, 0, userId);
            if (uid > 0) list.put(uid, threshold);
        }
    }

    @GuardedBy("mLock")
    private void updateSmallAreaDetection() {
        if (mAllowPkgMap.isEmpty()) return;

        mUserIds = mUserManager.getUserIds();

        final SparseArray<Float> uidThresholdList = new SparseArray<>();
        for (String pkg : mAllowPkgMap.keySet()) {
            final float threshold = mAllowPkgMap.get(pkg);
            updateUidListForAllUsers(uidThresholdList, pkg, threshold);
        }

        final int[] uids = new int[uidThresholdList.size()];
        final float[] thresholds = new float[uidThresholdList.size()];
        for (int i = 0; i < uidThresholdList.size();  i++) {
            uids[i] = uidThresholdList.keyAt(i);
            thresholds[i] = uidThresholdList.valueAt(i);
        }
        updateSmallAreaDetection(uids, thresholds);
    }

    @VisibleForTesting
    void updateSmallAreaDetection(int[] uids, float[] thresholds) {
        nativeUpdateSmallAreaDetection(uids, thresholds);
    }

    void setSmallAreaDetectionThreshold(int uid, float threshold) {
        nativeSetSmallAreaDetectionThreshold(uid, threshold);
    }

    void dump(PrintWriter pw) {
        pw.println("Small area detection allowlist");
        pw.println("  Packages:");
        synchronized (mLock) {
            for (String pkg : mAllowPkgMap.keySet()) {
                pw.println("    " + pkg + " threshold = " + mAllowPkgMap.get(pkg));
            }
            pw.println("  mUserIds=" + Arrays.toString(mUserIds));
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
            synchronized (mLock) {
                if (mAllowPkgMap.containsKey(packageName)) {
                    setSmallAreaDetectionThreshold(uid, mAllowPkgMap.get(packageName));
                }
            }
        }
    }
}
