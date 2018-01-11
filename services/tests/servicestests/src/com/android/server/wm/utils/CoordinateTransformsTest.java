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

import static com.android.server.wm.utils.CoordinateTransforms.transformPhysicalToLogicalCoordinates;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.PointF;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;

public class CoordinateTransformsTest {

    private static final int W = 200;
    private static final int H = 400;

    private final Matrix mMatrix = new Matrix();

    @Rule
    public final ErrorCollector mErrorCollector = new ErrorCollector();

    @Before
    public void setUp() throws Exception {
        mMatrix.setTranslate(0xdeadbeef, 0xdeadbeef);
    }

    @Test
    public void transformPhysicalToLogicalCoordinates_rot0() throws Exception {
        transformPhysicalToLogicalCoordinates(ROTATION_0, W, H, mMatrix);
        assertThat(mMatrix, is(Matrix.IDENTITY_MATRIX));
    }

    @Test
    public void transformPhysicalToLogicalCoordinates_rot90() throws Exception {
        transformPhysicalToLogicalCoordinates(ROTATION_90, W, H, mMatrix);

        checkDevicePoint(0, 0).mapsToLogicalPoint(0, W);
        checkDevicePoint(W, H).mapsToLogicalPoint(H, 0);
    }

    @Test
    public void transformPhysicalToLogicalCoordinates_rot180() throws Exception {
        transformPhysicalToLogicalCoordinates(ROTATION_180, W, H, mMatrix);

        checkDevicePoint(0, 0).mapsToLogicalPoint(W, H);
        checkDevicePoint(W, H).mapsToLogicalPoint(0, 0);
    }

    @Test
    public void transformPhysicalToLogicalCoordinates_rot270() throws Exception {
        transformPhysicalToLogicalCoordinates(ROTATION_270, W, H, mMatrix);

        checkDevicePoint(0, 0).mapsToLogicalPoint(H, 0);
        checkDevicePoint(W, H).mapsToLogicalPoint(0, W);
    }

    private DevicePointAssertable checkDevicePoint(int x, int y) {
        final Point devicePoint = new Point(x, y);
        final float[] fs = new float[] {x, y};
        mMatrix.mapPoints(fs);
        final PointF transformedPoint = new PointF(fs[0], fs[1]);

        return (expectedX, expectedY) -> {
            mErrorCollector.checkThat("t(" + devicePoint + ")",
                    transformedPoint, is(new PointF(expectedX, expectedY)));
        };
    }

    public interface DevicePointAssertable {
        void mapsToLogicalPoint(int x, int y);
    }
}