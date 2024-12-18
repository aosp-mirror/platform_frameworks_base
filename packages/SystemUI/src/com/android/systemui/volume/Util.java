/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.volume;

import android.media.AudioManager;
import android.util.MathUtils;
import android.view.View;

/**
 * Static helpers for the volume dialog.
 */
class Util extends com.android.settingslib.volume.Util {

    public static String logTag(Class<?> c) {
        final String tag = "vol." + c.getSimpleName();
        return tag.length() < 23 ? tag : tag.substring(0, 23);
    }

    public static String ringerModeToString(int ringerMode) {
        switch (ringerMode) {
            case AudioManager.RINGER_MODE_SILENT:
                return "RINGER_MODE_SILENT";
            case AudioManager.RINGER_MODE_VIBRATE:
                return "RINGER_MODE_VIBRATE";
            case AudioManager.RINGER_MODE_NORMAL:
                return "RINGER_MODE_NORMAL";
            default:
                return "RINGER_MODE_UNKNOWN_" + ringerMode;
        }
    }

    public static final void setVisOrGone(View v, boolean vis) {
        if (v == null || (v.getVisibility() == View.VISIBLE) == vis) return;
        v.setVisibility(vis ? View.VISIBLE : View.GONE);
    }

    /**
     * Translates a value from one range to another.
     *
     * ```
     * Given: currentValue=3, currentRange=[0, 8], targetRange=[0, 100]
     * Result: 37.5
     * ```
     */
    public static float translateToRange(float value,
            float valueRangeStart,
            float valueRangeEnd,
            float targetRangeStart,
            float targetRangeEnd) {
        float currentRangeLength = valueRangeEnd - valueRangeStart;
        float targetRangeLength = targetRangeEnd - targetRangeStart;
        if (currentRangeLength == 0f || targetRangeLength == 0f) {
            return targetRangeStart;
        }
        float valueFraction = (value - valueRangeStart) / currentRangeLength;
        return MathUtils.constrain(targetRangeStart + valueFraction * targetRangeLength,
                targetRangeStart, targetRangeEnd);
    }
}
