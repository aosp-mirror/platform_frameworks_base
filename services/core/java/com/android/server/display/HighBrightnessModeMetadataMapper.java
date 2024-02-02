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

import android.util.ArrayMap;
import android.util.Slog;

/**
 * Provides {@link HighBrightnessModeMetadata}s for {@link DisplayDevice}s. This class should only
 * be accessed from the display thread.
 */
class HighBrightnessModeMetadataMapper {

    private static final String TAG = "HighBrightnessModeMetadataMapper";

    /**
     *  Map of every internal primary display device {@link HighBrightnessModeMetadata}s indexed by
     *  {@link DisplayDevice#mUniqueId}.
     */
    private final ArrayMap<String, HighBrightnessModeMetadata> mHighBrightnessModeMetadataMap =
            new ArrayMap<>();

    HighBrightnessModeMetadata getHighBrightnessModeMetadataLocked(LogicalDisplay display) {
        final DisplayDevice device = display.getPrimaryDisplayDeviceLocked();
        if (device == null) {
            Slog.wtf(TAG, "Display Device is null in DisplayPowerController for display: "
                    + display.getDisplayIdLocked());
            return null;
        }
        if (device.getDisplayDeviceConfig().getHighBrightnessModeData() == null) {
            return null;
        }

        final String uniqueId = device.getUniqueId();

        if (mHighBrightnessModeMetadataMap.containsKey(uniqueId)) {
            return mHighBrightnessModeMetadataMap.get(uniqueId);
        }

        // HBM Time info not present. Create a new one for this physical display.
        HighBrightnessModeMetadata hbmInfo = new HighBrightnessModeMetadata();
        mHighBrightnessModeMetadataMap.put(uniqueId, hbmInfo);
        return hbmInfo;
    }
}
