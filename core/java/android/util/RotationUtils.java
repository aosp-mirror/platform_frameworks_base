/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import android.graphics.Insets;
import android.view.Surface.Rotation;

/**
 * A class containing utility methods related to rotation.
 *
 * @hide
 */
public class RotationUtils {

    /**
     * Rotates an Insets according to the given rotation.
     */
    public static Insets rotateInsets(Insets insets, @Rotation int rotation) {
        if (insets == null || insets == Insets.NONE) {
            return insets;
        }
        Insets rotated;
        switch (rotation) {
            case ROTATION_0:
                rotated = insets;
                break;
            case ROTATION_90:
                rotated = Insets.of(
                        insets.top,
                        insets.right,
                        insets.bottom,
                        insets.left);
                break;
            case ROTATION_180:
                rotated = Insets.of(
                        insets.right,
                        insets.bottom,
                        insets.left,
                        insets.top);
                break;
            case ROTATION_270:
                rotated = Insets.of(
                        insets.bottom,
                        insets.left,
                        insets.top,
                        insets.right);
                break;
            default:
                throw new IllegalArgumentException("unknown rotation: " + rotation);
        }
        return rotated;
    }
}
