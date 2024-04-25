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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.ArrayMap;

import java.util.Map;

/**
 * Bidirectional mapping (1:1) for the currently active cameraId and the app package that opened it.
 *
 * <p>This class is not thread-safe.
 */
final class CameraIdPackageNameBiMapping {
    private final Map<String, String> mPackageToCameraIdMap = new ArrayMap<>();
    private final Map<String, String> mCameraIdToPackageMap = new ArrayMap<>();

    boolean isEmpty() {
        return mCameraIdToPackageMap.isEmpty();
    }

    void put(@NonNull String packageName, @NonNull String cameraId) {
        // Always using the last connected camera ID for the package even for the concurrent
        // camera use case since we can't guess which camera is more important anyway.
        removePackageName(packageName);
        removeCameraId(cameraId);
        mPackageToCameraIdMap.put(packageName, cameraId);
        mCameraIdToPackageMap.put(cameraId, packageName);
    }

    boolean containsPackageName(@NonNull String packageName) {
        return mPackageToCameraIdMap.containsKey(packageName);
    }

    @Nullable
    String getCameraId(@NonNull String packageName) {
        return mPackageToCameraIdMap.get(packageName);
    }

    void removeCameraId(@NonNull String cameraId) {
        final String packageName = mCameraIdToPackageMap.get(cameraId);
        if (packageName == null) {
            return;
        }
        mPackageToCameraIdMap.remove(packageName, cameraId);
        mCameraIdToPackageMap.remove(cameraId, packageName);
    }

    @NonNull
    String getSummaryForDisplayRotationHistoryRecord() {
        return "{ mPackageToCameraIdMap=" + mPackageToCameraIdMap + " }";
    }

    private void removePackageName(@NonNull String packageName) {
        String cameraId = mPackageToCameraIdMap.get(packageName);
        if (cameraId == null) {
            return;
        }
        mPackageToCameraIdMap.remove(packageName, cameraId);
        mCameraIdToPackageMap.remove(cameraId, packageName);
    }
}
