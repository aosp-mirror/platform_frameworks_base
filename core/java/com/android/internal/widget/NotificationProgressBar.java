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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification.ProgressStyle;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.RemotableViewMethod;
import android.widget.ProgressBar;
import android.widget.RemoteViews;

import androidx.annotation.ColorInt;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.internal.widget.NotificationProgressDrawable.Part;
import com.android.internal.widget.NotificationProgressDrawable.Point;
import com.android.internal.widget.NotificationProgressDrawable.Segment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * NotificationProgressBar extends the capabilities of ProgressBar by adding functionalities to
 * represent Notification ProgressStyle progress, such as for ridesharing and navigation.
 */
@RemoteViews.RemoteView
public class NotificationProgressBar extends ProgressBar {
    private NotificationProgressModel mProgressModel;
    @Nullable
    private Drawable mProgressTrackerDrawable = null;

    public NotificationProgressBar(Context context) {
        this(context, null);
    }

    public NotificationProgressBar(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.progressBarStyle);
    }

    public NotificationProgressBar(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public NotificationProgressBar(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    /**
     * Setter for the notification progress model.
     *
     * @see NotificationProgressModel#fromBundle
     * @see #setProgressModelAsync
     */
    @RemotableViewMethod(asyncImpl = "setProgressModelAsync")
    public void setProgressModel(@Nullable Bundle bundle) {
        Preconditions.checkArgument(bundle != null,
                "Bundle shouldn't be null");

        mProgressModel = NotificationProgressModel.fromBundle(bundle);
    }

    private void setProgressModel(@NonNull NotificationProgressModel model) {
        mProgressModel = model;
    }

    /**
     * Setter for the progress tracker icon.
     *
     * @see #setProgressTrackerIconAsync
     */
    @RemotableViewMethod(asyncImpl = "setProgressTrackerIconAsync")
    public void setProgressTrackerIcon(@Nullable Icon icon) {
    }


    /**
     * Async version of {@link #setProgressTrackerIcon}
     */
    public Runnable setProgressTrackerIconAsync(@Nullable Icon icon) {
        final Drawable progressTrackerDrawable;
        if (icon != null) {
            progressTrackerDrawable = icon.loadDrawable(getContext());
        } else {
            progressTrackerDrawable = null;
        }
        return () -> {
            setProgressTrackerDrawable(progressTrackerDrawable);
        };
    }

    private void setProgressTrackerDrawable(@Nullable  Drawable drawable) {
        mProgressTrackerDrawable = drawable;
    }

    /**
     * Processes the ProgressStyle data and convert to list of {@code
     * NotificationProgressDrawable.Part}.
     */
    @VisibleForTesting
    public static List<Part> processAndConvertToDrawableParts(
            List<ProgressStyle.Segment> segments,
            List<ProgressStyle.Point> points,
            int progress,
            boolean isStyledByProgress
    ) {
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("List of segments shouldn't be empty");
        }

        for (ProgressStyle.Segment segment : segments) {
            final int length = segment.getLength();
            if (length <= 0) {
                throw new IllegalArgumentException("Invalid segment length : " + length);
            }
        }

        final int progressMax = segments.stream().mapToInt(ProgressStyle.Segment::getLength).sum();

        if (progress < 0 || progress > progressMax) {
            throw new IllegalArgumentException("Invalid progress : " + progress);
        }
        for (ProgressStyle.Point point : points) {
            final int pos = point.getPosition();
            if (pos <= 0 || pos >= progressMax) {
                throw new IllegalArgumentException("Invalid Point position : " + pos);
            }
        }

        final Map<Integer, ProgressStyle.Segment> startToSegmentMap = generateStartToSegmentMap(
                segments);
        final Map<Integer, ProgressStyle.Point> positionToPointMap = generatePositionToPointMap(
                points);
        final SortedSet<Integer> sortedPos = generateSortedPositionSet(startToSegmentMap,
                positionToPointMap, progress, isStyledByProgress);

        final Map<Integer, ProgressStyle.Segment> startToSplitSegmentMap =
                splitSegmentsByPointsAndProgress(
                        startToSegmentMap, sortedPos, progressMax);

        return convertToDrawableParts(startToSplitSegmentMap, positionToPointMap, sortedPos,
                progress, progressMax,
                isStyledByProgress);
    }


    // Any segment with a point on it gets split by the point.
    // If isStyledByProgress is true, also split the segment with the progress value in its range.
    private static Map<Integer, ProgressStyle.Segment> splitSegmentsByPointsAndProgress(
            Map<Integer, ProgressStyle.Segment> startToSegmentMap,
            SortedSet<Integer> sortedPos,
            int progressMax) {
        int prevSegStart = 0;
        for (Integer pos : sortedPos) {
            if (pos == 0 || pos == progressMax) continue;
            if (startToSegmentMap.containsKey(pos)) {
                prevSegStart = pos;
                continue;
            }

            final ProgressStyle.Segment prevSeg = startToSegmentMap.get(prevSegStart);
            final ProgressStyle.Segment leftSeg = new ProgressStyle.Segment(
                    pos - prevSegStart).setColor(
                    prevSeg.getColor());
            final ProgressStyle.Segment rightSeg = new ProgressStyle.Segment(
                    prevSegStart + prevSeg.getLength() - pos).setColor(prevSeg.getColor());

            startToSegmentMap.put(prevSegStart, leftSeg);
            startToSegmentMap.put(pos, rightSeg);

            prevSegStart = pos;
        }

        return startToSegmentMap;
    }

    private static List<Part> convertToDrawableParts(
            Map<Integer, ProgressStyle.Segment> startToSegmentMap,
            Map<Integer, ProgressStyle.Point> positionToPointMap,
            SortedSet<Integer> sortedPos,
            int progress,
            int progressMax,
            boolean isStyledByProgress
    ) {
        List<Part> parts = new ArrayList<>();
        boolean styleRemainingParts = false;
        for (Integer pos : sortedPos) {
            if (positionToPointMap.containsKey(pos)) {
                final ProgressStyle.Point point = positionToPointMap.get(pos);
                final int color = maybeGetFadedColor(point.getColor(), styleRemainingParts);
                parts.add(new Point(null, color, styleRemainingParts));
            }
            // We want the Point at the current progress to be filled (not faded), but a Segment
            // starting at this progress to be faded.
            if (isStyledByProgress && !styleRemainingParts && pos == progress) {
                styleRemainingParts = true;
            }
            if (startToSegmentMap.containsKey(pos)) {
                final ProgressStyle.Segment seg = startToSegmentMap.get(pos);
                final int color = maybeGetFadedColor(seg.getColor(), styleRemainingParts);
                parts.add(new Segment(
                        (float) seg.getLength() / progressMax, color, styleRemainingParts));
            }
        }

        return parts;
    }

    @ColorInt
    private static int maybeGetFadedColor(@ColorInt int color, boolean fade) {
        if (!fade) return color;

        return NotificationProgressDrawable.getFadedColor(color);
    }

    private static Map<Integer, ProgressStyle.Segment> generateStartToSegmentMap(
            List<ProgressStyle.Segment> segments) {
        final Map<Integer, ProgressStyle.Segment> startToSegmentMap = new HashMap<>();

        int currentStart = 0;  // Initial start position is 0

        for (ProgressStyle.Segment segment : segments) {
            // Use the current start position as the key, and the segment as the value
            startToSegmentMap.put(currentStart, segment);

            // Update the start position for the next segment
            currentStart += segment.getLength();
        }

        return startToSegmentMap;
    }

    private static Map<Integer, ProgressStyle.Point> generatePositionToPointMap(
            List<ProgressStyle.Point> points) {
        final Map<Integer, ProgressStyle.Point> positionToPointMap = new HashMap<>();

        for (ProgressStyle.Point point : points) {
            positionToPointMap.put(point.getPosition(), point);
        }

        return positionToPointMap;
    }

    private static SortedSet<Integer> generateSortedPositionSet(
            Map<Integer, ProgressStyle.Segment> startToSegmentMap,
            Map<Integer, ProgressStyle.Point> positionToPointMap, int progress,
            boolean isStyledByProgress) {
        final SortedSet<Integer> sortedPos = new TreeSet<>(startToSegmentMap.keySet());
        sortedPos.addAll(positionToPointMap.keySet());
        if (isStyledByProgress) {
            sortedPos.add(progress);
        }

        return sortedPos;
    }
}
