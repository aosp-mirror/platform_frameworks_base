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

import android.content.Context;
import android.content.res.Resources;
import android.view.Display;
import android.view.DisplayInfo;

import com.android.internal.R;

/**
 * Utils for loading display related resources and calculations.
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

    /**
     * Returns the Display.Mode with maximum resolution.
     */
    public static Display.Mode getMaximumResolutionDisplayMode(Display.Mode[] modes) {
        if (modes == null || modes.length == 0) {
            return null;
        }
        int maxWidth = 0;
        Display.Mode target = null;
        for (Display.Mode mode : modes) {
            if (mode.getPhysicalWidth() > maxWidth) {
                maxWidth = mode.getPhysicalWidth();
                target = mode;
            }
        }
        return target;
    }

    /**
     * Get the display size ratio based on the physical display size.
     */
    public static float getPhysicalPixelDisplaySizeRatio(
            int physicalWidth, int physicalHeight, int currentWidth, int currentHeight) {
        if (physicalWidth == currentWidth && physicalHeight == currentHeight) {
            return 1f;
        }
        final float widthRatio = (float) currentWidth / physicalWidth;
        final float heightRatio = (float) currentHeight / physicalHeight;
        return Math.min(widthRatio, heightRatio);
    }

    /**
     * Get the display size ratio for the current resolution vs the maximum supported
     * resolution.
     */
    public static float getScaleFactor(Context context) {
        DisplayInfo displayInfo = new DisplayInfo();
        context.getDisplay().getDisplayInfo(displayInfo);
        final Display.Mode maxDisplayMode =
                getMaximumResolutionDisplayMode(displayInfo.supportedModes);
        final float scaleFactor = getPhysicalPixelDisplaySizeRatio(
                maxDisplayMode.getPhysicalWidth(), maxDisplayMode.getPhysicalHeight(),
                displayInfo.getNaturalWidth(), displayInfo.getNaturalHeight());

        return scaleFactor;
    }
}
