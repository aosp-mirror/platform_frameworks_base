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
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.view.Surface;
import android.view.Surface.Rotation;
import android.view.SurfaceControl;

/**
 * A class containing utility methods related to rotation.
 *
 * @hide
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
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
     * Rotates inOutBounds together with the parent for a given rotation delta. This assumes that
     * the parent starts at 0,0 and remains at 0,0 after the rotation. The inOutBounds will remain
     * at the same physical position within the parent.
     *
     * Only 'inOutBounds' is mutated.
     */
    public static void rotateBounds(Rect inOutBounds, int parentWidth, int parentHeight,
            @Rotation int rotation) {
        final int origLeft = inOutBounds.left;
        final int origTop = inOutBounds.top;
        switch (rotation) {
            case ROTATION_0:
                return;
            case ROTATION_90:
                inOutBounds.left = inOutBounds.top;
                inOutBounds.top = parentWidth - inOutBounds.right;
                inOutBounds.right = inOutBounds.bottom;
                inOutBounds.bottom = parentWidth - origLeft;
                return;
            case ROTATION_180:
                inOutBounds.left = parentWidth - inOutBounds.right;
                inOutBounds.right = parentWidth - origLeft;
                inOutBounds.top = parentHeight - inOutBounds.bottom;
                inOutBounds.bottom = parentHeight - origTop;
                return;
            case ROTATION_270:
                inOutBounds.left = parentHeight - inOutBounds.bottom;
                inOutBounds.bottom = inOutBounds.right;
                inOutBounds.right = parentHeight - inOutBounds.top;
                inOutBounds.top = origLeft;
        }
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
        rotateBounds(inOutBounds, parentBounds.right, parentBounds.bottom, rotation);
    }

    /** @return the rotation needed to rotate from oldRotation to newRotation. */
    @Rotation
    public static int deltaRotation(@Rotation int oldRotation, @Rotation int newRotation) {
        int delta = newRotation - oldRotation;
        if (delta < 0) delta += 4;
        return delta;
    }

    /**
     * Rotates a surface CCW around the origin (eg. a 90-degree rotation will result in the
     * bottom-left being at the origin). Use {@link #rotatePoint} to transform the top-left
     * corner appropriately.
     */
    public static void rotateSurface(SurfaceControl.Transaction t, SurfaceControl sc,
            @Rotation int rotation) {
        // Note: the matrix values look inverted, but they aren't because our coordinate-space
        // is actually left-handed.
        // Note: setMatrix expects values in column-major order.
        switch (rotation) {
            case ROTATION_0:
                t.setMatrix(sc, 1.f, 0.f, 0.f, 1.f);
                break;
            case ROTATION_90:
                t.setMatrix(sc, 0.f, -1.f, 1.f, 0.f);
                break;
            case ROTATION_180:
                t.setMatrix(sc, -1.f, 0.f, 0.f, -1.f);
                break;
            case ROTATION_270:
                t.setMatrix(sc, 0.f, 1.f, -1.f, 0.f);
                break;
        }
    }

    /**
     * Rotates a point CCW within a rectangle of size parentW x parentH with top/left at the
     * origin as if the point is stuck to the rectangle. The rectangle is transformed such that
     * it's top/left remains at the origin after the rotation.
     */
    public static void rotatePoint(Point inOutPoint, @Rotation int rotation,
            int parentW, int parentH) {
        int origX = inOutPoint.x;
        switch (rotation) {
            case ROTATION_0:
                return;
            case ROTATION_90:
                inOutPoint.x = inOutPoint.y;
                inOutPoint.y = parentW - origX;
                return;
            case ROTATION_180:
                inOutPoint.x = parentW - inOutPoint.x;
                inOutPoint.y = parentH - inOutPoint.y;
                return;
            case ROTATION_270:
                inOutPoint.x = parentH - inOutPoint.y;
                inOutPoint.y = origX;
        }
    }

    /**
     * Same as {@link #rotatePoint}, but for float coordinates.
     */
    public static void rotatePointF(PointF inOutPoint, @Rotation int rotation,
            float parentW, float parentH) {
        float origX = inOutPoint.x;
        switch (rotation) {
            case ROTATION_0:
                return;
            case ROTATION_90:
                inOutPoint.x = inOutPoint.y;
                inOutPoint.y = parentW - origX;
                return;
            case ROTATION_180:
                inOutPoint.x = parentW - inOutPoint.x;
                inOutPoint.y = parentH - inOutPoint.y;
                return;
            case ROTATION_270:
                inOutPoint.x = parentH - inOutPoint.y;
                inOutPoint.y = origX;
        }
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

    /**
     * Reverses the rotation direction around the Z axis. Note that this method assumes all
     * rotations are relative to {@link Surface.ROTATION_0}.
     *
     * @param rotation the original rotation.
     * @return the new rotation that should be applied.
     */
    @Surface.Rotation
    public static int reverseRotationDirectionAroundZAxis(@Surface.Rotation int rotation) {
        // Flipping 270 and 90 has the same effect as changing the direction which rotation is
        // applied.
        if (rotation == Surface.ROTATION_90) {
            rotation = Surface.ROTATION_270;
        } else if (rotation == Surface.ROTATION_270) {
            rotation = Surface.ROTATION_90;
        }
        return rotation;
    }
}
