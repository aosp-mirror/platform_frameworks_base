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

import android.annotation.Dimension;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Rect;
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

    /**
     * Rotates bounds as if parentBounds and bounds are a group. The group is rotated from
     * oldRotation to newRotation. This assumes that parentBounds is at 0,0 and remains at 0,0 after
     * rotation. The bounds will be at the same physical position in parentBounds.
     *
     * Only 'inOutBounds' is mutated.
     */
    public static void rotateBounds(Rect inOutBounds, Rect parentBounds, @Rotation int oldRotation,
            @Rotation int newRotation) {
        rotateBounds(inOutBounds, parentBounds, deltaRotation(oldRotation, newRotation));
    }

    /**
     * Rotates bounds as if parentBounds and bounds are a group. The group is rotated by `delta`
     * 90-degree counter-clockwise increments. This assumes that parentBounds is at 0,0 and
     * remains at 0,0 after rotation. The bounds will be at the same physical position in
     * parentBounds.
     *
     * Only 'inOutBounds' is mutated.
     */
    public static void rotateBounds(Rect inOutBounds, Rect parentBounds, @Rotation int rotation) {
        final int origLeft = inOutBounds.left;
        final int origTop = inOutBounds.top;
        switch (rotation) {
            case ROTATION_0:
                return;
            case ROTATION_90:
                inOutBounds.left = inOutBounds.top;
                inOutBounds.top = parentBounds.right - inOutBounds.right;
                inOutBounds.right = inOutBounds.bottom;
                inOutBounds.bottom = parentBounds.right - origLeft;
                return;
            case ROTATION_180:
                inOutBounds.left = parentBounds.right - inOutBounds.right;
                inOutBounds.right = parentBounds.right - origLeft;
                inOutBounds.top = parentBounds.bottom - inOutBounds.bottom;
                inOutBounds.bottom = parentBounds.bottom - origTop;
                return;
            case ROTATION_270:
                inOutBounds.left = parentBounds.bottom - inOutBounds.bottom;
                inOutBounds.bottom = inOutBounds.right;
                inOutBounds.right = parentBounds.bottom - inOutBounds.top;
                inOutBounds.top = origLeft;
        }
    }

    /** @return the rotation needed to rotate from oldRotation to newRotation. */
    @Rotation
    public static int deltaRotation(int oldRotation, int newRotation) {
        int delta = newRotation - oldRotation;
        if (delta < 0) delta += 4;
        return delta;
    }

    /**
     * Sets a matrix such that given a rotation, it transforms physical display
     * coordinates to that rotation's logical coordinates.
     *
     * @param rotation the rotation to which the matrix should transform
     * @param out the matrix to be set
     */
    public static void transformPhysicalToLogicalCoordinates(@Rotation int rotation,
            @Dimension int physicalWidth, @Dimension int physicalHeight, Matrix out) {
        switch (rotation) {
            case ROTATION_0:
                out.reset();
                break;
            case ROTATION_90:
                out.setRotate(270);
                out.postTranslate(0, physicalWidth);
                break;
            case ROTATION_180:
                out.setRotate(180);
                out.postTranslate(physicalWidth, physicalHeight);
                break;
            case ROTATION_270:
                out.setRotate(90);
                out.postTranslate(physicalHeight, 0);
                break;
            default:
                throw new IllegalArgumentException("Unknown rotation: " + rotation);
        }
    }
}
