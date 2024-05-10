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

package com.android.settingslib.testutils.shadow;

import android.Manifest;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.hardware.display.ColorDisplayManager;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@Implements(ColorDisplayManager.class)
public class ShadowColorDisplayManager extends org.robolectric.shadows.ShadowColorDisplayManager {

    private boolean mIsReduceBrightColorsActivated;

    @Implementation
    @SystemApi
    @RequiresPermission(Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
    public boolean setReduceBrightColorsActivated(boolean activated) {
        mIsReduceBrightColorsActivated = activated;
        return true;
    }

    @Implementation
    @SystemApi
    public boolean isReduceBrightColorsActivated() {
        return mIsReduceBrightColorsActivated;
    }

}
