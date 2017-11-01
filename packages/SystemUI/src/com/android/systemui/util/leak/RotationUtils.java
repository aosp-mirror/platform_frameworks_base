/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.util.leak;

import android.content.Context;
import android.content.res.Configuration;
import android.view.Surface;

public class RotationUtils {

    public static final int ROTATION_NONE = 0;
    public static final int ROTATION_LANDSCAPE = 1;
    public static final int ROTATION_SEASCAPE = 2;

    public static int getRotation(Context context) {
        Configuration config = context.getResources().getConfiguration();
        int rot = context.getDisplay().getRotation();
        if (config.smallestScreenWidthDp < 600) {
            if (rot == Surface.ROTATION_90) {
                return ROTATION_LANDSCAPE;
            } else if (rot == Surface.ROTATION_270) {
                return ROTATION_SEASCAPE;
            }
        }
        return ROTATION_NONE;
    }
}
