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

import com.android.internal.widget.NotificationProgressDrawable.Part;
import com.android.internal.widget.NotificationProgressDrawable.Point;
import com.android.internal.widget.NotificationProgressDrawable.Segment;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class NotificationProgressBarTest {

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToDrawableParts_segmentsIsEmpty() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 50;
        int progressMax = 100;
        boolean isStyledByProgress = true;

        NotificationProgressBar.processAndConvertToDrawableParts(segments, points, progress,
                progressMax,
                isStyledByProgress);
    }

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToDrawableParts_segmentsLengthNotMatchingProgressMax() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(50));
        segments.add(new ProgressStyle.Segment(100));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 50;
        int progressMax = 100;
        boolean isStyledByProgress = true;

        NotificationProgressBar.processAndConvertToDrawableParts(segments, points, progress,
                progressMax,
                isStyledByProgress);
    }

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToDrawableParts_segmentLengthIsNegative() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(-50));
        segments.add(new ProgressStyle.Segment(150));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 50;
        int progressMax = 100;
        boolean isStyledByProgress = true;

        NotificationProgressBar.processAndConvertToDrawableParts(segments, points, progress,
                progressMax,
                isStyledByProgress);
    }

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToDrawableParts_segmentLengthIsZero() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(0));
        segments.add(new ProgressStyle.Segment(100));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 50;
        int progressMax = 100;
        boolean isStyledByProgress = true;

        NotificationProgressBar.processAndConvertToDrawableParts(segments, points, progress,
                progressMax,
                isStyledByProgress);
    }

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToDrawableParts_progressIsNegative() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = -50;
        int progressMax = 100;
        boolean isStyledByProgress = true;

        NotificationProgressBar.processAndConvertToDrawableParts(segments, points, progress,
                progressMax,
                isStyledByProgress);
    }

    @Test
    public void processAndConvertToDrawableParts_progressIsZero() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100).setColor(Color.RED));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 0;
        int progressMax = 100;
        boolean isStyledByProgress = true;

        List<Part> parts = NotificationProgressBar.processAndConvertToDrawableParts(
                segments, points, progress, progressMax, isStyledByProgress);

        int fadedRed = 0x7FFF0000;
        List<Part> expected = new ArrayList<>(List.of(new Segment(1f, fadedRed, true)));

        assertThat(parts).isEqualTo(expected);
    }

    @Test
    public void processAndConvertToDrawableParts_progressAtMax() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100).setColor(Color.RED));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 100;
        int progressMax = 100;
        boolean isStyledByProgress = true;

        List<Part> parts = NotificationProgressBar.processAndConvertToDrawableParts(
                segments, points, progress, progressMax, isStyledByProgress);

        List<Part> expected = new ArrayList<>(List.of(new Segment(1f, Color.RED)));

        assertThat(parts).isEqualTo(expected);
    }

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToDrawableParts_progressAboveMax() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 150;
        int progressMax = 100;
        boolean isStyledByProgress = true;

        NotificationProgressBar.processAndConvertToDrawableParts(segments, points, progress,
                progressMax, isStyledByProgress);
    }

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToDrawableParts_pointPositionIsNegative() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100));
        List<ProgressStyle.Point> points = new ArrayList<>();
        points.add(new ProgressStyle.Point(-50).setColor(Color.RED));
        int progress = 50;
        int progressMax = 100;
        boolean isStyledByProgress = true;

        NotificationProgressBar.processAndConvertToDrawableParts(segments, points, progress,
                progressMax,
                isStyledByProgress);
    }

    @Test(expected = IllegalArgumentException.class)
    public void processAndConvertToDrawableParts_pointPositionAboveMax() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(100));
        List<ProgressStyle.Point> points = new ArrayList<>();
        points.add(new ProgressStyle.Point(150).setColor(Color.RED));
        int progress = 50;
        int progressMax = 100;
        boolean isStyledByProgress = true;

        NotificationProgressBar.processAndConvertToDrawableParts(segments, points, progress,
                progressMax,
                isStyledByProgress);
    }

    @Test
    public void processAndConvertToDrawableParts_multipleSegmentsWithoutPoints() {
        List<ProgressStyle.Segment> segments = new ArrayList<>();
        segments.add(new ProgressStyle.Segment(50).setColor(Color.RED));
        segments.add(new ProgressStyle.Segment(50).setColor(Color.GREEN));
        List<ProgressStyle.Point> points = new ArrayList<>();
        int progress = 60;
        int progressMax = 100;
        boolean isStyledByProgress = true;

        List<Part> parts = NotificationProgressBar.processAndConvertToDrawableParts(
                segments, points, progress, progressMax, isStyledByProgress);

        // Colors with 50% opacity
        int fadedGreen = 0x7F00FF00;

        List<Part> expected = new ArrayList<>(List.of(
                new Segment(0.50f, Color.RED),
                new Segment(0.10f, Color.GREEN),
                new Segment(0.40f, fadedGreen, true)));

        assertThat(parts).isEqualTo(expected);
    }

    @Test
    public void processAndConvertToDrawableParts_singleSegmentWithPoints() {
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

        // Colors with 50% opacity
        int fadedBlue = 0x7F0000FF;
        int fadedYellow = 0x7FFFFF00;

        List<Part> expected = new ArrayList<>(List.of(
                new Segment(0.15f, Color.BLUE),
                new Point(null, Color.RED),
                new Segment(0.10f, Color.BLUE),
                new Point(null, Color.BLUE),
                new Segment(0.35f, Color.BLUE),
                new Point(null, Color.BLUE),
                new Segment(0.15f, fadedBlue, true),
                new Point(null, fadedYellow, true),
                new Segment(0.25f, fadedBlue, true)));

        List<Part> parts = NotificationProgressBar.processAndConvertToDrawableParts(
                segments, points, progress, progressMax, isStyledByProgress);

        assertThat(parts).isEqualTo(expected);
    }

    @Test
    public void processAndConvertToDrawableParts_multipleSegmentsWithPoints() {
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

        List<Part> parts = NotificationProgressBar.processAndConvertToDrawableParts(
                segments, points, progress, progressMax, isStyledByProgress);

        // Colors with 50% opacity
        int fadedGreen = 0x7F00FF00;
        int fadedYellow = 0x7FFFFF00;

        List<Part> expected = new ArrayList<>(List.of(
                new Segment(0.15f, Color.RED),
                new Point(null, Color.RED),
                new Segment(0.10f, Color.RED),
                new Point(null, Color.BLUE),
                new Segment(0.25f, Color.RED),
                new Segment(0.10f, Color.GREEN),
                new Point(null, Color.BLUE),
                new Segment(0.15f, fadedGreen, true),
                new Point(null, fadedYellow, true),
                new Segment(0.25f, fadedGreen, true)));

        assertThat(parts).isEqualTo(expected);
    }

    @Test
    public void processAndConvertToDrawableParts_multipleSegmentsWithPoints_notStyledByProgress() {
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

        List<Part> parts = NotificationProgressBar.processAndConvertToDrawableParts(
                segments, points, progress, progressMax, isStyledByProgress);

        List<Part> expected = new ArrayList<>(List.of(
                new Segment(0.15f, Color.RED),
                new Point(null, Color.RED),
                new Segment(0.10f, Color.RED),
                new Point(null, Color.BLUE),
                new Segment(0.25f, Color.RED),
                new Segment(0.25f, Color.GREEN),
                new Point(null, Color.YELLOW),
                new Segment(0.25f, Color.GREEN)));

        assertThat(parts).isEqualTo(expected);
    }
}
