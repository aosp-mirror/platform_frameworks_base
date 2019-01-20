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

package com.android.server.display;

import android.annotation.UserIdInt;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.ColorDisplayService.ColorTransformController;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

class AppSaturationController {

    private final Object mLock = new Object();

    /**
     * A package name has one or more userIds it is running under. Each userId has zero or one
     * saturation level, and zero or more ColorTransformControllers.
     */
    @GuardedBy("mLock")
    private final Map<String, SparseArray<SaturationController>> mAppsMap = new HashMap<>();

    @VisibleForTesting
    static final float[] TRANSLATION_VECTOR = {0f, 0f, 0f};

    /**
     * Add an {@link WeakReference<ColorTransformController>} for a given package and userId.
     */
    boolean addColorTransformController(String packageName, @UserIdInt int userId,
            WeakReference<ColorTransformController> controller) {
        synchronized (mLock) {
            return getSaturationControllerLocked(packageName, userId)
                    .addColorTransformController(controller);
        }
    }

    /**
     * Set the saturation level ({@code ColorDisplayManager#SaturationLevel} constant for a given
     * package name and userId.
     */
    public boolean setSaturationLevel(String packageName, @UserIdInt int userId,
            int saturationLevel) {
        synchronized (mLock) {
            return getSaturationControllerLocked(packageName, userId)
                    .setSaturationLevel(saturationLevel);
        }
    }

    /**
     * Dump state information.
     */
    public void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("App Saturation: ");
            if (mAppsMap.size() == 0) {
                pw.println("    No packages");
                return;
            }
            final List<String> packageNames = new ArrayList<>(mAppsMap.keySet());
            Collections.sort(packageNames);
            for (String packageName : packageNames) {
                pw.println("    " + packageName + ":");
                final SparseArray<SaturationController> appUserIdMap = mAppsMap.get(packageName);
                for (int i = 0; i < appUserIdMap.size(); i++) {
                    pw.println("        " + appUserIdMap.keyAt(i) + ":");
                    appUserIdMap.valueAt(i).dump(pw);
                }
            }
        }
    }

    /**
     * Retrieve the SaturationController for a given package and userId, creating all intermediate
     * connections as needed.
     */
    private SaturationController getSaturationControllerLocked(String packageName,
            @UserIdInt int userId) {
        return getOrCreateSaturationControllerLocked(getOrCreateUserIdMapLocked(packageName),
                userId);
    }

    /**
     * Retrieve or create the mapping between the app's given package name and its userIds (and
     * their SaturationControllers).
     */
    private SparseArray<SaturationController> getOrCreateUserIdMapLocked(String packageName) {
        if (mAppsMap.get(packageName) != null) {
            return mAppsMap.get(packageName);
        }

        final SparseArray<SaturationController> appUserIdMap = new SparseArray<>();
        mAppsMap.put(packageName, appUserIdMap);
        return appUserIdMap;
    }

    /**
     * Retrieve or create the mapping between an app's given userId and SaturationController.
     */
    private SaturationController getOrCreateSaturationControllerLocked(
            SparseArray<SaturationController> appUserIdMap, @UserIdInt int userId) {
        if (appUserIdMap.get(userId) != null) {
            return appUserIdMap.get(userId);
        }

        final SaturationController saturationController = new SaturationController();
        appUserIdMap.put(userId, saturationController);
        return saturationController;
    }

    @VisibleForTesting
    static void computeGrayscaleTransformMatrix(float saturation, float[] matrix) {
        float desaturation = 1.0f - saturation;
        float[] luminance = {0.231f * desaturation, 0.715f * desaturation,
                0.072f * desaturation};
        matrix[0] = luminance[0] + saturation;
        matrix[1] = luminance[0];
        matrix[2] = luminance[0];
        matrix[3] = luminance[1];
        matrix[4] = luminance[1] + saturation;
        matrix[5] = luminance[1];
        matrix[6] = luminance[2];
        matrix[7] = luminance[2];
        matrix[8] = luminance[2] + saturation;
    }

    private static class SaturationController {

        private final List<WeakReference<ColorTransformController>> mControllerRefs =
                new ArrayList<>();
        private int mSaturationLevel = 100;
        private float[] mTransformMatrix = new float[9];

        private boolean setSaturationLevel(int saturationLevel) {
            mSaturationLevel = saturationLevel;
            if (!mControllerRefs.isEmpty()) {
                return updateState();
            }
            return false;
        }

        private boolean addColorTransformController(
                WeakReference<ColorTransformController> controller) {
            mControllerRefs.add(controller);
            if (mSaturationLevel != 100) {
                return updateState();
            } else {
                clearExpiredReferences();
            }
            return false;
        }

        private boolean updateState() {
            computeGrayscaleTransformMatrix(mSaturationLevel / 100f, mTransformMatrix);

            boolean updated = false;
            final Iterator<WeakReference<ColorTransformController>> iterator = mControllerRefs
                    .iterator();
            while (iterator.hasNext()) {
                WeakReference<ColorTransformController> controllerRef = iterator.next();
                final ColorTransformController controller = controllerRef.get();
                if (controller != null) {
                    controller.applyAppSaturation(mTransformMatrix, TRANSLATION_VECTOR);
                    updated = true;
                } else {
                    // Purge cleared refs lazily to avoid accumulating a lot of dead windows
                    iterator.remove();
                }
            }
            return updated;

        }

        private void clearExpiredReferences() {
            final Iterator<WeakReference<ColorTransformController>> iterator = mControllerRefs
                    .iterator();
            while (iterator.hasNext()) {
                WeakReference<ColorTransformController> controllerRef = iterator.next();
                final ColorTransformController controller = controllerRef.get();
                if (controller == null) {
                    iterator.remove();
                }
            }
        }

        private void dump(PrintWriter pw) {
            pw.println("            mSaturationLevel: " + mSaturationLevel);
            pw.println("            mControllerRefs count: " + mControllerRefs.size());
        }
    }
}
