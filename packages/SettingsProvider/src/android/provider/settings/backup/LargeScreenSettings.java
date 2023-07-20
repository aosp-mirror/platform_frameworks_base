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

package android.provider.settings.backup;

import android.content.Context;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.WindowManager;

/** Settings that should not be restored when target device is a large screen
 *  i.e. tablets and foldables in unfolded state
 */
public class LargeScreenSettings {
    private static final float LARGE_SCREEN_MIN_DPS = 600;
    private static final String LARGE_SCREEN_DO_NOT_RESTORE = "accelerometer_rotation";

   /**
    * Autorotation setting should not be restored when the target device is a large screen.
    * (b/243489549)
    */
    public static boolean doNotRestoreIfLargeScreenSetting(String key, Context context) {
        return isLargeScreen(context) && LARGE_SCREEN_DO_NOT_RESTORE.equals(key);
    }

    // copied from systemui/shared/...Utilities.java
    // since we don't want to add compile time dependency on sys ui package
    private static boolean isLargeScreen(Context context) {
        final WindowManager windowManager = context.getSystemService(WindowManager.class);
        final Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
        float smallestWidth = dpiFromPx(Math.min(bounds.width(), bounds.height()),
                context.getResources().getConfiguration().densityDpi);
        return smallestWidth >= LARGE_SCREEN_MIN_DPS;
    }

    private static float dpiFromPx(float size, int densityDpi) {
        float densityRatio = (float) densityDpi / DisplayMetrics.DENSITY_DEFAULT;
        return (size / densityRatio);
    }
}
