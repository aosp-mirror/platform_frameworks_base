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

package android.hardware.camera2.extension;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.impl.CameraMetadataNative;

import com.android.internal.camera.flags.Flags;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Helper class used to forward the current
 * system camera characteristics information.
 *
 * @hide
 */
@SystemApi
public class CharacteristicsMap {
    private final HashMap<String, CameraCharacteristics> mCharMap;

    /**
     * Initialize a camera characteristics map instance
     *
     * @param charsMap       Maps camera ids to respective
     *                       {@link CameraCharacteristics}
     */
    CharacteristicsMap(@NonNull Map<String, CameraMetadataNative> charsMap) {
        mCharMap = new HashMap<>();
        for (Map.Entry<String, CameraMetadataNative> entry : charsMap.entrySet()) {
            mCharMap.put(entry.getKey(), new CameraCharacteristics(entry.getValue()));
        }
    }

    /**
     * Return the set of camera ids stored in the characteristics map
     *
     * @return Set of the camera ids stored in the map
     */
    @NonNull
    public Set<String> getCameraIds() {
        return mCharMap.keySet();
    }

    /**
     * Return the corresponding {@link CameraCharacteristics} given
     * a valid camera id
     *
     * @param cameraId Camera device id
     *
     * @return Valid {@link CameraCharacteristics} instance of null
     *         in case the camera id is not part of the map
     */
    @Nullable
    public CameraCharacteristics get(@NonNull String cameraId) {
        return mCharMap.get(cameraId);
    }
}
