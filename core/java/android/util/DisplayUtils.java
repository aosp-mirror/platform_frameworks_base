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

package android.util;

import android.content.res.Resources;

import com.android.internal.R;

/**
 * Utils for loading resources for multi-display.
 *
 * @hide
 */
public class DisplayUtils {

    /**
     * Gets the index of the given display unique id in {@link R.array#config_displayUniqueIdArray}
     * which is used to get the related cutout configs for that display.
     *
     * For multi-display device, {@link R.array#config_displayUniqueIdArray} should be set for each
     * display if there are different type of cutouts on each display.
     * For single display device, {@link R.array#config_displayUniqueIdArray} should not to be set
     * and the system will load the default configs for main built-in display.
     */
    public static int getDisplayUniqueIdConfigIndex(Resources res, String displayUniqueId) {
        int index = -1;
        if (displayUniqueId == null || displayUniqueId.isEmpty()) {
            return index;
        }
        final String[] ids = res.getStringArray(R.array.config_displayUniqueIdArray);
        final int size = ids.length;
        for (int i = 0; i < size; i++) {
            if (displayUniqueId.equals(ids[i])) {
                index = i;
                break;
            }
        }
        return index;
    }
}
