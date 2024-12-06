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
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.view.RemotableViewMethod;
import android.widget.ProgressBar;
import android.widget.RemoteViews;

import androidx.annotation.ColorInt;

import com.android.internal.R;
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
public final class NotificationProgressBar extends ProgressBar {
    private static final String TAG = "NotificationProgressBar";

    private NotificationProgressDrawable mNotificationProgressDrawable;

    private NotificationProgressModel mProgressModel;

    @Nullable
    private List<Part> mProgressDrawableParts = null;

    @Nullable
    private Drawable mTracker = null;

    /** @see R.styleable#NotificationProgressBar_trackerHeight */
    private final int mTrackerHeight;
    private int mTrackerWidth;
    private int mTrackerPos;
    private final Matrix mMatrix = new Matrix();
    private Matrix mTrackerDrawMatrix = null;

    private float mScale = 0;
    /** Indicates whether mTrackerPos needs to be recalculated before the tracker is drawn. */
    private boolean mTrackerPosIsDirty = false;

    public NotificationProgressBar(Context context) {
        this(context, null);
    }

    public NotificationProgressBar(Context context, AttributeSet attrs) {
        this(context, attrs, R.attr.progressBarStyle);
    }

    public NotificationProgressBar(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public NotificationProgressBar(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.NotificationProgressBar, defStyleAttr, defStyleRes);
        saveAttributeDataForStyleable(context, R.styleable.NotificationProgressBar, attrs, a,
                defStyleAttr,
                defStyleRes);

        try {
            mNotificationProgressDrawable = getNotificationProgressDrawable();
        } catch (IllegalStateException ex) {
            Log.e(TAG, "Can't get NotificationProgressDrawable", ex);
        }

        // Supports setting the tracker in xml, but ProgressStyle notifications set/override it
        // via {@code setProgressTrackerIcon}.
        final Drawable tracker = a.getDrawable(R.styleable.NotificationProgressBar_tracker);
        setTracker(tracker);

        // If this is configured to be a non-zero size, will scale and crop the tracker drawable to
        // ensure its aspect ratio is between 2:1 to 1:2.
        mTrackerHeight = a.getDimensionPixelSize(R.styleable.NotificationProgressBar_trackerHeight,
                0);
    }

    /**
     * Setter for the notification progress model.
     *
     * @see NotificationProgressModel#fromBundle
     */
    @RemotableViewMethod
    public void setProgressModel(@Nullable Bundle bundle) {
        Preconditions.checkArgument(bundle != null,
                "Bundle shouldn't be null");

        mProgressModel = NotificationProgressModel.fromBundle(bundle);
        final boolean isIndeterminate = mProgressModel.isIndeterminate();
        setIndeterminate(isIndeterminate);

        if (isIndeterminate) {
            final int indeterminateColor = mProgressModel.getIndeterminateColor();
            setIndeterminateTintList(ColorStateList.valueOf(indeterminateColor));
        } else {
            final int progress = mProgressModel.getProgress();
            final int progressMax = mProgressModel.getProgressMax();
            mProgressDrawableParts = processAndConvertToDrawableParts(mProgressModel.getSegments(),
                    mProgressModel.getPoints(),
                    progress,
                    progressMax,
                    mProgressModel.isStyledByProgress());

            if (mNotificationProgressDrawable != null) {
                mNotificationProgressDrawable.setParts(mProgressDrawableParts);
            }

            setMax(progressMax);
            setProgress(progress);
        }
    }

    @NonNull
    private NotificationProgressDrawable getNotificationProgressDrawable() {
        final Drawable d = getProgressDrawable();
        if (d == null) {
            throw new IllegalStateException("getProgressDrawable() returns null");
        }
        if (!(d instanceof LayerDrawable)) {
            throw new IllegalStateException("getProgressDrawable() doesn't return a LayerDrawable");
        }

        final Drawable layer = ((LayerDrawable) d).findDrawableByLayerId(R.id.background);
        if (!(layer instanceof NotificationProgressDrawable)) {
            throw new IllegalStateException(
                    "Couldn't get NotificationProgressDrawable, retrieved drawable is: " + (
                            layer != null ? layer.toString() : null));
        }

        return (NotificationProgressDrawable) layer;
    }

    /**
     * Setter for the progress tracker icon.
     *
     * @see #setProgressTrackerIconAsync
     */
    @RemotableViewMethod(asyncImpl = "setProgressTrackerIconAsync")
    public void setProgressTrackerIcon(@Nullable Icon icon) {
        final Drawable progressTrackerDrawable;
        if (icon != null) {
            progressTrackerDrawable = icon.loadDrawable(getContext());
        } else {
            progressTrackerDrawable = null;
        }
        setTracker(progressTrackerDrawable);
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
            setTracker(progressTrackerDrawable);
        };
    }

    private void setTracker(@Nullable Drawable tracker) {
        if (tracker == mTracker) return;

        if (mTracker != null) {
            mTracker.setCallback(null);
        }

        if (tracker != null) {
            tracker.setCallback(this);
            if (getMirrorForRtl()) {
                tracker.setAutoMirrored(true);
            }

            if (canResolveLayoutDirection()) {
                tracker.setLayoutDirection(getLayoutDirection());
            }
        }

        final boolean trackerSizeChanged = trackerSizeChanged(tracker, mTracker);

        mTracker = tracker;
        if (mNotificationProgressDrawable != null) {
            mNotificationProgressDrawable.setHasTrackerIcon(mTracker != null);
        }

        configureTrackerBounds();
        updateTrackerAndBarPos(getWidth(), getHeight());

        // Change in tracker size may lead to change in measured view size.
        // @see #onMeasure.
        if (trackerSizeChanged) requestLayout();

        invalidate();

        if (tracker != null && tracker.isStateful()) {
            // Note that if the states are different this won't work.
            // For now, let's consider that an app bug.
            tracker.setState(getDrawableState());
        }
    }

    private static boolean trackerSizeChanged(@Nullable Drawable newTracker,
            @Nullable Drawable oldTracker) {
        if (newTracker == null && oldTracker == null) return false;
        if (newTracker == null && oldTracker != null) return true;
        if (newTracker != null && oldTracker == null) return true;

        return newTracker.getIntrinsicWidth() != oldTracker.getIntrinsicWidth()
                || newTracker.getIntrinsicHeight() != oldTracker.getIntrinsicHeight();
    }

    private void configureTrackerBounds() {
        // Reset the tracker draw matrix to null
        mTrackerDrawMatrix = null;

        if (mTracker == null || mTrackerHeight <= 0) {
            return;
        }

        final int dWidth = mTracker.getIntrinsicWidth();
        final int dHeight = mTracker.getIntrinsicHeight();
        if (dWidth <= 0 || dHeight <= 0) {
            return;
        }
        final int maxDWidth = dHeight * 2;
        final int maxDHeight = dWidth * 2;

        mTrackerDrawMatrix = mMatrix;
        float scale;
        float dx = 0, dy = 0;

        if (dWidth > maxDWidth) {
            scale = (float) mTrackerHeight / (float) dHeight;
            dx = (maxDWidth * scale - dWidth * scale) * 0.5f;
            mTrackerWidth = (int) (maxDWidth * scale);
        } else if (dHeight > maxDHeight) {
            scale = (float) mTrackerHeight * 0.5f / (float) dWidth;
            dy = (maxDHeight * scale - dHeight * scale) * 0.5f;
            mTrackerWidth = mTrackerHeight / 2;
        } else {
            scale = (float) mTrackerHeight / (float) dHeight;
            mTrackerWidth = (int) (dWidth * scale);
        }

        mTrackerDrawMatrix.setScale(scale, scale);
        mTrackerDrawMatrix.postTranslate(Math.round(dx), Math.round(dy));
    }

    @Override
    public synchronized void setProgress(int progress) {
        super.setProgress(progress);

        onMaybeVisualProgressChanged();
    }

    @Override
    public void setProgress(int progress, boolean animate) {
        // Animation isn't supported by NotificationProgressBar.
        super.setProgress(progress, false);

        onMaybeVisualProgressChanged();
    }

    @Override
    public synchronized void setMin(int min) {
        super.setMin(min);

        onMaybeVisualProgressChanged();
    }

    @Override
    public synchronized void setMax(int max) {
        super.setMax(max);

        onMaybeVisualProgressChanged();
    }

    private void onMaybeVisualProgressChanged() {
        float scale = getScale();
        if (mScale == scale) return;

        mScale = scale;
        mTrackerPosIsDirty = true;
        invalidate();
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return who == mTracker || super.verifyDrawable(who);
    }

    @Override
    public void jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState();

        if (mTracker != null) {
            mTracker.jumpToCurrentState();
        }
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();

        final Drawable tracker = mTracker;
        if (tracker != null && tracker.isStateful()
                && tracker.setState(getDrawableState())) {
            invalidateDrawable(tracker);
        }
    }

    @Override
    public void drawableHotspotChanged(float x, float y) {
        super.drawableHotspotChanged(x, y);

        if (mTracker != null) {
            mTracker.setHotspot(x, y);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        updateTrackerAndBarPos(w, h);
    }

    private void updateTrackerAndBarPos(int w, int h) {
        final int paddedHeight = h - mPaddingTop - mPaddingBottom;
        final Drawable bar = getCurrentDrawable();
        final Drawable tracker = mTracker;

        // The max height does not incorporate padding, whereas the height
        // parameter does.
        final int barHeight = Math.min(getMaxHeight(), paddedHeight);
        final int trackerHeight = tracker == null ? 0
                : ((mTrackerHeight <= 0) ? tracker.getIntrinsicHeight() : mTrackerHeight);

        // Apply offset to whichever item is taller.
        final int barOffsetY;
        final int trackerOffsetY;
        if (trackerHeight > barHeight) {
            final int offsetHeight = (paddedHeight - trackerHeight) / 2;
            barOffsetY = offsetHeight + (trackerHeight - barHeight) / 2;
            trackerOffsetY = offsetHeight;
        } else {
            final int offsetHeight = (paddedHeight - barHeight) / 2;
            barOffsetY = offsetHeight;
            trackerOffsetY = offsetHeight + (barHeight - trackerHeight) / 2;
        }

        if (bar != null) {
            final int barWidth = w - mPaddingRight - mPaddingLeft;
            bar.setBounds(0, barOffsetY, barWidth, barOffsetY + barHeight);
        }

        if (tracker != null) {
            setTrackerPos(w, tracker, mScale, trackerOffsetY);
        }
    }

    private float getScale() {
        int min = getMin();
        int max = getMax();
        int range = max - min;
        return range > 0 ? (getProgress() - min) / (float) range : 0;
    }

    /**
     * Updates the tracker drawable bounds.
     *
     * @param w Width of the view, including padding
     * @param tracker Drawable used for the tracker
     * @param scale Current progress between 0 and 1
     * @param offsetY Vertical offset for centering. If set to
     *            {@link Integer#MIN_VALUE}, the current offset will be used.
     */
    private void setTrackerPos(int w, Drawable tracker, float scale, int offsetY) {
        int available = w - mPaddingLeft - mPaddingRight;
        final int trackerWidth = tracker.getIntrinsicWidth();
        final int trackerHeight = tracker.getIntrinsicHeight();
        available -= ((mTrackerHeight <= 0) ? trackerWidth : mTrackerWidth);

        final int trackerPos = (int) (scale * available + 0.5f);

        final int top, bottom;
        if (offsetY == Integer.MIN_VALUE) {
            final Rect oldBounds = tracker.getBounds();
            top = oldBounds.top;
            bottom = oldBounds.bottom;
        } else {
            top = offsetY;
            bottom = offsetY + trackerHeight;
        }

        mTrackerPos = (isLayoutRtl() && getMirrorForRtl()) ? available - trackerPos : trackerPos;
        final int left = 0;
        final int right = left + trackerWidth;

        final Drawable background = getBackground();
        if (background != null) {
            final int bkgOffsetX = mPaddingLeft;
            final int bkgOffsetY = mPaddingTop;
            background.setHotspotBounds(left + bkgOffsetX, top + bkgOffsetY,
                    right + bkgOffsetX, bottom + bkgOffsetY);
        }

        // Canvas will be translated, so 0,0 is where we start drawing
        tracker.setBounds(left, top, right, bottom);

        mTrackerPosIsDirty = false;
    }

    @Override
    public void onResolveDrawables(int layoutDirection) {
        super.onResolveDrawables(layoutDirection);

        if (mTracker != null) {
            mTracker.setLayoutDirection(layoutDirection);
        }
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (isIndeterminate()) return;
        drawTracker(canvas);
    }

    /**
     * Draw the tracker.
     */
    private void drawTracker(Canvas canvas) {
        if (mTracker == null) return;

        if (mTrackerPosIsDirty) {
            setTrackerPos(getWidth(), mTracker, mScale, Integer.MIN_VALUE);
        }

        final int saveCount = canvas.save();
        // Translate the canvas origin to tracker position to make the draw matrix and the RtL
        // transformations work.
        canvas.translate(mPaddingLeft + mTrackerPos, mPaddingTop);

        if (mTrackerHeight > 0) {
            canvas.clipRect(0, 0, mTrackerWidth, mTrackerHeight);
        }

        if (mTrackerDrawMatrix != null) {
            canvas.concat(mTrackerDrawMatrix);
        }
        mTracker.draw(canvas);
        canvas.restoreToCount(saveCount);
    }

    @Override
    protected synchronized void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Drawable d = getCurrentDrawable();

        int trackerHeight = mTracker == null ? 0 : mTracker.getIntrinsicHeight();
        int dw = 0;
        int dh = 0;
        if (d != null) {
            dw = Math.max(getMinWidth(), Math.min(getMaxWidth(), d.getIntrinsicWidth()));
            dh = Math.max(getMinHeight(), Math.min(getMaxHeight(), d.getIntrinsicHeight()));
            dh = Math.max(trackerHeight, dh);
        }
        dw += mPaddingLeft + mPaddingRight;
        dh += mPaddingTop + mPaddingBottom;

        setMeasuredDimension(resolveSizeAndState(dw, widthMeasureSpec, 0),
                resolveSizeAndState(dh, heightMeasureSpec, 0));
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return NotificationProgressBar.class.getName();
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);

        final Drawable tracker = mTracker;
        if (tracker != null) {
            setTrackerPos(getWidth(), tracker, mScale, Integer.MIN_VALUE);

            // Since we draw translated, the drawable's bounds that it signals
            // for invalidation won't be the actual bounds we want invalidated,
            // so just invalidate this whole view.
            invalidate();
        }
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
            int progressMax,
            boolean isStyledByProgress
    ) {
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("List of segments shouldn't be empty");
        }

        final int totalLength = segments.stream().mapToInt(ProgressStyle.Segment::getLength).sum();
        if (progressMax != totalLength) {
            throw new IllegalArgumentException("Invalid progressMax : " + progressMax);
        }

        for (ProgressStyle.Segment segment : segments) {
            final int length = segment.getLength();
            if (length <= 0) {
                throw new IllegalArgumentException("Invalid segment length : " + length);
            }
        }

        if (progress < 0 || progress > progressMax) {
            throw new IllegalArgumentException("Invalid progress : " + progress);
        }
        for (ProgressStyle.Point point : points) {
            final int pos = point.getPosition();
            if (pos < 0 || pos > progressMax) {
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
