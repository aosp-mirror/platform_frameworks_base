/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.accessibility;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.everyItem;
import static org.hamcrest.MatcherAssert.assertThat;

import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.GestureDescription.GestureStep;
import android.accessibilityservice.GestureDescription.MotionEventGenerator;
import android.accessibilityservice.GestureDescription.StrokeDescription;
import android.graphics.Path;
import android.graphics.PointF;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Test;

import java.util.List;

import static junit.framework.TestCase.assertEquals;

/**
 * Tests for GestureDescription
 */
public class GestureDescriptionTest {
    @Test
    public void testGestureShorterThanSampleRate_producesStartAndEnd() {
        PointF click = new PointF(10, 20);
        Path clickPath = new Path();
        clickPath.moveTo(click.x, click.y);
        StrokeDescription clickStroke = new StrokeDescription(clickPath, 0, 10);
        GestureDescription.Builder clickBuilder = new GestureDescription.Builder();
        clickBuilder.addStroke(clickStroke);
        GestureDescription clickGesture = clickBuilder.build();

        List<GestureStep> clickGestureSteps = MotionEventGenerator
                .getGestureStepsFromGestureDescription(clickGesture, 100);

        assertEquals(2, clickGestureSteps.size());
        assertThat(clickGestureSteps.get(0), allOf(numTouchPointsIs(1), numStartsOfStroke(1),
                numEndsOfStroke(0), hasPoint(click)));
        assertThat(clickGestureSteps.get(1), allOf(numTouchPointsIs(1), numStartsOfStroke(0),
                numEndsOfStroke(1), hasPoint(click)));
    }

    @Test
    public void testSwipe_shouldContainEvenlySpacedPoints() {
        int samplePeriod = 10;
        int numSamples = 5;
        float stepX = 2;
        float stepY = 3;
        PointF start = new PointF(10, 20);
        PointF end = new PointF(10 + numSamples * stepX, 20 + numSamples * stepY);

        GestureDescription swipe =
                createSwipe(start.x, start.y, end.x, end.y, numSamples * samplePeriod);
        List<GestureStep> swipeGestureSteps = MotionEventGenerator
                .getGestureStepsFromGestureDescription(swipe, samplePeriod);
        assertEquals(numSamples + 1, swipeGestureSteps.size());

        assertThat(swipeGestureSteps.get(0), allOf(numTouchPointsIs(1), numStartsOfStroke(1),
                numEndsOfStroke(0), hasPoint(start)));
        assertThat(swipeGestureSteps.get(numSamples), allOf(numTouchPointsIs(1),
                numStartsOfStroke(0), numEndsOfStroke(1), hasPoint(end)));

        for (int i = 1; i < numSamples; ++i) {
            PointF interpPoint = new PointF(start.x + stepX * i, start.y + stepY * i);
            assertThat(swipeGestureSteps.get(i), allOf(numTouchPointsIs(1),
                    numStartsOfStroke(0), numEndsOfStroke(0), hasPoint(interpPoint)));
        }
    }

    @Test
    public void testSwipeWithNonIntegerValues_shouldRound() {
        int strokeTime = 10;

        GestureDescription swipe = createSwipe(10.1f, 20.6f, 11.9f, 22.1f, strokeTime);
        List<GestureStep> swipeGestureSteps = MotionEventGenerator
                .getGestureStepsFromGestureDescription(swipe, strokeTime);
        assertEquals(2, swipeGestureSteps.size());
        assertThat(swipeGestureSteps.get(0), hasPoint(new PointF(10, 21)));
        assertThat(swipeGestureSteps.get(1), hasPoint(new PointF(12, 22)));
    }

    @Test
    public void testPathsWithOverlappingTiming_produceCorrectSteps() {
        // There are 4 paths
        // 0: an L-shaped path that starts first
        // 1: a swipe that starts in the middle of the L-shaped path and ends when the L ends
        // 2: a swipe that starts at the same time as #1 but extends past the end of the L
        // 3: a swipe that starts when #3 ends
        PointF path0Start = new PointF(100, 150);
        PointF path0Turn = new PointF(100, 200);
        PointF path0End = new PointF(250, 200);
        int path0StartTime = 0;
        int path0EndTime = 100;
        int path0Duration = path0EndTime - path0StartTime;
        Path path0 = new Path();
        path0.moveTo(path0Start.x, path0Start.y);
        path0.lineTo(path0Turn.x, path0Turn.y);
        path0.lineTo(path0End.x, path0End.y);
        StrokeDescription path0Stroke = new StrokeDescription(path0, path0StartTime, path0Duration);

        PointF path1Start = new PointF(300, 350);
        PointF path1End = new PointF(300, 400);
        int path1StartTime = 50;
        int path1EndTime = path0EndTime;
        StrokeDescription path1Stroke = createSwipeStroke(
                path1Start.x, path1Start.y, path1End.x, path1End.y, path1StartTime, path1EndTime);

        PointF path2Start = new PointF(400, 450);
        PointF path2End = new PointF(400, 500);
        int path2StartTime = 50;
        int path2EndTime = 150;
        StrokeDescription path2Stroke = createSwipeStroke(
                path2Start.x, path2Start.y, path2End.x, path2End.y, path2StartTime, path2EndTime);

        PointF path3Start = new PointF(500, 550);
        PointF path3End = new PointF(500, 600);
        int path3StartTime = path2EndTime;
        int path3EndTime = 200;
        StrokeDescription path3Stroke = createSwipeStroke(
                path3Start.x, path3Start.y, path3End.x, path3End.y, path3StartTime, path3EndTime);

        int deltaT = 12; // Force samples to happen on extra boundaries
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(path0Stroke);
        builder.addStroke(path1Stroke);
        builder.addStroke(path2Stroke);
        builder.addStroke(path3Stroke);
        List<GestureStep> steps = MotionEventGenerator
                .getGestureStepsFromGestureDescription(builder.build(), deltaT);

        long start = 0;
        assertThat(steps.get(0), allOf(numStartsOfStroke(1), numEndsOfStroke(0), isAtTime(start),
                numTouchPointsIs(1), hasPoint(path0Start)));
        assertThat(steps.get(1), allOf(numTouchPointsIs(1), noStartsOrEnds(),
                isAtTime(start + deltaT)));
        assertThat(steps.get(2), allOf(numTouchPointsIs(1), isAtTime(start + deltaT * 2)));
        assertThat(steps.get(3), allOf(numTouchPointsIs(1), isAtTime(start + deltaT * 3)));
        assertThat(steps.get(4), allOf(numTouchPointsIs(1), isAtTime(start + deltaT * 4)));

        assertThat(steps.get(5), allOf(numTouchPointsIs(3), numStartsOfStroke(2),
                numEndsOfStroke(0), isAtTime(path1StartTime), hasPoint(path1Start),
                hasPoint(path2Start)));

        start = path1StartTime;
        assertThat(steps.get(6), allOf(numTouchPointsIs(3), isAtTime(start + deltaT * 1)));
        assertThat(steps.get(7), allOf(noStartsOrEnds(), isAtTime(start + deltaT * 2)));
        assertThat(steps.get(8), allOf(numTouchPointsIs(3), isAtTime(start + deltaT * 3)));
        assertThat(steps.get(9), allOf(noStartsOrEnds(), isAtTime(start + deltaT * 4)));

        assertThat(steps.get(10), allOf(numTouchPointsIs(3), numStartsOfStroke(0),
                numEndsOfStroke(2), isAtTime(path0EndTime), hasPoint(path0End),
                hasPoint(path1End)));

        start = path0EndTime;
        assertThat(steps.get(11), allOf(numTouchPointsIs(1), isAtTime(start + deltaT * 1)));
        assertThat(steps.get(12), allOf(noStartsOrEnds(), isAtTime(start + deltaT * 2)));
        assertThat(steps.get(13), allOf(numTouchPointsIs(1), isAtTime(start + deltaT * 3)));
        assertThat(steps.get(14), allOf(noStartsOrEnds(), isAtTime(start + deltaT * 4)));

        assertThat(steps.get(15), allOf(numTouchPointsIs(2), numStartsOfStroke(1),
                numEndsOfStroke(1), isAtTime(path2EndTime), hasPoint(path2End),
                hasPoint(path3Start)));

        start = path2EndTime;
        assertThat(steps.get(16), allOf(numTouchPointsIs(1), isAtTime(start + deltaT * 1)));
        assertThat(steps.get(17), allOf(noStartsOrEnds(), isAtTime(start + deltaT * 2)));
        assertThat(steps.get(18), allOf(numTouchPointsIs(1), isAtTime(start + deltaT * 3)));
        assertThat(steps.get(19), allOf(noStartsOrEnds(), isAtTime(start + deltaT * 4)));

        assertThat(steps.get(20), allOf(numTouchPointsIs(1), numStartsOfStroke(0),
                numEndsOfStroke(1), isAtTime(path3EndTime), hasPoint(path3End)));
    }

    @Test
    public void testMaxTouchpoints_shouldHaveValidCoords() {
        GestureDescription.Builder maxPointBuilder = new GestureDescription.Builder();
        PointF baseStartPoint = new PointF(100, 100);
        PointF baseEndPoint = new PointF(100, 200);
        int xStep = 10;
        int samplePeriod = 15;
        int numSamples = 2;
        int numPoints = GestureDescription.getMaxStrokeCount();
        for (int i = 0; i < numPoints; i++) {
            Path path = new Path();
            path.moveTo(baseStartPoint.x + i * xStep, baseStartPoint.y);
            path.lineTo(baseEndPoint.x + i * xStep, baseEndPoint.y);
            maxPointBuilder.addStroke(new StrokeDescription(path, 0, samplePeriod * numSamples));
        }

        List<GestureStep> steps = MotionEventGenerator
                .getGestureStepsFromGestureDescription(maxPointBuilder.build(), samplePeriod);
        assertEquals(3, steps.size());

        assertThat(steps.get(0), allOf(numTouchPointsIs(numPoints), numStartsOfStroke(numPoints),
                numEndsOfStroke(0), isAtTime(0)));
        assertThat(steps.get(1), allOf(numTouchPointsIs(numPoints), numStartsOfStroke(0),
                numEndsOfStroke(0), isAtTime(samplePeriod)));
        assertThat(steps.get(2), allOf(numTouchPointsIs(numPoints), numStartsOfStroke(0),
                numEndsOfStroke(numPoints), isAtTime(samplePeriod * 2)));

        PointF baseMidPoint = new PointF((baseStartPoint.x + baseEndPoint.x) / 2,
                (baseStartPoint.y + baseEndPoint.y) / 2);
        for (int i = 0; i < numPoints; i++) {
            assertThat(steps.get(0),
                    hasPoint(new PointF(baseStartPoint.x + i * xStep, baseStartPoint.y)));
            assertThat(steps.get(1),
                    hasPoint(new PointF(baseMidPoint.x + i * xStep, baseMidPoint.y)));
            assertThat(steps.get(2),
                    hasPoint(new PointF(baseEndPoint.x + i * xStep, baseEndPoint.y)));
        }
    }

    @Test
    public void testGetGestureSteps_touchPointsHaveStrokeId() {
        StrokeDescription swipeStroke = createSwipeStroke(10, 20, 30, 40, 0, 100);
        GestureDescription swipe = new GestureDescription.Builder().addStroke(swipeStroke).build();
        List<GestureStep> swipeGestureSteps = MotionEventGenerator
                .getGestureStepsFromGestureDescription(swipe, 10);

        assertThat(swipeGestureSteps, everyItem(hasStrokeId(swipeStroke.getId())));
    }

    @Test
    public void testGetGestureSteps_continuedStroke_hasNoEndPoint() {
        Path swipePath = new Path();
        swipePath.moveTo(10, 20);
        swipePath.lineTo(30, 40);
        StrokeDescription stroke1 =
                new StrokeDescription(swipePath, 0, 100, true);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke1).build();
        List<GestureStep> steps = MotionEventGenerator
                .getGestureStepsFromGestureDescription(gesture, 10);

        assertThat(steps, everyItem(numEndsOfStroke(0)));
    }

    @Test
    public void testGetGestureSteps_continuingStroke_hasNoStartPointAndHasContinuedId() {
        Path swipePath = new Path();
        swipePath.moveTo(10, 20);
        swipePath.lineTo(30, 40);
        StrokeDescription stroke1 =
                new StrokeDescription(swipePath, 0, 100, true);
        StrokeDescription stroke2 = stroke1.continueStroke(swipePath, 0, 100, false);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke2).build();
        List<GestureStep> steps = MotionEventGenerator
                .getGestureStepsFromGestureDescription(gesture, 10);

        assertThat(steps, everyItem(
                allOf(continuesStrokeId(stroke1.getId()), numStartsOfStroke(0))));
    }

    private GestureDescription createSwipe(
            float startX, float startY, float endX, float endY, long duration) {
        GestureDescription.Builder swipeBuilder = new GestureDescription.Builder();
        swipeBuilder.addStroke(createSwipeStroke(startX, startY, endX, endY, 0, duration));
        return swipeBuilder.build();
    }

    private StrokeDescription createSwipeStroke(
            float startX, float startY, float endX, float endY, long startTime, long endTime) {
        Path swipePath = new Path();
        swipePath.moveTo(startX, startY);
        swipePath.lineTo(endX, endY);
        StrokeDescription swipeStroke =
                new StrokeDescription(swipePath, startTime, endTime - startTime);
        return swipeStroke;
    }

    Matcher<GestureStep> numTouchPointsIs(final int numTouchPoints) {
        return new TypeSafeMatcher<GestureStep>() {
            @Override
            protected boolean matchesSafely(GestureStep gestureStep) {
                return gestureStep.numTouchPoints == numTouchPoints;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("Has " + numTouchPoints + " touch point(s)");
            }
        };
    }

    Matcher<GestureStep> numStartsOfStroke(final int numStarts) {
        return new TypeSafeMatcher<GestureStep>() {
            @Override
            protected boolean matchesSafely(GestureStep gestureStep) {
                int numStartsFound = 0;
                for (int i = 0; i < gestureStep.numTouchPoints; i++) {
                    if (gestureStep.touchPoints[i].mIsStartOfPath) {
                        numStartsFound++;
                    }
                }
                return numStartsFound == numStarts;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("Starts " + numStarts + " stroke(s)");
            }
        };
    }

    Matcher<GestureStep> numEndsOfStroke(final int numEnds) {
        return new TypeSafeMatcher<GestureStep>() {
            @Override
            protected boolean matchesSafely(GestureStep gestureStep) {
                int numEndsFound = 0;
                for (int i = 0; i < gestureStep.numTouchPoints; i++) {
                    if (gestureStep.touchPoints[i].mIsEndOfPath) {
                        numEndsFound++;
                    }
                }
                return numEndsFound == numEnds;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("Ends " + numEnds + " stroke(s)");
            }
        };
    }

    Matcher<GestureStep> hasPoint(final PointF point) {
        return new TypeSafeMatcher<GestureStep>() {
            @Override
            protected boolean matchesSafely(GestureStep gestureStep) {
                for (int i = 0; i < gestureStep.numTouchPoints; i++) {
                    if ((gestureStep.touchPoints[i].mX == point.x)
                            && (gestureStep.touchPoints[i].mY == point.y)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("Has at least one point at " + point);
            }
        };
    }

    Matcher<GestureStep> hasStrokeId(final int strokeId) {
        return new TypeSafeMatcher<GestureStep>() {
            @Override
            protected boolean matchesSafely(GestureStep gestureStep) {
                for (int i = 0; i < gestureStep.numTouchPoints; i++) {
                    if (gestureStep.touchPoints[i].mStrokeId == strokeId) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("Has at least one point with stroke id " + strokeId);
            }
        };
    }

    Matcher<GestureStep> continuesStrokeId(final int strokeId) {
        return new TypeSafeMatcher<GestureStep>() {
            @Override
            protected boolean matchesSafely(GestureStep gestureStep) {
                for (int i = 0; i < gestureStep.numTouchPoints; i++) {
                    if (gestureStep.touchPoints[i].mContinuedStrokeId == strokeId) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("Continues stroke id " + strokeId);
            }
        };
    }

    Matcher<GestureStep> isAtTime(final long time) {
        return new TypeSafeMatcher<GestureStep>() {
            @Override
            protected boolean matchesSafely(GestureStep gestureStep) {
                return gestureStep.timeSinceGestureStart == time;
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("Is at time " + time);
            }
        };
    }

    Matcher<GestureStep> noStartsOrEnds() {
        return allOf(numStartsOfStroke(0), numEndsOfStroke(0));
    }
}
