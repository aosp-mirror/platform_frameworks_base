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

import static com.android.server.wm.utils.CoordinateTransforms.transformLogicalToPhysicalCoordinates;
import static com.android.server.wm.utils.CoordinateTransforms.transformPhysicalToLogicalCoordinates;


import static com.android.server.wm.utils.CoordinateTransforms.transformToRotation;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.*;

import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.PointF;
import android.view.DisplayInfo;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ErrorCollector;

public class CoordinateTransformsTest {

    private static final int W = 200;
    private static final int H = 400;

    private final Matrix mMatrix = new Matrix();
    private final Matrix mMatrix2 = new Matrix();

    @Rule
    public final ErrorCollector mErrorCollector = new ErrorCollector();

    @Before
    public void setUp() throws Exception {
        mMatrix.setTranslate(0xdeadbeef, 0xdeadbeef);
        mMatrix2.setTranslate(0xbeefdead, 0xbeefdead);
    }

    @Test
    public void transformPhysicalToLogicalCoordinates_rot0() {
        transformPhysicalToLogicalCoordinates(ROTATION_0, W, H, mMatrix);
        assertThat(mMatrix, is(Matrix.IDENTITY_MATRIX));
    }

    @Test
    public void transformPhysicalToLogicalCoordinates_rot90() {
        transformPhysicalToLogicalCoordinates(ROTATION_90, W, H, mMatrix);

        checkPoint(0, 0).transformsTo(0, W);
        checkPoint(W, H).transformsTo(H, 0);
    }

    @Test
    public void transformPhysicalToLogicalCoordinates_rot180() {
        transformPhysicalToLogicalCoordinates(ROTATION_180, W, H, mMatrix);

        checkPoint(0, 0).transformsTo(W, H);
        checkPoint(W, H).transformsTo(0, 0);
    }

    @Test
    public void transformPhysicalToLogicalCoordinates_rot270() {
        transformPhysicalToLogicalCoordinates(ROTATION_270, W, H, mMatrix);

        checkPoint(0, 0).transformsTo(H, 0);
        checkPoint(W, H).transformsTo(0, W);
    }

    @Test
    public void transformLogicalToPhysicalCoordinates_rot0() {
        transformLogicalToPhysicalCoordinates(ROTATION_0, W, H, mMatrix);
        assertThat(mMatrix, is(Matrix.IDENTITY_MATRIX));
    }

    @Test
    public void transformLogicalToPhysicalCoordinates_rot90() {
        transformLogicalToPhysicalCoordinates(ROTATION_90, W, H, mMatrix);

        checkPoint(0, W).transformsTo(0, 0);
        checkPoint(H, 0).transformsTo(W, H);
    }

    @Test
    public void transformLogicalToPhysicalCoordinates_rot180() {
        transformLogicalToPhysicalCoordinates(ROTATION_180, W, H, mMatrix);

        checkPoint(W, H).transformsTo(0, 0);
        checkPoint(0, 0).transformsTo(W, H);
    }

    @Test
    public void transformLogicalToPhysicalCoordinates_rot270() {
        transformLogicalToPhysicalCoordinates(ROTATION_270, W, H, mMatrix);

        checkPoint(H, 0).transformsTo(0, 0);
        checkPoint(0, W).transformsTo(W, H);
    }

    @Test
    public void transformLogicalToPhysicalCoordinatesIsInverse_rot0() {
        transformLogicalToPhysicalCoordinates(ROTATION_0, W, H, mMatrix);
        transformPhysicalToLogicalCoordinates(ROTATION_0, W, H, mMatrix2);

        assertMatricesAreInverses(mMatrix, mMatrix2);
    }

    @Test
    public void transformLogicalToPhysicalCoordinatesIsInverse_rot90() {
        transformLogicalToPhysicalCoordinates(ROTATION_90, W, H, mMatrix);
        transformPhysicalToLogicalCoordinates(ROTATION_90, W, H, mMatrix2);

        assertMatricesAreInverses(mMatrix, mMatrix2);
    }

    @Test
    public void transformLogicalToPhysicalCoordinatesIsInverse_rot180() {
        transformLogicalToPhysicalCoordinates(ROTATION_180, W, H, mMatrix);
        transformPhysicalToLogicalCoordinates(ROTATION_180, W, H, mMatrix2);

        assertMatricesAreInverses(mMatrix, mMatrix2);
    }

    @Test
    public void transformLogicalToPhysicalCoordinatesIsInverse_rot270() {
        transformLogicalToPhysicalCoordinates(ROTATION_270, W, H, mMatrix);
        transformPhysicalToLogicalCoordinates(ROTATION_270, W, H, mMatrix2);

        assertMatricesAreInverses(mMatrix, mMatrix2);
    }

    @Test
    public void transformBetweenRotations_rot180_rot270() {
        // W,H are flipped, because they need to be given in the new orientation, i.e. ROT_270.
        transformToRotation(ROTATION_180, ROTATION_270, H, W, mMatrix);

        checkPoint(0, 0).transformsTo(0, W);
        checkPoint(W, H).transformsTo(H, 0);
    }

    @Test
    public void transformBetweenRotations_rot90_rot0() {
        transformToRotation(ROTATION_180, ROTATION_270, W, H, mMatrix);

        checkPoint(0, 0).transformsTo(0, H);
        // H,W is bottom right in ROT_90
        checkPoint(H, W).transformsTo(W, 0);
    }

    @Test
    public void transformBetweenRotations_displayInfo() {
        final DisplayInfo di = new DisplayInfo();
        di.rotation = ROTATION_90;
        di.logicalWidth = H;  // dimensions are flipped in ROT_90
        di.logicalHeight = W;
        transformToRotation(ROTATION_180, ROTATION_270, di, mMatrix);

        // W,H are flipped, because they need to be given in the new orientation, i.e. ROT_270.
        transformToRotation(ROTATION_180, ROTATION_270, H, W, mMatrix2);

        assertEquals(mMatrix2, mMatrix);
    }

    private void assertMatricesAreInverses(Matrix matrix, Matrix matrix2) {
        final Matrix concat = new Matrix();
        concat.setConcat(matrix, matrix2);
        assertTrue("expected identity, but was: " + concat, concat.isIdentity());
    }

    private TransformPointAssertable checkPoint(int x, int y) {
        final Point devicePoint = new Point(x, y);
        final float[] fs = new float[] {x, y};
        mMatrix.mapPoints(fs);
        final PointF transformedPoint = new PointF(fs[0], fs[1]);

        return (expectedX, expectedY) -> {
            mErrorCollector.checkThat("t(" + devicePoint + ")",
                    transformedPoint, is(new PointF(expectedX, expectedY)));
        };
    }

    public interface TransformPointAssertable {
        void transformsTo(int x, int y);
    }
}