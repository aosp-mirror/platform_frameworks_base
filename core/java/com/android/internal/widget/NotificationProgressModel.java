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


import android.annotation.ColorInt;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.app.Flags;
import android.app.Notification;
import android.app.Notification.ProgressStyle.Point;
import android.app.Notification.ProgressStyle.Segment;
import android.graphics.Color;
import android.os.Bundle;

import com.android.internal.util.Preconditions;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Data model for {@link NotificationProgressBar}.
 *
 * This class holds the necessary data to render the notification progressbar.
 * It is used to bind the progress style progress data to {@link NotificationProgressBar}.
 *
 * @hide
 * @see NotificationProgressModel#toBundle
 * @see NotificationProgressModel#fromBundle
 */
@FlaggedApi(Flags.FLAG_API_RICH_ONGOING)
public final class NotificationProgressModel {
    private static final int INVALID_INDETERMINATE_COLOR = Color.TRANSPARENT;
    private static final String KEY_SEGMENTS = "segments";
    private static final String KEY_POINTS = "points";
    private static final String KEY_PROGRESS = "progress";
    private static final String KEY_IS_STYLED_BY_PROGRESS = "isStyledByProgress";
    private static final String KEY_INDETERMINATE_COLOR = "indeterminateColor";
    private final List<Segment> mSegments;
    private final List<Point> mPoints;
    private final int mProgress;
    private final boolean mIsStyledByProgress;
    @ColorInt
    private final int mIndeterminateColor;

    public NotificationProgressModel(
            @NonNull List<Segment> segments,
            @NonNull List<Point> points,
            int progress,
            boolean isStyledByProgress
    ) {
        Preconditions.checkArgument(progress >= 0);
        Preconditions.checkArgument(!segments.isEmpty());
        mSegments = segments;
        mPoints = points;
        mProgress = progress;
        mIsStyledByProgress = isStyledByProgress;
        mIndeterminateColor = INVALID_INDETERMINATE_COLOR;
    }

    public NotificationProgressModel(
            @ColorInt int indeterminateColor
    ) {
        Preconditions.checkArgument(indeterminateColor != INVALID_INDETERMINATE_COLOR);
        mSegments = Collections.emptyList();
        mPoints = Collections.emptyList();
        mProgress = 0;
        mIsStyledByProgress = false;
        mIndeterminateColor = indeterminateColor;
    }

    public List<Segment> getSegments() {
        return mSegments;
    }

    public List<Point> getPoints() {
        return mPoints;
    }

    public int getProgress() {
        return mProgress;
    }

    public int getProgressMax() {
        return mSegments.stream().mapToInt(Notification.ProgressStyle.Segment::getLength).sum();
    }

    public boolean isStyledByProgress() {
        return mIsStyledByProgress;
    }

    @ColorInt
    public int getIndeterminateColor() {
        return mIndeterminateColor;
    }

    public boolean isIndeterminate() {
        return mIndeterminateColor != INVALID_INDETERMINATE_COLOR;
    }

    /**
     * Returns a {@link Bundle} representation of this {@link NotificationProgressModel}.
     */
    @NonNull
    public Bundle toBundle() {
        final Bundle bundle = new Bundle();
        if (mIndeterminateColor != INVALID_INDETERMINATE_COLOR) {
            bundle.putInt(KEY_INDETERMINATE_COLOR, mIndeterminateColor);
        } else {
            bundle.putParcelableList(KEY_SEGMENTS,
                    Notification.ProgressStyle.getProgressSegmentsAsBundleList(mSegments));
            bundle.putParcelableList(KEY_POINTS,
                    Notification.ProgressStyle.getProgressPointsAsBundleList(mPoints));
            bundle.putInt(KEY_PROGRESS, mProgress);
            bundle.putBoolean(KEY_IS_STYLED_BY_PROGRESS, mIsStyledByProgress);
        }
        return bundle;
    }

    /**
     * Creates a {@link NotificationProgressModel} from a {@link Bundle}.
     */
    @NonNull
    public static NotificationProgressModel fromBundle(@NonNull Bundle bundle) {
        final int indeterminateColor = bundle.getInt(KEY_INDETERMINATE_COLOR,
                INVALID_INDETERMINATE_COLOR);
        if (indeterminateColor != INVALID_INDETERMINATE_COLOR) {
            return new NotificationProgressModel(indeterminateColor);
        } else {
            final List<Segment> segments =
                    Notification.ProgressStyle.getProgressSegmentsFromBundleList(
                            bundle.getParcelableArrayList(KEY_SEGMENTS, Bundle.class));
            final List<Point> points =
                    Notification.ProgressStyle.getProgressPointsFromBundleList(
                            bundle.getParcelableArrayList(KEY_POINTS, Bundle.class));
            final int progress = bundle.getInt(KEY_PROGRESS);
            final boolean isStyledByProgress = bundle.getBoolean(KEY_IS_STYLED_BY_PROGRESS);
            return new NotificationProgressModel(segments, points, progress, isStyledByProgress);
        }
    }

    @Override
    public String toString() {
        return "NotificationProgressModel{"
                + "mSegments=" + mSegments
                + ", mPoints=" + mPoints
                + ", mProgress=" + mProgress
                + ", mIsStyledByProgress=" + mIsStyledByProgress
                + ", mIndeterminateColor=" + mIndeterminateColor + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        final NotificationProgressModel that = (NotificationProgressModel) o;
        return mProgress == that.mProgress
                && mIsStyledByProgress == that.mIsStyledByProgress
                && mIndeterminateColor == that.mIndeterminateColor
                && Objects.equals(mSegments, that.mSegments)
                && Objects.equals(mPoints, that.mPoints);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSegments,
                mPoints,
                mProgress,
                mIsStyledByProgress,
                mIndeterminateColor);
    }
}
