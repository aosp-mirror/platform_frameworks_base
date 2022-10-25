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

package com.android.server.wm.utils;

import static android.hardware.HardwareBuffer.USAGE_PROTECTED_CONTENT;

import android.graphics.Matrix;
import android.hardware.HardwareBuffer;
import android.view.Surface;


/** Helper functions for the {@link com.android.server.wm.ScreenRotationAnimation} class*/
public class RotationAnimationUtils {

    /**
     * @return whether the hardwareBuffer passed in is marked as protected.
     */
    public static boolean hasProtectedContent(HardwareBuffer hardwareBuffer) {
        return (hardwareBuffer.getUsage() & USAGE_PROTECTED_CONTENT) == USAGE_PROTECTED_CONTENT;
    }

    public static void createRotationMatrix(int rotation, int width, int height, Matrix outMatrix) {
        switch (rotation) {
            case Surface.ROTATION_0:
                outMatrix.reset();
                break;
            case Surface.ROTATION_90:
                outMatrix.setRotate(90, 0, 0);
                outMatrix.postTranslate(height, 0);
                break;
            case Surface.ROTATION_180:
                outMatrix.setRotate(180, 0, 0);
                outMatrix.postTranslate(width, height);
                break;
            case Surface.ROTATION_270:
                outMatrix.setRotate(270, 0, 0);
                outMatrix.postTranslate(0, width);
                break;
        }
    }
}
