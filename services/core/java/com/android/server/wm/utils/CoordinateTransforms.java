/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import android.annotation.Dimension;
import android.graphics.Matrix;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.Surface.Rotation;

public class CoordinateTransforms {

    private CoordinateTransforms() {
    }

    /**
     * Sets a matrix such that given a rotation, it transforms physical display
     * coordinates to that rotation's logical coordinates.
     *
     * @param rotation the rotation to which the matrix should transform
     * @param out      the matrix to be set
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
     * Sets a matrix such that given a rotation, it transforms that rotation's logical coordinates
     * to physical coordinates.
     *
     * @param rotation the rotation to which the matrix should transform
     * @param out      the matrix to be set
     */
    public static void transformLogicalToPhysicalCoordinates(@Rotation int rotation,
            @Dimension int physicalWidth, @Dimension int physicalHeight, Matrix out) {
        switch (rotation) {
            case ROTATION_0:
                out.reset();
                break;
            case ROTATION_90:
                out.setRotate(90);
                out.preTranslate(0, -physicalWidth);
                break;
            case ROTATION_180:
                out.setRotate(180);
                out.preTranslate(-physicalWidth, -physicalHeight);
                break;
            case ROTATION_270:
                out.setRotate(270);
                out.preTranslate(-physicalHeight, 0);
                break;
            default:
                throw new IllegalArgumentException("Unknown rotation: " + rotation);
        }
    }

    /**
     * Sets a matrix such that given a two rotations, that it transforms coordinates given in the
     * old rotation to coordinates that refer to the same physical location in the new rotation.
     *
     * @param oldRotation the rotation to transform from
     * @param newRotation the rotation to transform to
     * @param info the display info
     * @param out a matrix that will be set to the transform
     */
    public static void transformToRotation(@Rotation int oldRotation,
            @Rotation int newRotation, DisplayInfo info, Matrix out) {
        final boolean flipped = info.rotation == ROTATION_90 || info.rotation == ROTATION_270;
        final int h = flipped ? info.logicalWidth : info.logicalHeight;
        final int w = flipped ? info.logicalHeight : info.logicalWidth;

        final Matrix tmp = new Matrix();
        transformLogicalToPhysicalCoordinates(oldRotation, w, h, out);
        transformPhysicalToLogicalCoordinates(newRotation, w, h, tmp);
        out.postConcat(tmp);
    }

    /**
     * Sets a matrix such that given a two rotations, that it transforms coordinates given in the
     * old rotation to coordinates that refer to the same physical location in the new rotation.
     *
     * @param oldRotation the rotation to transform from
     * @param newRotation the rotation to transform to
     * @param newWidth the width of the area to transform, in the new rotation
     * @param newHeight the height of the area to transform, in the new rotation
     * @param out a matrix that will be set to the transform
     */
    public static void transformToRotation(@Rotation int oldRotation,
            @Rotation int newRotation, int newWidth, int newHeight, Matrix out) {
        final boolean flipped = newRotation == ROTATION_90 || newRotation == ROTATION_270;
        final int h = flipped ? newWidth : newHeight;
        final int w = flipped ? newHeight : newWidth;

        final Matrix tmp = new Matrix();
        transformLogicalToPhysicalCoordinates(oldRotation, w, h, out);
        transformPhysicalToLogicalCoordinates(newRotation, w, h, tmp);
        out.postConcat(tmp);
    }

    /** Computes the matrix that rotates the original w x h by the rotation delta. */
    public static void computeRotationMatrix(int rotationDelta, int w, int h, Matrix outMatrix) {
        switch (rotationDelta) {
            case Surface.ROTATION_0:
                outMatrix.reset();
                break;
            case Surface.ROTATION_90:
                outMatrix.setRotate(90);
                outMatrix.postTranslate(h, 0);
                break;
            case Surface.ROTATION_180:
                outMatrix.setRotate(180);
                outMatrix.postTranslate(w, h);
                break;
            case Surface.ROTATION_270:
                outMatrix.setRotate(270);
                outMatrix.postTranslate(0, w);
                break;
        }
    }
}
