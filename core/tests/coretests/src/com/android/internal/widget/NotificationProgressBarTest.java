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

import static com.google.common.truth.Truth.assertThat;

import android.app.Notification.ProgressStyle;
import android.graphics.Color;
import android.util.Pair;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.widget.NotificationProgressBar.Part;
import com.android.internal.widget.NotificationProgressBar.Point;
import com.android.internal.widget.NotificationProgressBar.Segment;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class NotificationProgressBarTest {

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToParts_segmentsIsEmpty() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 50;
        int progressMax = 100;

        NotificationProgressBar.processAndConvertToViewParts(segments, points, progress,
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

        NotificationProgressBar.processAndConvertToViewParts(segments, points, progress,
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

        NotificationProgressBar.processAndConvertToViewParts(segments, points, progress,
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

        NotificationProgressBar.processAndConvertToViewParts(segments, points, progress,
                progressMax);
    }

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToParts_progressIsNegative() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = -50;
        int progressMax = 100;

        NotificationProgressBar.processAndConvertToViewParts(segments, points, progress,
                progressMax);
    }

    @Test
    public void processAndConvertToParts_progressIsZero() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100).setColor(Color.RED));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 0;
        int progressMax = 100;

        List<Part> parts = NotificationProgressBar.processAndConvertToViewParts(segments, points,
                progress, progressMax);

        List<Part> expectedParts = new ArrayList<>(List.of(new Segment(1f, Color.RED)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 300;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;

        List<NotificationProgressDrawable.Part> drawableParts =
                NotificationProgressBar.processAndConvertToDrawableParts(parts, drawableWidth,
                        segSegGap, segPointGap, pointRadius, hasTrackerIcon
                );

        List<NotificationProgressDrawable.Part> expectedDrawableParts = new ArrayList<>(
                List.of(new NotificationProgressDrawable.Segment(0, 300, Color.RED)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);

        float segmentMinWidth = 16;
        boolean isStyledByProgress = true;

        Pair<List<NotificationProgressDrawable.Part>, Float> p =
                NotificationProgressBar.maybeStretchAndRescaleSegments(parts, drawableParts,
                        segmentMinWidth, pointRadius, (float) progress / progressMax, 300,
                        isStyledByProgress, hasTrackerIcon ? 0 : segSegGap);

        // Colors with 40% opacity
        int fadedRed = 0x66FF0000;
        expectedDrawableParts = new ArrayList<>(
                List.of(new NotificationProgressDrawable.Segment(0, 300, fadedRed, true)));

        assertThat(p.second).isEqualTo(0);
        assertThat(p.first).isEqualTo(expectedDrawableParts);
    }

    @Test
    public void processAndConvertToParts_progressAtMax() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100).setColor(Color.RED));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 100;
        int progressMax = 100;

        List<Part> parts = NotificationProgressBar.processAndConvertToViewParts(segments, points,
                progress, progressMax);

        List<Part> expectedParts = new ArrayList<>(List.of(new Segment(1f, Color.RED)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 300;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;

        List<NotificationProgressDrawable.Part> drawableParts =
                NotificationProgressBar.processAndConvertToDrawableParts(parts, drawableWidth,
                        segSegGap, segPointGap, pointRadius, hasTrackerIcon
                );

        List<NotificationProgressDrawable.Part> expectedDrawableParts = new ArrayList<>(
                List.of(new NotificationProgressDrawable.Segment(0, 300, Color.RED)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);

        float segmentMinWidth = 16;
        boolean isStyledByProgress = true;

        Pair<List<NotificationProgressDrawable.Part>, Float> p =
                NotificationProgressBar.maybeStretchAndRescaleSegments(parts, drawableParts,
                        segmentMinWidth, pointRadius, (float) progress / progressMax, 300,
                        isStyledByProgress, hasTrackerIcon ? 0 : segSegGap);

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

        NotificationProgressBar.processAndConvertToViewParts(segments, points, progress,
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

        NotificationProgressBar.processAndConvertToViewParts(segments, points, progress,
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

        NotificationProgressBar.processAndConvertToViewParts(segments, points, progress,
                progressMax);
    }

    @Test
    public void processAndConvertToParts_multipleSegmentsWithoutPoints() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(50).setColor(Color.RED));
        segments.add(new ProgressStyle.Segment(50).setColor(Color.GREEN));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 60;
        int progressMax = 100;

        List<Part> parts = NotificationProgressBar.processAndConvertToViewParts(
                segments, points, progress, progressMax);

        List<Part> expectedParts = new ArrayList<>(List.of(
                new Segment(0.50f, Color.RED),
                new Segment(0.50f, Color.GREEN)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 300;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;

        List<NotificationProgressDrawable.Part> drawableParts =
                NotificationProgressBar.processAndConvertToDrawableParts(parts, drawableWidth,
                        segSegGap, segPointGap, pointRadius, hasTrackerIcon
                );

        List<NotificationProgressDrawable.Part> expectedDrawableParts = new ArrayList<>(
                List.of(new NotificationProgressDrawable.Segment(0, 146, Color.RED),
                        new NotificationProgressDrawable.Segment(150, 300, Color.GREEN)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);

        float segmentMinWidth = 16;
        boolean isStyledByProgress = true;
        Pair<List<NotificationProgressDrawable.Part>, Float> p =
                NotificationProgressBar.maybeStretchAndRescaleSegments(parts, drawableParts,
                        segmentMinWidth, pointRadius, (float) progress / progressMax, 300,
                        isStyledByProgress, hasTrackerIcon ? 0 : segSegGap);

        // Colors with 40% opacity
        int fadedGreen = 0x6600FF00;
        expectedDrawableParts = new ArrayList<>(
                List.of(new NotificationProgressDrawable.Segment(0, 146, Color.RED),
                        new NotificationProgressDrawable.Segment(150, 180, Color.GREEN),
                        new NotificationProgressDrawable.Segment(180, 300, fadedGreen, true)));

        assertThat(p.second).isEqualTo(180);
        assertThat(p.first).isEqualTo(expectedDrawableParts);
    }

    @Test
    public void processAndConvertToParts_multipleSegmentsWithoutPoints_noTracker() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(50).setColor(Color.RED));
        segments.add(new ProgressStyle.Segment(50).setColor(Color.GREEN));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 60;
        int progressMax = 100;
        List<Part> parts = NotificationProgressBar.processAndConvertToViewParts(segments, points,
                progress, progressMax);

        List<Part> expectedParts = new ArrayList<>(List.of(
                new Segment(0.50f, Color.RED),
                new Segment(0.50f, Color.GREEN)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 300;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = false;

        List<NotificationProgressDrawable.Part> drawableParts =
                NotificationProgressBar.processAndConvertToDrawableParts(parts, drawableWidth,
                        segSegGap, segPointGap, pointRadius, hasTrackerIcon
                );

        List<NotificationProgressDrawable.Part> expectedDrawableParts = new ArrayList<>(
                List.of(new NotificationProgressDrawable.Segment(0, 146, Color.RED),
                        new NotificationProgressDrawable.Segment(150, 300, Color.GREEN)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);

        float segmentMinWidth = 16;
        boolean isStyledByProgress = true;
        Pair<List<NotificationProgressDrawable.Part>, Float> p =
                NotificationProgressBar.maybeStretchAndRescaleSegments(parts, drawableParts,
                        segmentMinWidth, pointRadius, (float) progress / progressMax, 300,
                        isStyledByProgress, hasTrackerIcon ? 0 : segSegGap);

        // Colors with 40% opacity
        int fadedGreen = 0x6600FF00;
        expectedDrawableParts = new ArrayList<>(
                List.of(new NotificationProgressDrawable.Segment(0, 146, Color.RED),
                        new NotificationProgressDrawable.Segment(150, 176, Color.GREEN),
                        new NotificationProgressDrawable.Segment(180, 300, fadedGreen, true)));

        assertThat(p.second).isEqualTo(180);
        assertThat(p.first).isEqualTo(expectedDrawableParts);
    }

    @Test
    public void processAndConvertToParts_singleSegmentWithPoints() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100).setColor(Color.BLUE));
        List<ProgressStyle.Point> points = new ArrayList<>();
        points.add(new ProgressStyle.Point(15).setColor(Color.RED));
        points.add(new ProgressStyle.Point(25).setColor(Color.BLUE));
        points.add(new ProgressStyle.Point(60).setColor(Color.BLUE));
        points.add(new ProgressStyle.Point(75).setColor(Color.YELLOW));
        int progress = 60;
        int progressMax = 100;

        List<Part> parts = NotificationProgressBar.processAndConvertToViewParts(segments, points,
                progress, progressMax);

        List<Part> expectedParts = new ArrayList<>(List.of(
                new Segment(0.15f, Color.BLUE),
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

        List<NotificationProgressDrawable.Part> drawableParts =
                NotificationProgressBar.processAndConvertToDrawableParts(parts, drawableWidth,
                        segSegGap, segPointGap, pointRadius, hasTrackerIcon
                );

        List<NotificationProgressDrawable.Part> expectedDrawableParts = new ArrayList<>(
                List.of(new NotificationProgressDrawable.Segment(0, 35, Color.BLUE),
                        new NotificationProgressDrawable.Point(39, 51, Color.RED),
                        new NotificationProgressDrawable.Segment(55, 65, Color.BLUE),
                        new NotificationProgressDrawable.Point(69, 81, Color.BLUE),
                        new NotificationProgressDrawable.Segment(85, 170, Color.BLUE),
                        new NotificationProgressDrawable.Point(174, 186, Color.BLUE),
                        new NotificationProgressDrawable.Segment(190, 215, Color.BLUE),
                        new NotificationProgressDrawable.Point(219, 231, Color.YELLOW),
                        new NotificationProgressDrawable.Segment(235, 300, Color.BLUE)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);

        float segmentMinWidth = 16;
        boolean isStyledByProgress = true;

        Pair<List<NotificationProgressDrawable.Part>, Float> p =
                NotificationProgressBar.maybeStretchAndRescaleSegments(parts, drawableParts,
                        segmentMinWidth, pointRadius, (float) progress / progressMax, 300,
                        isStyledByProgress, hasTrackerIcon ? 0 : segSegGap);

        // Colors with 40% opacity
        int fadedBlue = 0x660000FF;
        int fadedYellow = 0x66FFFF00;
        expectedDrawableParts = new ArrayList<>(
                List.of(new NotificationProgressDrawable.Segment(0, 34.219177F, Color.BLUE),
                        new NotificationProgressDrawable.Point(38.219177F, 50.219177F, Color.RED),
                        new NotificationProgressDrawable.Segment(54.219177F, 70.21918F, Color.BLUE),
                        new NotificationProgressDrawable.Point(74.21918F, 86.21918F, Color.BLUE),
                        new NotificationProgressDrawable.Segment(90.21918F, 172.38356F, Color.BLUE),
                        new NotificationProgressDrawable.Point(176.38356F, 188.38356F, Color.BLUE),
                        new NotificationProgressDrawable.Segment(192.38356F, 217.0137F, fadedBlue,
                                true),
                        new NotificationProgressDrawable.Point(221.0137F, 233.0137F, fadedYellow),
                        new NotificationProgressDrawable.Segment(237.0137F, 300F, fadedBlue,
                                true)));

        assertThat(p.second).isEqualTo(182.38356F);
        assertThat(p.first).isEqualTo(expectedDrawableParts);
    }

    @Test
    public void processAndConvertToParts_multipleSegmentsWithPoints() {
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

        List<Part> parts = NotificationProgressBar.processAndConvertToViewParts(segments, points,
                progress, progressMax);

        List<Part> expectedParts = new ArrayList<>(List.of(
                new Segment(0.15f, Color.RED),
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
        List<NotificationProgressDrawable.Part> drawableParts =
                NotificationProgressBar.processAndConvertToDrawableParts(parts, drawableWidth,
                        segSegGap, segPointGap, pointRadius, hasTrackerIcon
                );

        List<NotificationProgressDrawable.Part> expectedDrawableParts = new ArrayList<>(
                List.of(new NotificationProgressDrawable.Segment(0, 35, Color.RED),
                        new NotificationProgressDrawable.Point(39, 51, Color.RED),
                        new NotificationProgressDrawable.Segment(55, 65, Color.RED),
                        new NotificationProgressDrawable.Point(69, 81, Color.BLUE),
                        new NotificationProgressDrawable.Segment(85, 146, Color.RED),
                        new NotificationProgressDrawable.Segment(150, 170, Color.GREEN),
                        new NotificationProgressDrawable.Point(174, 186, Color.BLUE),
                        new NotificationProgressDrawable.Segment(190, 215, Color.GREEN),
                        new NotificationProgressDrawable.Point(219, 231, Color.YELLOW),
                        new NotificationProgressDrawable.Segment(235, 300, Color.GREEN)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);

        float segmentMinWidth = 16;
        boolean isStyledByProgress = true;

        Pair<List<NotificationProgressDrawable.Part>, Float> p =
                NotificationProgressBar.maybeStretchAndRescaleSegments(parts, drawableParts,
                        segmentMinWidth, pointRadius, (float) progress / progressMax, 300,
                        isStyledByProgress, hasTrackerIcon ? 0 : segSegGap);

        // Colors with 40% opacity
        int fadedGreen = 0x6600FF00;
        int fadedYellow = 0x66FFFF00;
        expectedDrawableParts = new ArrayList<>(
                List.of(new NotificationProgressDrawable.Segment(0, 34.095238F, Color.RED),
                        new NotificationProgressDrawable.Point(38.095238F, 50.095238F, Color.RED),
                        new NotificationProgressDrawable.Segment(54.095238F, 70.09524F, Color.RED),
                        new NotificationProgressDrawable.Point(74.09524F, 86.09524F, Color.BLUE),
                        new NotificationProgressDrawable.Segment(90.09524F, 148.9524F, Color.RED),
                        new NotificationProgressDrawable.Segment(152.95238F, 172.7619F,
                                Color.GREEN),
                        new NotificationProgressDrawable.Point(176.7619F, 188.7619F, Color.BLUE),
                        new NotificationProgressDrawable.Segment(192.7619F, 217.33333F,
                                fadedGreen, true),
                        new NotificationProgressDrawable.Point(221.33333F, 233.33333F,
                                fadedYellow),
                        new NotificationProgressDrawable.Segment(237.33333F, 299.99997F,
                                fadedGreen, true)));

        assertThat(p.second).isEqualTo(182.7619F);
        assertThat(p.first).isEqualTo(expectedDrawableParts);
    }

    @Test
    public void processAndConvertToParts_multipleSegmentsWithPoints_notStyledByProgress() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(50).setColor(Color.RED));
        segments.add(new ProgressStyle.Segment(50).setColor(Color.GREEN));
        List<ProgressStyle.Point> points = new ArrayList<>();
        points.add(new ProgressStyle.Point(15).setColor(Color.RED));
        points.add(new ProgressStyle.Point(25).setColor(Color.BLUE));
        points.add(new ProgressStyle.Point(75).setColor(Color.YELLOW));
        int progress = 60;
        int progressMax = 100;

        List<Part> parts = NotificationProgressBar.processAndConvertToViewParts(segments, points,
                progress, progressMax);

        List<Part> expectedParts = new ArrayList<>(List.of(
                new Segment(0.15f, Color.RED),
                new Point(Color.RED),
                new Segment(0.10f, Color.RED),
                new Point(Color.BLUE),
                new Segment(0.25f, Color.RED),
                new Segment(0.25f, Color.GREEN),
                new Point(Color.YELLOW),
                new Segment(0.25f, Color.GREEN)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 300;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;

        List<NotificationProgressDrawable.Part> drawableParts =
                NotificationProgressBar.processAndConvertToDrawableParts(parts, drawableWidth,
                        segSegGap, segPointGap, pointRadius, hasTrackerIcon
                );

        List<NotificationProgressDrawable.Part> expectedDrawableParts = new ArrayList<>(
                List.of(new NotificationProgressDrawable.Segment(0, 35, Color.RED),
                        new NotificationProgressDrawable.Point(39, 51, Color.RED),
                        new NotificationProgressDrawable.Segment(55, 65, Color.RED),
                        new NotificationProgressDrawable.Point(69, 81, Color.BLUE),
                        new NotificationProgressDrawable.Segment(85, 146, Color.RED),
                        new NotificationProgressDrawable.Segment(150, 215, Color.GREEN),
                        new NotificationProgressDrawable.Point(219, 231, Color.YELLOW),
                        new NotificationProgressDrawable.Segment(235, 300, Color.GREEN)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);

        float segmentMinWidth = 16;
        boolean isStyledByProgress = false;

        Pair<List<NotificationProgressDrawable.Part>, Float> p =
                NotificationProgressBar.maybeStretchAndRescaleSegments(parts, drawableParts,
                        segmentMinWidth, pointRadius, (float) progress / progressMax, 300,
                        isStyledByProgress, hasTrackerIcon ? 0 : segSegGap);

        expectedDrawableParts = new ArrayList<>(
                List.of(new NotificationProgressDrawable.Segment(0, 34.296295F, Color.RED),
                        new NotificationProgressDrawable.Point(38.296295F, 50.296295F, Color.RED),
                        new NotificationProgressDrawable.Segment(54.296295F, 70.296295F, Color.RED),
                        new NotificationProgressDrawable.Point(74.296295F, 86.296295F, Color.BLUE),
                        new NotificationProgressDrawable.Segment(90.296295F, 149.62962F, Color.RED),
                        new NotificationProgressDrawable.Segment(153.62962F, 216.8148F,
                                Color.GREEN),
                        new NotificationProgressDrawable.Point(220.81482F, 232.81482F,
                                Color.YELLOW),
                        new NotificationProgressDrawable.Segment(236.81482F, 300, Color.GREEN)));

        assertThat(p.second).isEqualTo(182.9037F);
        assertThat(p.first).isEqualTo(expectedDrawableParts);
    }

    // The only difference from the `zeroWidthDrawableSegment` test below is the longer
    // segmentMinWidth (= 16dp).
    @Test
    public void maybeStretchAndRescaleSegments_negativeWidthDrawableSegment() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100).setColor(Color.BLUE));
        segments.add(new ProgressStyle.Segment(200).setColor(Color.BLUE));
        segments.add(new ProgressStyle.Segment(300).setColor(Color.BLUE));
        segments.add(new ProgressStyle.Segment(400).setColor(Color.BLUE));
        List<ProgressStyle.Point> points = new ArrayList<>();
        points.add(new ProgressStyle.Point(0).setColor(Color.BLUE));
        int progress = 1000;
        int progressMax = 1000;

        List<Part> parts = NotificationProgressBar.processAndConvertToViewParts(
                segments, points, progress, progressMax);

        List<Part> expectedParts = new ArrayList<>(List.of(
                new Point(Color.BLUE),
                new Segment(0.1f, Color.BLUE),
                new Segment(0.2f, Color.BLUE),
                new Segment(0.3f, Color.BLUE),
                new Segment(0.4f, Color.BLUE)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 200;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;
        List<NotificationProgressDrawable.Part> drawableParts =
                NotificationProgressBar.processAndConvertToDrawableParts(parts, drawableWidth,
                        segSegGap, segPointGap, pointRadius, hasTrackerIcon
                );

        List<NotificationProgressDrawable.Part> expectedDrawableParts = new ArrayList<>(
                List.of(new NotificationProgressDrawable.Point(0, 12, Color.BLUE),
                        new NotificationProgressDrawable.Segment(16, 16, Color.BLUE),
                        new NotificationProgressDrawable.Segment(20, 56, Color.BLUE),
                        new NotificationProgressDrawable.Segment(60, 116, Color.BLUE),
                        new NotificationProgressDrawable.Segment(120, 200, Color.BLUE)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);

        float segmentMinWidth = 16;
        boolean isStyledByProgress = true;

        Pair<List<NotificationProgressDrawable.Part>, Float> p =
                NotificationProgressBar.maybeStretchAndRescaleSegments(parts, drawableParts,
                        segmentMinWidth, pointRadius, (float) progress / progressMax, 200,
                        isStyledByProgress, hasTrackerIcon ? 0 : segSegGap);

        expectedDrawableParts = new ArrayList<>(
                List.of(new NotificationProgressDrawable.Point(0, 12, Color.BLUE),
                        new NotificationProgressDrawable.Segment(16, 32, Color.BLUE),
                        new NotificationProgressDrawable.Segment(36, 69.41936F, Color.BLUE),
                        new NotificationProgressDrawable.Segment(73.41936F, 124.25807F, Color.BLUE),
                        new NotificationProgressDrawable.Segment(128.25807F, 200, Color.BLUE)));

        assertThat(p.second).isEqualTo(200);
        assertThat(p.first).isEqualTo(expectedDrawableParts);
    }

    // The only difference from the `negativeWidthDrawableSegment` test above is the shorter
    // segmentMinWidth (= 10dp).
    @Test
    public void maybeStretchAndRescaleSegments_zeroWidthDrawableSegment() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100).setColor(Color.BLUE));
        segments.add(new ProgressStyle.Segment(200).setColor(Color.BLUE));
        segments.add(new ProgressStyle.Segment(300).setColor(Color.BLUE));
        segments.add(new ProgressStyle.Segment(400).setColor(Color.BLUE));
        List<ProgressStyle.Point> points = new ArrayList<>();
        points.add(new ProgressStyle.Point(0).setColor(Color.BLUE));
        int progress = 1000;
        int progressMax = 1000;

        List<Part> parts = NotificationProgressBar.processAndConvertToViewParts(
                segments, points, progress, progressMax);

        List<Part> expectedParts = new ArrayList<>(List.of(
                new Point(Color.BLUE),
                new Segment(0.1f, Color.BLUE),
                new Segment(0.2f, Color.BLUE),
                new Segment(0.3f, Color.BLUE),
                new Segment(0.4f, Color.BLUE)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 200;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;
        List<NotificationProgressDrawable.Part> drawableParts =
                NotificationProgressBar.processAndConvertToDrawableParts(parts, drawableWidth,
                        segSegGap, segPointGap, pointRadius, hasTrackerIcon
                );

        List<NotificationProgressDrawable.Part> expectedDrawableParts = new ArrayList<>(
                List.of(new NotificationProgressDrawable.Point(0, 12, Color.BLUE),
                        new NotificationProgressDrawable.Segment(16, 16, Color.BLUE),
                        new NotificationProgressDrawable.Segment(20, 56, Color.BLUE),
                        new NotificationProgressDrawable.Segment(60, 116, Color.BLUE),
                        new NotificationProgressDrawable.Segment(120, 200, Color.BLUE)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);

        float segmentMinWidth = 10;
        boolean isStyledByProgress = true;

        Pair<List<NotificationProgressDrawable.Part>, Float> p =
                NotificationProgressBar.maybeStretchAndRescaleSegments(parts, drawableParts,
                        segmentMinWidth, pointRadius, (float) progress / progressMax, 200,
                        isStyledByProgress, hasTrackerIcon ? 0 : segSegGap);

        expectedDrawableParts = new ArrayList<>(
                List.of(new NotificationProgressDrawable.Point(0, 12, Color.BLUE),
                        new NotificationProgressDrawable.Segment(16, 26, Color.BLUE),
                        new NotificationProgressDrawable.Segment(30, 64.169014F, Color.BLUE),
                        new NotificationProgressDrawable.Segment(68.169014F, 120.92958F,
                                Color.BLUE),
                        new NotificationProgressDrawable.Segment(124.92958F, 200, Color.BLUE)));

        assertThat(p.second).isEqualTo(200);
        assertThat(p.first).isEqualTo(expectedDrawableParts);
    }

    @Test
    public void maybeStretchAndRescaleSegments_noStretchingNecessary() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(200).setColor(Color.BLUE));
        segments.add(new ProgressStyle.Segment(100).setColor(Color.BLUE));
        segments.add(new ProgressStyle.Segment(300).setColor(Color.BLUE));
        segments.add(new ProgressStyle.Segment(400).setColor(Color.BLUE));
        List<ProgressStyle.Point> points = new ArrayList<>();
        points.add(new ProgressStyle.Point(0).setColor(Color.BLUE));
        int progress = 1000;
        int progressMax = 1000;

        List<Part> parts = NotificationProgressBar.processAndConvertToViewParts(
                segments, points, progress, progressMax);

        List<Part> expectedParts = new ArrayList<>(List.of(
                new Point(Color.BLUE),
                new Segment(0.2f, Color.BLUE),
                new Segment(0.1f, Color.BLUE),
                new Segment(0.3f, Color.BLUE),
                new Segment(0.4f, Color.BLUE)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 200;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;

        List<NotificationProgressDrawable.Part> drawableParts =
                NotificationProgressBar.processAndConvertToDrawableParts(parts, drawableWidth,
                        segSegGap, segPointGap, pointRadius, hasTrackerIcon
                );

        List<NotificationProgressDrawable.Part> expectedDrawableParts = new ArrayList<>(
                List.of(new NotificationProgressDrawable.Point(0, 12, Color.BLUE),
                        new NotificationProgressDrawable.Segment(16, 36, Color.BLUE),
                        new NotificationProgressDrawable.Segment(40, 56, Color.BLUE),
                        new NotificationProgressDrawable.Segment(60, 116, Color.BLUE),
                        new NotificationProgressDrawable.Segment(120, 200, Color.BLUE)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);

        float segmentMinWidth = 10;
        boolean isStyledByProgress = true;

        Pair<List<NotificationProgressDrawable.Part>, Float> p =
                NotificationProgressBar.maybeStretchAndRescaleSegments(parts, drawableParts,
                        segmentMinWidth, pointRadius, (float) progress / progressMax, 200,
                        isStyledByProgress, hasTrackerIcon ? 0 : segSegGap);

        assertThat(p.second).isEqualTo(200);
        assertThat(p.first).isEqualTo(expectedDrawableParts);
    }
}
