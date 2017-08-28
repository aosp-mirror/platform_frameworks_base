/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.widget;

import static org.junit.Assert.assertEquals;

import android.graphics.PointF;
import android.graphics.RectF;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.List;

@RunWith(JUnit4.class)
public final class SelectionActionModeHelperTest {

    /*
     * The test rectangle set is composed of three 1x1 rectangles as illustrated below.
     *
     * (0, 0) ____________ (100001, 0)
     *        |█        █|
     *        |_█________|
     * (0, 2)              (100001, 2)
     */
    private final List<RectF> mRectFList = Arrays.asList(
            new RectF(0, 0, 1, 1),
            new RectF(100000, 0, 100001, 1),
            new RectF(1, 1, 2, 2));

    @Test
    public void testMovePointInsideNearestRectangle_pointIsInsideRectangle() {
        testMovePointInsideNearestRectangle(
                0.1f /* pointX */,
                0.1f /* pointY */,
                0.1f /* expectedPointX */,
                0.5f /* expectedPointY */);
    }

    @Test
    public void testMovePointInsideNearestRectangle_pointIsAboveRectangle() {
        testMovePointInsideNearestRectangle(
                0.1f /* pointX */,
                -1.0f /* pointY */,
                0.1f /* expectedPointX */,
                0.5f /* expectedPointY */);
    }

    @Test
    public void testMovePointInsideNearestRectangle_pointIsLeftOfRectangle() {
        testMovePointInsideNearestRectangle(
                -1.0f /* pointX */,
                0.4f /* pointY */,
                0.0f /* expectedPointX */,
                0.5f /* expectedPointY */);
    }

    @Test
    public void testMovePointInsideNearestRectangle_pointIsRightOfRectangle() {
        testMovePointInsideNearestRectangle(
                1.1f /* pointX */,
                0.0f /* pointY */,
                1.0f /* expectedPointX */,
                0.5f /* expectedPointY */);
    }

    @Test
    public void testMovePointInsideNearestRectangle_pointIsBelowRectangle() {
        testMovePointInsideNearestRectangle(
                0.1f /* pointX */,
                1.1f /* pointY */,
                0.1f /* expectedPointX */,
                0.5f /* expectedPointY */);
    }

    @Test
    public void testMovePointInsideNearestRectangle_pointIsToRightOfTheRightmostRectangle() {
        testMovePointInsideNearestRectangle(
                200000.0f /* pointX */,
                0.1f /* pointY */,
                100001.0f /* expectedPointX */,
                0.5f /* expectedPointY */);
    }

    private void testMovePointInsideNearestRectangle(final float pointX, final float pointY,
            final float expectedPointX,
            final float expectedPointY) {
        final PointF point = new PointF(pointX, pointY);
        final PointF adjustedPoint =
                SelectionActionModeHelper.movePointInsideNearestRectangle(point,
                        mRectFList);

        assertEquals(expectedPointX, adjustedPoint.x, 0.0f);
        assertEquals(expectedPointY, adjustedPoint.y, 0.0f);
    }

}
