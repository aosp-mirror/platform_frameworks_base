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

import static java.util.function.Function.identity;

import android.graphics.PointF;
import android.graphics.RectF;

import androidx.test.filters.LargeTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@LargeTest
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
                        mRectFList, identity());

        assertEquals(expectedPointX, adjustedPoint.x, 0.0f);
        assertEquals(expectedPointY, adjustedPoint.y, 0.0f);
    }

    @Test
    public void testMergeRectangleIntoList_addThreeDisjointRectangles() {
        testExpandRectangleList(
                new RectF[] {
                        new RectF(0, 0, 1, 1),
                        new RectF(10, 10, 11, 11),
                        new RectF(20, 20, 21, 21)
                },
                new RectF[] {
                        new RectF(0, 0, 1, 1),
                        new RectF(10, 10, 11, 11),
                        new RectF(20, 20, 21, 21)
                }
        );
    }

    @Test
    public void testMergeRectangleIntoList_addAnEmptyRectangle() {
        testExpandRectangleList(
                new RectF[] {
                        new RectF(0, 0, 0, 0)
                },
                new RectF[] {
                }
        );
    }

    @Test
    public void testMergeRectangleIntoList_addAContainedRectangle() {
        testExpandRectangleList(
                new RectF[] {
                        new RectF(0, 0, 10, 10),
                        new RectF(9, 0, 10, 10)
                },
                new RectF[] {
                        new RectF(0, 0, 10, 10)
                }
        );
    }

    @Test
    public void testMergeRectangleIntoList_addARectangleThatContainsExistingRectangles() {
        testExpandRectangleList(
                new RectF[] {
                        new RectF(0, 0, 1, 1),
                        new RectF(1, 0, 2, 1),
                        new RectF(0, 0, 2, 1)
                },
                new RectF[] {
                        new RectF(0, 0, 2, 1)
                }
        );
    }

    @Test
    public void testMergeRectangleIntoList_addRectangleThatIntersectsAndHasTheSameHeightOnRight() {
        testExpandRectangleList(
                new RectF[] {
                        new RectF(0, 0, 1, 1),
                        new RectF(0.5f, 0, 1.5f, 1)
                },
                new RectF[] {
                        new RectF(0, 0, 1.5f, 1)
                }
        );
    }

    @Test
    public void testMergeRectangleIntoList_addRectangleThatIntersectsAndHasTheSameHeightOnLeft() {
        testExpandRectangleList(
                new RectF[] {
                        new RectF(0.5f, 0, 1.5f, 1),
                        new RectF(0, 0, 1, 1)
                },
                new RectF[] {
                        new RectF(0, 0, 1.5f, 1)
                }
        );
    }

    @Test
    public void testMergeRectangleIntoList_addRectangleThatExpandsToTheRight() {
        testExpandRectangleList(
                new RectF[] {
                        new RectF(0, 0, 1, 1),
                        new RectF(1, 0, 1.5f, 1)
                },
                new RectF[] {
                        new RectF(0, 0, 1.5f, 1)
                }
        );
    }

    @Test
    public void testMergeRectangleIntoList_addRectangleThatExpandsToTheLeft() {
        testExpandRectangleList(
                new RectF[] {
                        new RectF(1, 0, 1.5f, 1),
                        new RectF(0, 0, 1, 1)
                },
                new RectF[] {
                        new RectF(0, 0, 1.5f, 1)
                }
        );
    }


    @Test
    public void testMergeRectangleIntoList_addRectangleMadeObsoleteByMultipleExistingRectangles() {
        testExpandRectangleList(
                new RectF[] {
                        new RectF(0, 0, 1, 1),
                        new RectF(0.5f, 0, 1.5f, 1),
                        new RectF(0.25f, 0, 1.25f, 1)
                },
                new RectF[] {
                        new RectF(0, 0, 1.5f, 1)
                }
        );
    }

    @Test
    public void testMergeRectangleIntoList_threeRectanglesThatTouchEachOther() {
        testExpandRectangleList(
                new RectF[] {
                        new RectF(0, 0, 1, 1),
                        new RectF(1, 0, 2, 1),
                        new RectF(2, 0, 3, 1)
                },
                new RectF[] {
                        new RectF(0, 0, 3, 1)
                }
        );
    }


    private void testExpandRectangleList(final RectF[] inputRectangles,
            final RectF[] outputRectangles) {
        final List<RectF> expectedOutput = Arrays.asList(outputRectangles);

        final List<RectF> result = new ArrayList<>();
        final int size = inputRectangles.length;
        for (int index = 0; index < size; ++index) {
            SelectionActionModeHelper.mergeRectangleIntoList(result, inputRectangles[index],
                    identity(), identity());
        }

        assertEquals(expectedOutput, result);
    }


}
