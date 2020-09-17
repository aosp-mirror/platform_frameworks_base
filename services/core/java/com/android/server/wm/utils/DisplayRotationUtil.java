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

import static android.view.DisplayCutout.BOUNDS_POSITION_LENGTH;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import static com.android.server.wm.utils.CoordinateTransforms.transformPhysicalToLogicalCoordinates;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Utility to compute bounds after rotating the screen.
 */
public class DisplayRotationUtil {
    private final Matrix mTmpMatrix = new Matrix();

    private static int getRotationToBoundsOffset(int rotation) {
        switch (rotation) {
            case ROTATION_0:
                return 0;
            case ROTATION_90:
                return -1;
            case ROTATION_180:
                return 2;
            case ROTATION_270:
                return 1;
            default:
                // should not happen
                return 0;
        }
    }

    @VisibleForTesting
    static int getBoundIndexFromRotation(int i, int rotation) {
        return Math.floorMod(i + getRotationToBoundsOffset(rotation),
                BOUNDS_POSITION_LENGTH);
    }

    /**
     * Compute bounds after rotating the screen.
     *
     * @param bounds Bounds before the rotation. The array must contain exactly 4 non-null elements.
     * @param rotation rotation constant defined in android.view.Surface.
     * @param initialDisplayWidth width of the display before the rotation.
     * @param initialDisplayHeight height of the display before the rotation.
     * @return Bounds after the rotation.
     *
     * @hide
     */
    public Rect[] getRotatedBounds(
            Rect[] bounds, int rotation, int initialDisplayWidth, int initialDisplayHeight) {
        if (bounds.length != BOUNDS_POSITION_LENGTH) {
            throw new IllegalArgumentException(
                    "bounds must have exactly 4 elements: bounds=" + bounds);
        }
        if (rotation == ROTATION_0) {
            return bounds;
        }
        transformPhysicalToLogicalCoordinates(rotation, initialDisplayWidth, initialDisplayHeight,
                mTmpMatrix);
        Rect[] newBounds = new Rect[BOUNDS_POSITION_LENGTH];
        for (int i = 0; i < bounds.length; i++) {

            final Rect rect = bounds[i];
            if (!rect.isEmpty()) {
                final RectF rectF = new RectF(rect);
                mTmpMatrix.mapRect(rectF);
                rectF.round(rect);
            }
            newBounds[getBoundIndexFromRotation(i, rotation)] = rect;
        }
        return newBounds;
    }
}
