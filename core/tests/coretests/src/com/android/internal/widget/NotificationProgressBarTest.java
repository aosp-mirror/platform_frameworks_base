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
        boolean isStyledByProgress = true;

        NotificationProgressBar.processAndConvertToViewParts(segments, points, progress,
                progressMax,
                isStyledByProgress);
    }

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToParts_segmentsLengthNotMatchingProgressMax() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(50));
        segments.add(new ProgressStyle.Segment(100));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 50;
        int progressMax = 100;
        boolean isStyledByProgress = true;

        NotificationProgressBar.processAndConvertToViewParts(segments, points, progress,
                progressMax,
                isStyledByProgress);
    }

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToParts_segmentLengthIsNegative() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(-50));
        segments.add(new ProgressStyle.Segment(150));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 50;
        int progressMax = 100;
        boolean isStyledByProgress = true;

        NotificationProgressBar.processAndConvertToViewParts(segments, points, progress,
                progressMax,
                isStyledByProgress);
    }

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToParts_segmentLengthIsZero() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(0));
        segments.add(new ProgressStyle.Segment(100));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 50;
        int progressMax = 100;
        boolean isStyledByProgress = true;

        NotificationProgressBar.processAndConvertToViewParts(segments, points, progress,
                progressMax,
                isStyledByProgress);
    }

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToParts_progressIsNegative() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = -50;
        int progressMax = 100;
        boolean isStyledByProgress = true;

        NotificationProgressBar.processAndConvertToViewParts(segments, points, progress,
                progressMax,
                isStyledByProgress);
    }

    @Test
    public void processAndConvertToParts_progressIsZero() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100).setColor(Color.RED));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 0;
        int progressMax = 100;
        boolean isStyledByProgress = true;

        List<Part> parts = NotificationProgressBar.processAndConvertToViewParts(
                segments, points, progress, progressMax, isStyledByProgress);

        // Colors with 40% opacity
        int fadedRed = 0x66FF0000;

        List<Part> expectedParts = new ArrayList<>(List.of(new Segment(1f, fadedRed, true)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 300;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;
        List<NotificationProgressDrawable.Part> drawableParts =
                NotificationProgressBar.processAndConvertToDrawableParts(parts, drawableWidth,
                        segSegGap,
                        segPointGap, pointRadius, hasTrackerIcon);

        List<NotificationProgressDrawable.Part> expectedDrawableParts = new ArrayList<>(
                List.of(new NotificationProgressDrawable.Segment(0, 300, fadedRed, true)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);
    }

    @Test
    public void processAndConvertToParts_progressAtMax() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100).setColor(Color.RED));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 100;
        int progressMax = 100;
        boolean isStyledByProgress = true;

        List<Part> parts = NotificationProgressBar.processAndConvertToViewParts(
                segments, points, progress, progressMax, isStyledByProgress);

        List<Part> expectedParts = new ArrayList<>(List.of(new Segment(1f, Color.RED)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 300;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;
        List<NotificationProgressDrawable.Part> drawableParts =
                NotificationProgressBar.processAndConvertToDrawableParts(parts, drawableWidth,
                        segSegGap,
                        segPointGap, pointRadius, hasTrackerIcon);

        List<NotificationProgressDrawable.Part> expectedDrawableParts = new ArrayList<>(
                List.of(new NotificationProgressDrawable.Segment(0, 300, Color.RED)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);
    }

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToParts_progressAboveMax() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 150;
        int progressMax = 100;
        boolean isStyledByProgress = true;

        NotificationProgressBar.processAndConvertToViewParts(segments, points, progress,
                progressMax, isStyledByProgress);
    }

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToParts_pointPositionIsNegative() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100));
        List<ProgressStyle.Point> points = new ArrayList<>();
        points.add(new ProgressStyle.Point(-50).setColor(Color.RED));
        int progress = 50;
        int progressMax = 100;
        boolean isStyledByProgress = true;

        NotificationProgressBar.processAndConvertToViewParts(segments, points, progress,
                progressMax,
                isStyledByProgress);
    }

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToParts_pointPositionAboveMax() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100));
        List<ProgressStyle.Point> points = new ArrayList<>();
        points.add(new ProgressStyle.Point(150).setColor(Color.RED));
        int progress = 50;
        int progressMax = 100;
        boolean isStyledByProgress = true;

        NotificationProgressBar.processAndConvertToViewParts(segments, points, progress,
                progressMax,
                isStyledByProgress);
    }

    @Test
    public void processAndConvertToParts_multipleSegmentsWithoutPoints() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(50).setColor(Color.RED));
        segments.add(new ProgressStyle.Segment(50).setColor(Color.GREEN));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 60;
        int progressMax = 100;
        boolean isStyledByProgress = true;

        List<Part> parts = NotificationProgressBar.processAndConvertToViewParts(
                segments, points, progress, progressMax, isStyledByProgress);

        // Colors with 40% opacity
        int fadedGreen = 0x6600FF00;

        List<Part> expectedParts = new ArrayList<>(List.of(
                new Segment(0.50f, Color.RED),
                new Segment(0.10f, Color.GREEN),
                new Segment(0.40f, fadedGreen, true)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 300;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;
        List<NotificationProgressDrawable.Part> drawableParts =
                NotificationProgressBar.processAndConvertToDrawableParts(parts, drawableWidth,
                        segSegGap,
                        segPointGap, pointRadius, hasTrackerIcon);

        List<NotificationProgressDrawable.Part> expectedDrawableParts = new ArrayList<>(
                List.of(new NotificationProgressDrawable.Segment(0, 146, Color.RED),
                        new NotificationProgressDrawable.Segment(150, 180, Color.GREEN),
                        new NotificationProgressDrawable.Segment(180, 300, fadedGreen, true)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);
    }

    @Test
    public void processAndConvertToParts_multipleSegmentsWithoutPoints_noTracker() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(50).setColor(Color.RED));
        segments.add(new ProgressStyle.Segment(50).setColor(Color.GREEN));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 60;
        int progressMax = 100;
        boolean isStyledByProgress = true;

        List<Part> parts = NotificationProgressBar.processAndConvertToViewParts(
                segments, points, progress, progressMax, isStyledByProgress);

        // Colors with 40% opacity
        int fadedGreen = 0x6600FF00;

        List<Part> expectedParts = new ArrayList<>(List.of(
                new Segment(0.50f, Color.RED),
                new Segment(0.10f, Color.GREEN),
                new Segment(0.40f, fadedGreen, true)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 300;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = false;
        List<NotificationProgressDrawable.Part> drawableParts =
                NotificationProgressBar.processAndConvertToDrawableParts(parts, drawableWidth,
                        segSegGap,
                        segPointGap, pointRadius, hasTrackerIcon);

        List<NotificationProgressDrawable.Part> expectedDrawableParts = new ArrayList<>(
                List.of(new NotificationProgressDrawable.Segment(0, 146, Color.RED),
                        new NotificationProgressDrawable.Segment(150, 176, Color.GREEN),
                        new NotificationProgressDrawable.Segment(180, 300, fadedGreen, true)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);
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
        boolean isStyledByProgress = true;

        // Colors with 40% opacity
        int fadedBlue = 0x660000FF;
        int fadedYellow = 0x66FFFF00;

        List<Part> expectedParts = new ArrayList<>(List.of(
                new Segment(0.15f, Color.BLUE),
                new Point(Color.RED),
                new Segment(0.10f, Color.BLUE),
                new Point(Color.BLUE),
                new Segment(0.35f, Color.BLUE),
                new Point(Color.BLUE),
                new Segment(0.15f, fadedBlue, true),
                new Point(fadedYellow),
                new Segment(0.25f, fadedBlue, true)));

        List<Part> parts = NotificationProgressBar.processAndConvertToViewParts(
                segments, points, progress, progressMax, isStyledByProgress);

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 300;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;
        List<NotificationProgressDrawable.Part> drawableParts =
                NotificationProgressBar.processAndConvertToDrawableParts(parts, drawableWidth,
                        segSegGap,
                        segPointGap, pointRadius, hasTrackerIcon);

        List<NotificationProgressDrawable.Part> expectedDrawableParts = new ArrayList<>(
                List.of(new NotificationProgressDrawable.Segment(0, 35, Color.BLUE),
                        new NotificationProgressDrawable.Point(39, 51, Color.RED),
                        new NotificationProgressDrawable.Segment(55, 65, Color.BLUE),
                        new NotificationProgressDrawable.Point(69, 81, Color.BLUE),
                        new NotificationProgressDrawable.Segment(85, 170, Color.BLUE),
                        new NotificationProgressDrawable.Point(174, 186, Color.BLUE),
                        new NotificationProgressDrawable.Segment(190, 215, fadedBlue, true),
                        new NotificationProgressDrawable.Point(219, 231, fadedYellow),
                        new NotificationProgressDrawable.Segment(235, 300, fadedBlue, true)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);
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
        boolean isStyledByProgress = true;

        List<Part> parts = NotificationProgressBar.processAndConvertToViewParts(
                segments, points, progress, progressMax, isStyledByProgress);

        // Colors with 40% opacity
        int fadedGreen = 0x6600FF00;
        int fadedYellow = 0x66FFFF00;

        List<Part> expectedParts = new ArrayList<>(List.of(
                new Segment(0.15f, Color.RED),
                new Point(Color.RED),
                new Segment(0.10f, Color.RED),
                new Point(Color.BLUE),
                new Segment(0.25f, Color.RED),
                new Segment(0.10f, Color.GREEN),
                new Point(Color.BLUE),
                new Segment(0.15f, fadedGreen, true),
                new Point(fadedYellow),
                new Segment(0.25f, fadedGreen, true)));

        assertThat(parts).isEqualTo(expectedParts);

        float drawableWidth = 300;
        float segSegGap = 4;
        float segPointGap = 4;
        float pointRadius = 6;
        boolean hasTrackerIcon = true;
        List<NotificationProgressDrawable.Part> drawableParts =
                NotificationProgressBar.processAndConvertToDrawableParts(parts, drawableWidth,
                        segSegGap,
                        segPointGap, pointRadius, hasTrackerIcon);

        List<NotificationProgressDrawable.Part> expectedDrawableParts = new ArrayList<>(
                List.of(new NotificationProgressDrawable.Segment(0, 35, Color.RED),
                        new NotificationProgressDrawable.Point(39, 51, Color.RED),
                        new NotificationProgressDrawable.Segment(55, 65, Color.RED),
                        new NotificationProgressDrawable.Point(69, 81, Color.BLUE),
                        new NotificationProgressDrawable.Segment(85, 146, Color.RED),
                        new NotificationProgressDrawable.Segment(150, 170, Color.GREEN),
                        new NotificationProgressDrawable.Point(174, 186, Color.BLUE),
                        new NotificationProgressDrawable.Segment(190, 215, fadedGreen, true),
                        new NotificationProgressDrawable.Point(219, 231, fadedYellow),
                        new NotificationProgressDrawable.Segment(235, 300, fadedGreen, true)));

        assertThat(drawableParts).isEqualTo(expectedDrawableParts);
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
        boolean isStyledByProgress = false;

        List<Part> parts = NotificationProgressBar.processAndConvertToViewParts(
                segments, points, progress, progressMax, isStyledByProgress);

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
                        segSegGap,
                        segPointGap, pointRadius, hasTrackerIcon);

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
    }
}
