/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.widget;

import static com.android.internal.widget.NotificationProgressBar.NotEnoughWidthToFitAllPartsException;

import static com.google.common.truth.Truth.assertThat;

import android.app.Notification.ProgressStyle;
import android.graphics.Color;
import android.util.Pair;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.widget.NotificationProgressBar.Part;
import com.android.internal.widget.NotificationProgressBar.Point;
import com.android.internal.widget.NotificationProgressBar.Segment;
import com.android.internal.widget.NotificationProgressDrawable.DrawablePart;
import com.android.internal.widget.NotificationProgressDrawable.DrawablePoint;
import com.android.internal.widget.NotificationProgressDrawable.DrawableSegment;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class NotificationProgressBarTest {

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToParts_segmentsIsEmpty() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 50;
        int progressMax = 100;

        NotificationProgressBar.processModelAndConvertToViewParts(segments, points, progress,
                progressMax);
    }

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToParts_segmentsLengthNotMatchingProgressMax() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(50));
        segments.add(new ProgressStyle.Segment(100));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 50;
        int progressMax = 100;

        NotificationProgressBar.processModelAndConvertToViewParts(segments, points, progress,
                progressMax);
    }

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToParts_segmentLengthIsNegative() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(-50));
        segments.add(new ProgressStyle.Segment(150));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 50;
        int progressMax = 100;

        NotificationProgressBar.processModelAndConvertToViewParts(segments, points, progress,
                progressMax);
    }

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToParts_segmentLengthIsZero() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(0));
        segments.add(new ProgressStyle.Segment(100));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 50;
        int progressMax = 100;

        NotificationProgressBar.processModelAndConvertToViewParts(segments, points, progress,
                progressMax);
    }

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToParts_progressIsNegative() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = -50;
        int progressMax = 100;

        NotificationProgressBar.processModelAndConvertToViewParts(segments, points, progress,
                progressMax);
    }

    @Test
    public void processAndConvertToParts_progressIsZero()
            throws NotificationProgressBar.NotEnoughWidthToFitAllPartsException {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100).setColor(Color.RED));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 0;
        int progressMax = 100;

        List<Part> parts = NotificationProgressBar.processModelAndConvertToViewParts(segments,
                points, progress, progressMax);

        List<Part> expectedParts = new ArrayList<>(List.of(new Segment(1f, Color.RED)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 300;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;

        List<DrawablePart> drawableParts =
                NotificationProgressBar.processPartsAndConvertToDrawableParts(
                        parts, drawableWidth, segSegGap, segPointGap, pointRadius, hasTrackerIcon);

        List<DrawablePart> expectedDrawableParts = new ArrayList<>(
                List.of(new DrawableSegment(0, 300, Color.RED)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);

        float segmentMinWidth = 16;
        boolean isStyledByProgress = true;

        Pair<List<DrawablePart>, Float> p = NotificationProgressBar.maybeStretchAndRescaleSegments(
                parts, drawableParts, segmentMinWidth, pointRadius, (float) progress / progressMax,
                300, isStyledByProgress, hasTrackerIcon ? 0 : segSegGap);

        // Colors with 50% opacity
        int fadedRed = 0x80FF0000;
        expectedDrawableParts = new ArrayList<>(
                List.of(new DrawableSegment(0, 300, fadedRed, true)));

        assertThat(p.second).isEqualTo(0);
        assertThat(p.first).isEqualTo(expectedDrawableParts);
    }

    @Test
    public void processAndConvertToParts_progressAtMax()
            throws NotEnoughWidthToFitAllPartsException {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100).setColor(Color.RED));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 100;
        int progressMax = 100;

        List<Part> parts = NotificationProgressBar.processModelAndConvertToViewParts(segments,
                points, progress, progressMax);

        List<Part> expectedParts = new ArrayList<>(List.of(new Segment(1f, Color.RED)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 300;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;

        List<DrawablePart> drawableParts =
                NotificationProgressBar.processPartsAndConvertToDrawableParts(
                        parts, drawableWidth, segSegGap, segPointGap, pointRadius, hasTrackerIcon);

        List<DrawablePart> expectedDrawableParts = new ArrayList<>(
                List.of(new DrawableSegment(0, 300, Color.RED)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);

        float segmentMinWidth = 16;
        boolean isStyledByProgress = true;

        Pair<List<DrawablePart>, Float> p = NotificationProgressBar.maybeStretchAndRescaleSegments(
                parts, drawableParts, segmentMinWidth, pointRadius, (float) progress / progressMax,
                300, isStyledByProgress, hasTrackerIcon ? 0 : segSegGap);

        assertThat(p.second).isEqualTo(300);
        assertThat(p.first).isEqualTo(expectedDrawableParts);
    }

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToParts_progressAboveMax() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 150;
        int progressMax = 100;

        NotificationProgressBar.processModelAndConvertToViewParts(segments, points, progress,
                progressMax);
    }

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToParts_pointPositionIsNegative() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100));
        List<ProgressStyle.Point> points = new ArrayList<>();
        points.add(new ProgressStyle.Point(-50).setColor(Color.RED));
        int progress = 50;
        int progressMax = 100;

        NotificationProgressBar.processModelAndConvertToViewParts(segments, points, progress,
                progressMax);
    }

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToParts_pointPositionAboveMax() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100));
        List<ProgressStyle.Point> points = new ArrayList<>();
        points.add(new ProgressStyle.Point(150).setColor(Color.RED));
        int progress = 50;
        int progressMax = 100;

        NotificationProgressBar.processModelAndConvertToViewParts(segments, points, progress,
                progressMax);
    }

    @Test
    public void processAndConvertToParts_singleSegmentWithoutPoints()
            throws NotEnoughWidthToFitAllPartsException {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100).setColor(Color.BLUE));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 60;
        int progressMax = 100;

        List<Part> parts = NotificationProgressBar.processModelAndConvertToViewParts(segments,
                points, progress, progressMax);

        List<Part> expectedParts = new ArrayList<>(
                List.of(new Segment(1, Color.BLUE)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 300;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;

        List<DrawablePart> drawableParts =
                NotificationProgressBar.processPartsAndConvertToDrawableParts(
                        parts, drawableWidth, segSegGap, segPointGap, pointRadius, hasTrackerIcon);

        List<DrawablePart> expectedDrawableParts = new ArrayList<>(
                List.of(new DrawableSegment(0, 300, Color.BLUE)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);

        float segmentMinWidth = 16;
        boolean isStyledByProgress = true;

        Pair<List<DrawablePart>, Float> p = NotificationProgressBar.maybeStretchAndRescaleSegments(
                parts, drawableParts, segmentMinWidth, pointRadius, (float) progress / progressMax,
                300, isStyledByProgress, hasTrackerIcon ? 0 : segSegGap);

        // Colors with 50% opacity
        int fadedBlue = 0x800000FF;
        expectedDrawableParts = new ArrayList<>(
                List.of(new DrawableSegment(0, 180, Color.BLUE),
                        new DrawableSegment(180, 300, fadedBlue, true)));

        assertThat(p.second).isEqualTo(180);
        assertThat(p.first).isEqualTo(expectedDrawableParts);
    }

    @Test
    public void processAndConvertToParts_multipleSegmentsWithoutPoints()
            throws NotEnoughWidthToFitAllPartsException {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(50).setColor(Color.RED));
        segments.add(new ProgressStyle.Segment(50).setColor(Color.GREEN));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 60;
        int progressMax = 100;

        List<Part> parts = NotificationProgressBar.processModelAndConvertToViewParts(segments,
                points, progress, progressMax);

        List<Part> expectedParts = new ArrayList<>(
                List.of(new Segment(0.50f, Color.RED), new Segment(0.50f, Color.GREEN)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 300;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;

        List<DrawablePart> drawableParts =
                NotificationProgressBar.processPartsAndConvertToDrawableParts(
                        parts, drawableWidth, segSegGap, segPointGap, pointRadius, hasTrackerIcon);

        List<DrawablePart> expectedDrawableParts = new ArrayList<>(
                List.of(new DrawableSegment(0, 146, Color.RED),
                        new DrawableSegment(150, 300, Color.GREEN)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);

        float segmentMinWidth = 16;
        boolean isStyledByProgress = true;
        Pair<List<DrawablePart>, Float> p = NotificationProgressBar.maybeStretchAndRescaleSegments(
                parts, drawableParts, segmentMinWidth, pointRadius, (float) progress / progressMax,
                300, isStyledByProgress, hasTrackerIcon ? 0 : segSegGap);

        // Colors with 50% opacity
        int fadedGreen = 0x8000FF00;
        expectedDrawableParts = new ArrayList<>(List.of(new DrawableSegment(0, 146, Color.RED),
                new DrawableSegment(150, 180, Color.GREEN),
                new DrawableSegment(180, 300, fadedGreen, true)));

        assertThat(p.second).isEqualTo(180);
        assertThat(p.first).isEqualTo(expectedDrawableParts);
    }

    @Test
    public void processAndConvertToParts_multipleSegmentsWithoutPoints_noTracker()
            throws NotEnoughWidthToFitAllPartsException {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(50).setColor(Color.RED));
        segments.add(new ProgressStyle.Segment(50).setColor(Color.GREEN));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 60;
        int progressMax = 100;
        List<Part> parts = NotificationProgressBar.processModelAndConvertToViewParts(segments,
                points, progress, progressMax);

        List<Part> expectedParts = new ArrayList<>(
                List.of(new Segment(0.50f, Color.RED), new Segment(0.50f, Color.GREEN)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 300;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = false;

        List<DrawablePart> drawableParts =
                NotificationProgressBar.processPartsAndConvertToDrawableParts(
                        parts, drawableWidth, segSegGap, segPointGap, pointRadius, hasTrackerIcon);

        List<DrawablePart> expectedDrawableParts = new ArrayList<>(
                List.of(new DrawableSegment(0, 146, Color.RED),
                        new DrawableSegment(150, 300, Color.GREEN)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);

        float segmentMinWidth = 16;
        boolean isStyledByProgress = true;
        Pair<List<DrawablePart>, Float> p = NotificationProgressBar.maybeStretchAndRescaleSegments(
                parts, drawableParts, segmentMinWidth, pointRadius, (float) progress / progressMax,
                300, isStyledByProgress, hasTrackerIcon ? 0 : segSegGap);

        // Colors with 50% opacity
        int fadedGreen = 0x8000FF00;
        expectedDrawableParts = new ArrayList<>(List.of(new DrawableSegment(0, 146, Color.RED),
                new DrawableSegment(150, 176, Color.GREEN),
                new DrawableSegment(180, 300, fadedGreen, true)));

        assertThat(p.second).isEqualTo(180);
        assertThat(p.first).isEqualTo(expectedDrawableParts);
    }

    @Test
    public void processAndConvertToParts_singleSegmentWithPoints()
            throws NotEnoughWidthToFitAllPartsException {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100).setColor(Color.BLUE));
        List<ProgressStyle.Point> points = new ArrayList<>();
        points.add(new ProgressStyle.Point(15).setColor(Color.RED));
        points.add(new ProgressStyle.Point(25).setColor(Color.BLUE));
        points.add(new ProgressStyle.Point(60).setColor(Color.BLUE));
        points.add(new ProgressStyle.Point(75).setColor(Color.YELLOW));
        int progress = 60;
        int progressMax = 100;

        List<Part> parts = NotificationProgressBar.processModelAndConvertToViewParts(segments,
                points, progress, progressMax);

        List<Part> expectedParts = new ArrayList<>(
                List.of(new Segment(0.15f, Color.BLUE),
                        new Point(Color.RED),
                        new Segment(0.10f, Color.BLUE),
                        new Point(Color.BLUE),
                        new Segment(0.35f, Color.BLUE),
                        new Point(Color.BLUE),
                        new Segment(0.15f, Color.BLUE),
                        new Point(Color.YELLOW),
                        new Segment(0.25f, Color.BLUE)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 300;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;

        List<DrawablePart> drawableParts =
                NotificationProgressBar.processPartsAndConvertToDrawableParts(
                        parts, drawableWidth, segSegGap, segPointGap, pointRadius, hasTrackerIcon);

        List<DrawablePart> expectedDrawableParts = new ArrayList<>(
                List.of(new DrawableSegment(0, 35, Color.BLUE),
                        new DrawablePoint(39, 51, Color.RED),
                        new DrawableSegment(55, 65, Color.BLUE),
                        new DrawablePoint(69, 81, Color.BLUE),
                        new DrawableSegment(85, 170, Color.BLUE),
                        new DrawablePoint(174, 186, Color.BLUE),
                        new DrawableSegment(190, 215, Color.BLUE),
                        new DrawablePoint(219, 231, Color.YELLOW),
                        new DrawableSegment(235, 300, Color.BLUE)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);

        float segmentMinWidth = 16;
        boolean isStyledByProgress = true;

        Pair<List<DrawablePart>, Float> p = NotificationProgressBar.maybeStretchAndRescaleSegments(
                parts, drawableParts, segmentMinWidth, pointRadius, (float) progress / progressMax,
                300, isStyledByProgress, hasTrackerIcon ? 0 : segSegGap);

        // Colors with 50% opacity
        int fadedBlue = 0x800000FF;
        int fadedYellow = 0x80FFFF00;
        expectedDrawableParts = new ArrayList<>(
                List.of(new DrawableSegment(0, 34.219177F, Color.BLUE),
                        new DrawablePoint(38.219177F, 50.219177F, Color.RED),
                        new DrawableSegment(54.219177F, 70.21918F, Color.BLUE),
                        new DrawablePoint(74.21918F, 86.21918F, Color.BLUE),
                        new DrawableSegment(90.21918F, 172.38356F, Color.BLUE),
                        new DrawablePoint(176.38356F, 188.38356F, Color.BLUE),
                        new DrawableSegment(192.38356F, 217.0137F, fadedBlue, true),
                        new DrawablePoint(221.0137F, 233.0137F, fadedYellow),
                        new DrawableSegment(237.0137F, 300F, fadedBlue, true)));

        assertThat(p.second).isEqualTo(182.38356F);
        assertThat(p.first).isEqualTo(expectedDrawableParts);
    }

    @Test
    public void processAndConvertToParts_multipleSegmentsWithPoints()
            throws NotEnoughWidthToFitAllPartsException {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(50).setColor(Color.RED));
        segments.add(new ProgressStyle.Segment(50).setColor(Color.GREEN));
        List<ProgressStyle.Point> points = new ArrayList<>();
        points.add(new ProgressStyle.Point(15).setColor(Color.RED));
        points.add(new ProgressStyle.Point(25).setColor(Color.BLUE));
        points.add(new ProgressStyle.Point(60).setColor(Color.BLUE));
        points.add(new ProgressStyle.Point(75).setColor(Color.YELLOW));
        int progress = 60;
        int progressMax = 100;

        List<Part> parts = NotificationProgressBar.processModelAndConvertToViewParts(segments,
                points, progress, progressMax);

        List<Part> expectedParts = new ArrayList<>(
                List.of(new Segment(0.15f, Color.RED),
                        new Point(Color.RED),
                        new Segment(0.10f, Color.RED),
                        new Point(Color.BLUE),
                        new Segment(0.25f, Color.RED),
                        new Segment(0.10f, Color.GREEN),
                        new Point(Color.BLUE),
                        new Segment(0.15f, Color.GREEN),
                        new Point(Color.YELLOW),
                        new Segment(0.25f, Color.GREEN)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 300;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;
        List<DrawablePart> drawableParts =
                NotificationProgressBar.processPartsAndConvertToDrawableParts(
                        parts, drawableWidth, segSegGap, segPointGap, pointRadius, hasTrackerIcon);

        List<DrawablePart> expectedDrawableParts = new ArrayList<>(
                List.of(new DrawableSegment(0, 35, Color.RED), new DrawablePoint(39, 51, Color.RED),
                        new DrawableSegment(55, 65, Color.RED),
                        new DrawablePoint(69, 81, Color.BLUE),
                        new DrawableSegment(85, 146, Color.RED),
                        new DrawableSegment(150, 170, Color.GREEN),
                        new DrawablePoint(174, 186, Color.BLUE),
                        new DrawableSegment(190, 215, Color.GREEN),
                        new DrawablePoint(219, 231, Color.YELLOW),
                        new DrawableSegment(235, 300, Color.GREEN)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);

        float segmentMinWidth = 16;
        boolean isStyledByProgress = true;

        Pair<List<DrawablePart>, Float> p = NotificationProgressBar.maybeStretchAndRescaleSegments(
                parts, drawableParts, segmentMinWidth, pointRadius, (float) progress / progressMax,
                300, isStyledByProgress, hasTrackerIcon ? 0 : segSegGap);

        // Colors with 50% opacity
        int fadedGreen = 0x8000FF00;
        int fadedYellow = 0x80FFFF00;
        expectedDrawableParts = new ArrayList<>(
                List.of(new DrawableSegment(0, 34.095238F, Color.RED),
                        new DrawablePoint(38.095238F, 50.095238F, Color.RED),
                        new DrawableSegment(54.095238F, 70.09524F, Color.RED),
                        new DrawablePoint(74.09524F, 86.09524F, Color.BLUE),
                        new DrawableSegment(90.09524F, 148.9524F, Color.RED),
                        new DrawableSegment(152.95238F, 172.7619F, Color.GREEN),
                        new DrawablePoint(176.7619F, 188.7619F, Color.BLUE),
                        new DrawableSegment(192.7619F, 217.33333F, fadedGreen, true),
                        new DrawablePoint(221.33333F, 233.33333F, fadedYellow),
                        new DrawableSegment(237.33333F, 299.99997F, fadedGreen, true)));

        assertThat(p.second).isEqualTo(182.7619F);
        assertThat(p.first).isEqualTo(expectedDrawableParts);
    }

    @Test
    public void processAndConvertToParts_multipleSegmentsWithPointsAtStartAndEnd()
            throws NotEnoughWidthToFitAllPartsException {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(50).setColor(Color.RED));
        segments.add(new ProgressStyle.Segment(50).setColor(Color.GREEN));
        List<ProgressStyle.Point> points = new ArrayList<>();
        points.add(new ProgressStyle.Point(0).setColor(Color.RED));
        points.add(new ProgressStyle.Point(25).setColor(Color.BLUE));
        points.add(new ProgressStyle.Point(60).setColor(Color.BLUE));
        points.add(new ProgressStyle.Point(100).setColor(Color.YELLOW));
        int progress = 60;
        int progressMax = 100;

        List<Part> parts = NotificationProgressBar.processModelAndConvertToViewParts(segments,
                points, progress, progressMax);

        List<Part> expectedParts = new ArrayList<>(
                List.of(new Point(Color.RED),
                        new Segment(0.25f, Color.RED),
                        new Point(Color.BLUE),
                        new Segment(0.25f, Color.RED),
                        new Segment(0.10f, Color.GREEN),
                        new Point(Color.BLUE),
                        new Segment(0.4f, Color.GREEN),
                        new Point(Color.YELLOW)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 300;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;

        List<DrawablePart> drawableParts =
                NotificationProgressBar.processPartsAndConvertToDrawableParts(
                        parts, drawableWidth, segSegGap, segPointGap, pointRadius, hasTrackerIcon);

        List<DrawablePart> expectedDrawableParts = new ArrayList<>(
                List.of(new DrawablePoint(0, 12, Color.RED),
                        new DrawableSegment(16, 65, Color.RED),
                        new DrawablePoint(69, 81, Color.BLUE),
                        new DrawableSegment(85, 146, Color.RED),
                        new DrawableSegment(150, 170, Color.GREEN),
                        new DrawablePoint(174, 186, Color.BLUE),
                        new DrawableSegment(190, 284, Color.GREEN),
                        new DrawablePoint(288, 300, Color.YELLOW)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);

        float segmentMinWidth = 16;
        boolean isStyledByProgress = true;

        Pair<List<DrawablePart>, Float> p = NotificationProgressBar.maybeStretchAndRescaleSegments(
                parts, drawableParts, segmentMinWidth, pointRadius, (float) progress / progressMax,
                300, isStyledByProgress, hasTrackerIcon ? 0 : segSegGap);

        // Colors with 50% opacity
        int fadedGreen = 0x8000FF00;
        int fadedYellow = 0x80FFFF00;
        expectedDrawableParts = new ArrayList<>(
                List.of(new DrawablePoint(0, 12, Color.RED),
                        new DrawableSegment(16, 65, Color.RED),
                        new DrawablePoint(69, 81, Color.BLUE),
                        new DrawableSegment(85, 146, Color.RED),
                        new DrawableSegment(150, 170, Color.GREEN),
                        new DrawablePoint(174, 186, Color.BLUE),
                        new DrawableSegment(190, 284, fadedGreen, true),
                        new DrawablePoint(288, 300, fadedYellow)));

        assertThat(p.second).isEqualTo(180);
        assertThat(p.first).isEqualTo(expectedDrawableParts);
    }

    // The points are so close to start/end that they would go out of bounds without the minimum
    // segment width requirement.
    @Test
    public void processAndConvertToParts_multipleSegmentsWithPointsNearStartAndEnd()
            throws NotEnoughWidthToFitAllPartsException {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(50).setColor(Color.RED));
        segments.add(new ProgressStyle.Segment(50).setColor(Color.GREEN));
        List<ProgressStyle.Point> points = new ArrayList<>();
        points.add(new ProgressStyle.Point(1).setColor(Color.RED));
        points.add(new ProgressStyle.Point(25).setColor(Color.BLUE));
        points.add(new ProgressStyle.Point(60).setColor(Color.BLUE));
        points.add(new ProgressStyle.Point(99).setColor(Color.YELLOW));
        int progress = 60;
        int progressMax = 100;

        List<Part> parts = NotificationProgressBar.processModelAndConvertToViewParts(segments,
                points, progress, progressMax);

        List<Part> expectedParts = new ArrayList<>(
                List.of(new Segment(0.01f, Color.RED),
                        new Point(Color.RED),
                        new Segment(0.24f, Color.RED),
                        new Point(Color.BLUE),
                        new Segment(0.25f, Color.RED),
                        new Segment(0.10f, Color.GREEN),
                        new Point(Color.BLUE),
                        new Segment(0.39f, Color.GREEN),
                        new Point(Color.YELLOW),
                        new Segment(0.01f, Color.GREEN)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 300;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;

        List<DrawablePart> drawableParts =
                NotificationProgressBar.processPartsAndConvertToDrawableParts(
                        parts, drawableWidth, segSegGap, segPointGap, pointRadius, hasTrackerIcon);

        List<DrawablePart> expectedDrawableParts = new ArrayList<>(
                List.of(new DrawableSegment(0, -7, Color.RED),
                        new DrawablePoint(-3, 9, Color.RED),
                        new DrawableSegment(13, 65, Color.RED),
                        new DrawablePoint(69, 81, Color.BLUE),
                        new DrawableSegment(85, 146, Color.RED),
                        new DrawableSegment(150, 170, Color.GREEN),
                        new DrawablePoint(174, 186, Color.BLUE),
                        new DrawableSegment(190, 287, Color.GREEN),
                        new DrawablePoint(291, 303, Color.YELLOW),
                        new DrawableSegment(307, 300, Color.GREEN)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);

        float segmentMinWidth = 16;
        boolean isStyledByProgress = true;

        Pair<List<DrawablePart>, Float> p = NotificationProgressBar.maybeStretchAndRescaleSegments(
                parts, drawableParts, segmentMinWidth, pointRadius, (float) progress / progressMax,
                300, isStyledByProgress, hasTrackerIcon ? 0 : segSegGap);

        // Colors with 50% opacity
        int fadedGreen = 0x8000FF00;
        int fadedYellow = 0x80FFFF00;
        expectedDrawableParts = new ArrayList<>(
                List.of(new DrawableSegment(0, 16, Color.RED),
                        new DrawablePoint(20, 32, Color.RED),
                        new DrawableSegment(36, 78.02409F, Color.RED),
                        new DrawablePoint(82.02409F, 94.02409F, Color.BLUE),
                        new DrawableSegment(98.02409F, 146.55421F, Color.RED),
                        new DrawableSegment(150.55421F, 169.44579F, Color.GREEN),
                        new DrawablePoint(173.44579F, 185.44579F, Color.BLUE),
                        new DrawableSegment(189.44579F, 264, fadedGreen, true),
                        new DrawablePoint(268, 280, fadedYellow),
                        new DrawableSegment(284, 300, fadedGreen, true)));

        assertThat(p.second).isEqualTo(179.44579F);
        assertThat(p.first).isEqualTo(expectedDrawableParts);
    }

    @Test
    public void processAndConvertToParts_multipleSegmentsWithPoints_notStyledByProgress()
            throws NotEnoughWidthToFitAllPartsException {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(50).setColor(Color.RED));
        segments.add(new ProgressStyle.Segment(50).setColor(Color.GREEN));
        List<ProgressStyle.Point> points = new ArrayList<>();
        points.add(new ProgressStyle.Point(15).setColor(Color.RED));
        points.add(new ProgressStyle.Point(25).setColor(Color.BLUE));
        points.add(new ProgressStyle.Point(75).setColor(Color.YELLOW));
        int progress = 60;
        int progressMax = 100;

        List<Part> parts = NotificationProgressBar.processModelAndConvertToViewParts(segments,
                points, progress, progressMax);

        List<Part> expectedParts = new ArrayList<>(
                List.of(new Segment(0.15f, Color.RED), new Point(Color.RED),
                        new Segment(0.10f, Color.RED), new Point(Color.BLUE),
                        new Segment(0.25f, Color.RED), new Segment(0.25f, Color.GREEN),
                        new Point(Color.YELLOW), new Segment(0.25f, Color.GREEN)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 300;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;

        List<DrawablePart> drawableParts =
                NotificationProgressBar.processPartsAndConvertToDrawableParts(
                        parts, drawableWidth, segSegGap, segPointGap, pointRadius, hasTrackerIcon);

        List<DrawablePart> expectedDrawableParts = new ArrayList<>(
                List.of(new DrawableSegment(0, 35, Color.RED), new DrawablePoint(39, 51, Color.RED),
                        new DrawableSegment(55, 65, Color.RED),
                        new DrawablePoint(69, 81, Color.BLUE),
                        new DrawableSegment(85, 146, Color.RED),
                        new DrawableSegment(150, 215, Color.GREEN),
                        new DrawablePoint(219, 231, Color.YELLOW),
                        new DrawableSegment(235, 300, Color.GREEN)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);

        float segmentMinWidth = 16;
        boolean isStyledByProgress = false;

        Pair<List<DrawablePart>, Float> p = NotificationProgressBar.maybeStretchAndRescaleSegments(
                parts, drawableParts, segmentMinWidth, pointRadius, (float) progress / progressMax,
                300, isStyledByProgress, hasTrackerIcon ? 0 : segSegGap);

        expectedDrawableParts = new ArrayList<>(
                List.of(new DrawableSegment(0, 34.296295F, Color.RED),
                        new DrawablePoint(38.296295F, 50.296295F, Color.RED),
                        new DrawableSegment(54.296295F, 70.296295F, Color.RED),
                        new DrawablePoint(74.296295F, 86.296295F, Color.BLUE),
                        new DrawableSegment(90.296295F, 149.62962F, Color.RED),
                        new DrawableSegment(153.62962F, 216.8148F, Color.GREEN),
                        new DrawablePoint(220.81482F, 232.81482F, Color.YELLOW),
                        new DrawableSegment(236.81482F, 300, Color.GREEN)));

        assertThat(p.second).isEqualTo(182.9037F);
        assertThat(p.first).isEqualTo(expectedDrawableParts);
    }

    // The only difference from the `zeroWidthDrawableSegment` test below is the longer
    // segmentMinWidth (= 16dp).
    @Test
    public void maybeStretchAndRescaleSegments_negativeWidthDrawableSegment()
            throws NotEnoughWidthToFitAllPartsException {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100).setColor(Color.BLUE));
        segments.add(new ProgressStyle.Segment(200).setColor(Color.BLUE));
        segments.add(new ProgressStyle.Segment(300).setColor(Color.BLUE));
        segments.add(new ProgressStyle.Segment(400).setColor(Color.BLUE));
        List<ProgressStyle.Point> points = new ArrayList<>();
        points.add(new ProgressStyle.Point(0).setColor(Color.BLUE));
        int progress = 1000;
        int progressMax = 1000;

        List<Part> parts = NotificationProgressBar.processModelAndConvertToViewParts(segments,
                points, progress, progressMax);

        List<Part> expectedParts = new ArrayList<>(
                List.of(new Point(Color.BLUE), new Segment(0.1f, Color.BLUE),
                        new Segment(0.2f, Color.BLUE), new Segment(0.3f, Color.BLUE),
                        new Segment(0.4f, Color.BLUE)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 200;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;

        List<DrawablePart> drawableParts =
                NotificationProgressBar.processPartsAndConvertToDrawableParts(
                        parts, drawableWidth, segSegGap, segPointGap, pointRadius, hasTrackerIcon);

        List<DrawablePart> expectedDrawableParts = new ArrayList<>(
                List.of(new DrawablePoint(0, 12, Color.BLUE),
                        new DrawableSegment(16, 16, Color.BLUE),
                        new DrawableSegment(20, 56, Color.BLUE),
                        new DrawableSegment(60, 116, Color.BLUE),
                        new DrawableSegment(120, 200, Color.BLUE)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);

        float segmentMinWidth = 16;
        boolean isStyledByProgress = true;

        Pair<List<DrawablePart>, Float> p = NotificationProgressBar.maybeStretchAndRescaleSegments(
                parts, drawableParts, segmentMinWidth, pointRadius, (float) progress / progressMax,
                200, isStyledByProgress, hasTrackerIcon ? 0 : segSegGap);

        expectedDrawableParts = new ArrayList<>(List.of(new DrawablePoint(0, 12, Color.BLUE),
                new DrawableSegment(16, 32, Color.BLUE),
                new DrawableSegment(36, 69.41936F, Color.BLUE),
                new DrawableSegment(73.41936F, 124.25807F, Color.BLUE),
                new DrawableSegment(128.25807F, 200, Color.BLUE)));

        assertThat(p.second).isEqualTo(200);
        assertThat(p.first).isEqualTo(expectedDrawableParts);
    }

    // The only difference from the `negativeWidthDrawableSegment` test above is the shorter
    // segmentMinWidth (= 10dp).
    @Test
    public void maybeStretchAndRescaleSegments_zeroWidthDrawableSegment()
            throws NotEnoughWidthToFitAllPartsException {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100).setColor(Color.BLUE));
        segments.add(new ProgressStyle.Segment(200).setColor(Color.BLUE));
        segments.add(new ProgressStyle.Segment(300).setColor(Color.BLUE));
        segments.add(new ProgressStyle.Segment(400).setColor(Color.BLUE));
        List<ProgressStyle.Point> points = new ArrayList<>();
        points.add(new ProgressStyle.Point(0).setColor(Color.BLUE));
        int progress = 1000;
        int progressMax = 1000;

        List<Part> parts = NotificationProgressBar.processModelAndConvertToViewParts(segments,
                points, progress, progressMax);

        List<Part> expectedParts = new ArrayList<>(
                List.of(new Point(Color.BLUE), new Segment(0.1f, Color.BLUE),
                        new Segment(0.2f, Color.BLUE), new Segment(0.3f, Color.BLUE),
                        new Segment(0.4f, Color.BLUE)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 200;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;

        List<DrawablePart> drawableParts =
                NotificationProgressBar.processPartsAndConvertToDrawableParts(
                        parts, drawableWidth, segSegGap, segPointGap, pointRadius, hasTrackerIcon);

        List<DrawablePart> expectedDrawableParts = new ArrayList<>(
                List.of(new DrawablePoint(0, 12, Color.BLUE),
                        new DrawableSegment(16, 16, Color.BLUE),
                        new DrawableSegment(20, 56, Color.BLUE),
                        new DrawableSegment(60, 116, Color.BLUE),
                        new DrawableSegment(120, 200, Color.BLUE)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);

        float segmentMinWidth = 10;
        boolean isStyledByProgress = true;

        Pair<List<DrawablePart>, Float> p = NotificationProgressBar.maybeStretchAndRescaleSegments(
                parts, drawableParts, segmentMinWidth, pointRadius, (float) progress / progressMax,
                200, isStyledByProgress, hasTrackerIcon ? 0 : segSegGap);

        expectedDrawableParts = new ArrayList<>(List.of(new DrawablePoint(0, 12, Color.BLUE),
                new DrawableSegment(16, 26, Color.BLUE),
                new DrawableSegment(30, 64.169014F, Color.BLUE),
                new DrawableSegment(68.169014F, 120.92958F, Color.BLUE),
                new DrawableSegment(124.92958F, 200, Color.BLUE)));

        assertThat(p.second).isEqualTo(200);
        assertThat(p.first).isEqualTo(expectedDrawableParts);
    }

    @Test
    public void maybeStretchAndRescaleSegments_noStretchingNecessary()
            throws NotEnoughWidthToFitAllPartsException {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(200).setColor(Color.BLUE));
        segments.add(new ProgressStyle.Segment(100).setColor(Color.BLUE));
        segments.add(new ProgressStyle.Segment(300).setColor(Color.BLUE));
        segments.add(new ProgressStyle.Segment(400).setColor(Color.BLUE));
        List<ProgressStyle.Point> points = new ArrayList<>();
        points.add(new ProgressStyle.Point(0).setColor(Color.BLUE));
        int progress = 1000;
        int progressMax = 1000;

        List<Part> parts = NotificationProgressBar.processModelAndConvertToViewParts(segments,
                points, progress, progressMax);

        List<Part> expectedParts = new ArrayList<>(
                List.of(new Point(Color.BLUE), new Segment(0.2f, Color.BLUE),
                        new Segment(0.1f, Color.BLUE), new Segment(0.3f, Color.BLUE),
                        new Segment(0.4f, Color.BLUE)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 200;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;

        List<DrawablePart> drawableParts =
                NotificationProgressBar.processPartsAndConvertToDrawableParts(
                        parts, drawableWidth, segSegGap, segPointGap, pointRadius, hasTrackerIcon);

        List<DrawablePart> expectedDrawableParts = new ArrayList<>(
                List.of(new DrawablePoint(0, 12, Color.BLUE),
                        new DrawableSegment(16, 36, Color.BLUE),
                        new DrawableSegment(40, 56, Color.BLUE),
                        new DrawableSegment(60, 116, Color.BLUE),
                        new DrawableSegment(120, 200, Color.BLUE)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);

        float segmentMinWidth = 10;
        boolean isStyledByProgress = true;

        Pair<List<DrawablePart>, Float> p = NotificationProgressBar.maybeStretchAndRescaleSegments(
                parts, drawableParts, segmentMinWidth, pointRadius, (float) progress / progressMax,
                200, isStyledByProgress, hasTrackerIcon ? 0 : segSegGap);

        assertThat(p.second).isEqualTo(200);
        assertThat(p.first).isEqualTo(expectedDrawableParts);
    }

    @Test(expected = NotEnoughWidthToFitAllPartsException.class)
    public void maybeStretchAndRescaleSegments_notEnoughWidthToFitAllParts()
            throws NotEnoughWidthToFitAllPartsException {
        final int orange = 0xff7f50;
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(10).setColor(orange));
        segments.add(new ProgressStyle.Segment(10).setColor(Color.YELLOW));
        segments.add(new ProgressStyle.Segment(10).setColor(Color.BLUE));
        segments.add(new ProgressStyle.Segment(10).setColor(Color.GREEN));
        segments.add(new ProgressStyle.Segment(10).setColor(Color.RED));
        segments.add(new ProgressStyle.Segment(10).setColor(orange));
        segments.add(new ProgressStyle.Segment(10).setColor(Color.YELLOW));
        segments.add(new ProgressStyle.Segment(10).setColor(Color.BLUE));
        segments.add(new ProgressStyle.Segment(10).setColor(Color.GREEN));
        segments.add(new ProgressStyle.Segment(10).setColor(Color.RED));
        List<ProgressStyle.Point> points = new ArrayList<>();
        points.add(new ProgressStyle.Point(0).setColor(orange));
        points.add(new ProgressStyle.Point(1).setColor(Color.BLUE));
        points.add(new ProgressStyle.Point(55).setColor(Color.BLUE));
        points.add(new ProgressStyle.Point(100).setColor(orange));
        int progress = 50;
        int progressMax = 100;

        List<Part> parts = NotificationProgressBar.processModelAndConvertToViewParts(segments,
                points, progress, progressMax);

        List<Part> expectedParts = new ArrayList<>(
                List.of(new Point(orange),
                        new Segment(0.01f, orange),
                        new Point(Color.BLUE),
                        new Segment(0.09f, orange),
                        new Segment(0.1f, Color.YELLOW),
                        new Segment(0.1f, Color.BLUE),
                        new Segment(0.1f, Color.GREEN),
                        new Segment(0.1f, Color.RED),
                        new Segment(0.05f, orange),
                        new Point(Color.BLUE),
                        new Segment(0.05f, orange),
                        new Segment(0.1f, Color.YELLOW),
                        new Segment(0.1f, Color.BLUE),
                        new Segment(0.1f, Color.GREEN),
                        new Segment(0.1f, Color.RED),
                        new Point(orange)));

        assertThat(parts).isEqualTo(expectedParts);

        // For the list of ProgressStyle.Part used in this test, 300 is the minimum width.
        float drawableWidth = 299;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;

        List<DrawablePart> drawableParts =
                NotificationProgressBar.processPartsAndConvertToDrawableParts(
                        parts, drawableWidth, segSegGap, segPointGap, pointRadius, hasTrackerIcon);

        // Skips the validation of the intermediate list of DrawableParts.

        float segmentMinWidth = 16;
        boolean isStyledByProgress = true;

        NotificationProgressBar.maybeStretchAndRescaleSegments(
                parts, drawableParts, segmentMinWidth, pointRadius, (float) progress / progressMax,
                300, isStyledByProgress, hasTrackerIcon ? 0 : segSegGap);
    }

    @Test
    public void processModelAndConvertToFinalDrawableParts_singleSegmentWithPoints()
            throws NotEnoughWidthToFitAllPartsException {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100).setColor(Color.BLUE));
        List<ProgressStyle.Point> points = new ArrayList<>();
        points.add(new ProgressStyle.Point(15).setColor(Color.RED));
        points.add(new ProgressStyle.Point(25).setColor(Color.BLUE));
        points.add(new ProgressStyle.Point(60).setColor(Color.BLUE));
        points.add(new ProgressStyle.Point(75).setColor(Color.YELLOW));
        int progress = 60;
        int progressMax = 100;

        float drawableWidth = 300;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;

        float segmentMinWidth = 16;
        boolean isStyledByProgress = true;

        Pair<List<DrawablePart>, Float> p =
                NotificationProgressBar.processModelAndConvertToFinalDrawableParts(
                        segments,
                        points,
                        progress,
                        progressMax,
                        drawableWidth,
                        segSegGap,
                        segPointGap,
                        pointRadius,
                        hasTrackerIcon,
                        segmentMinWidth,
                        isStyledByProgress
                );

        // Colors with 50% opacity
        int fadedBlue = 0x800000FF;
        int fadedYellow = 0x80FFFF00;
        List<DrawablePart> expectedDrawableParts = new ArrayList<>(
                List.of(new DrawableSegment(0, 34.219177F, Color.BLUE),
                        new DrawablePoint(38.219177F, 50.219177F, Color.RED),
                        new DrawableSegment(54.219177F, 70.21918F, Color.BLUE),
                        new DrawablePoint(74.21918F, 86.21918F, Color.BLUE),
                        new DrawableSegment(90.21918F, 172.38356F, Color.BLUE),
                        new DrawablePoint(176.38356F, 188.38356F, Color.BLUE),
                        new DrawableSegment(192.38356F, 217.0137F, fadedBlue, true),
                        new DrawablePoint(221.0137F, 233.0137F, fadedYellow),
                        new DrawableSegment(237.0137F, 300F, fadedBlue, true)));

        assertThat(p.second).isEqualTo(182.38356F);
        assertThat(p.first).isEqualTo(expectedDrawableParts);
    }

    @Test
    public void processModelAndConvertToFinalDrawableParts_singleSegmentWithoutPoints()
            throws NotEnoughWidthToFitAllPartsException {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100).setColor(Color.BLUE));
        int progress = 60;
        int progressMax = 100;

        float drawableWidth = 100;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;

        float segmentMinWidth = 16;
        boolean isStyledByProgress = true;

        Pair<List<DrawablePart>, Float> p =
                NotificationProgressBar.processModelAndConvertToFinalDrawableParts(
                        segments,
                        Collections.emptyList(),
                        progress,
                        progressMax,
                        drawableWidth,
                        segSegGap,
                        segPointGap,
                        pointRadius,
                        hasTrackerIcon,
                        segmentMinWidth,
                        isStyledByProgress
                );

        // Colors with 50%f opacity
        int fadedBlue = 0x800000FF;
        List<DrawablePart> expectedDrawableParts = new ArrayList<>(
                List.of(new DrawableSegment(0, 60.000004F, Color.BLUE),
                        new DrawableSegment(60.000004F, 100, fadedBlue, true)));

        assertThat(p.second).isWithin(1e-5f).of(60);
        assertThat(p.first).isEqualTo(expectedDrawableParts);
    }
}
