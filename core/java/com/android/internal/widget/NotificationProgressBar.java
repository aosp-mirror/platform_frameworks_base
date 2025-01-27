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
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Pair;
import android.view.RemotableViewMethod;
import android.widget.ProgressBar;
import android.widget.RemoteViews;

import androidx.annotation.ColorInt;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.internal.widget.NotificationProgressDrawable.DrawablePart;
import com.android.internal.widget.NotificationProgressDrawable.DrawablePoint;
import com.android.internal.widget.NotificationProgressDrawable.DrawableSegment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * NotificationProgressBar extends the capabilities of ProgressBar by adding functionalities to
 * represent Notification ProgressStyle progress, such as for ridesharing and navigation.
 */
@RemoteViews.RemoteView
public final class NotificationProgressBar extends ProgressBar implements
        NotificationProgressDrawable.BoundsChangeListener {
    private static final String TAG = "NotificationProgressBar";
    private static final boolean DEBUG = false;

    private NotificationProgressDrawable mNotificationProgressDrawable;
    private final Rect mProgressDrawableBounds = new Rect();

    private NotificationProgressModel mProgressModel;

    @Nullable
    private List<Part> mParts = null;

    // List of drawable parts before segment splitting by process.
    @Nullable
    private List<DrawablePart> mProgressDrawableParts = null;

    @Nullable
    private Drawable mTracker = null;
    private boolean mHasTrackerIcon = false;

    /** @see R.styleable#NotificationProgressBar_trackerHeight */
    private final int mTrackerHeight;
    private int mTrackerWidth;
    private int mTrackerPos;
    private final Matrix mMatrix = new Matrix();
    private Matrix mTrackerDrawMatrix = null;

    private float mProgressFraction = 0;
    /**
     * The location of progress on the stretched and rescaled progress bar, in fraction. Used for
     * calculating the tracker position. If stretching and rescaling is not needed, ==
     * mProgressFraction.
     */
    private float mAdjustedProgressFraction = 0;
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

        final TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.NotificationProgressBar, defStyleAttr, defStyleRes);
        saveAttributeDataForStyleable(context, R.styleable.NotificationProgressBar, attrs, a,
                defStyleAttr,
                defStyleRes);

        try {
            mNotificationProgressDrawable = getNotificationProgressDrawable();
            mNotificationProgressDrawable.setBoundsChangeListener(this);
        } catch (IllegalStateException ex) {
            Log.e(TAG, "Can't get NotificationProgressDrawable", ex);
        }

        // Supports setting the tracker in xml, but ProgressStyle notifications set/override it
        // via {@code #setProgressTrackerIcon}.
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
        Preconditions.checkArgument(bundle != null, "Bundle shouldn't be null");

        mProgressModel = NotificationProgressModel.fromBundle(bundle);
        final boolean isIndeterminate = mProgressModel.isIndeterminate();
        setIndeterminate(isIndeterminate);

        if (isIndeterminate) {
            final int indeterminateColor = mProgressModel.getIndeterminateColor();
            setIndeterminateTintList(ColorStateList.valueOf(indeterminateColor));
        } else {
            // TODO: b/372908709 - maybe don't rerun the entire calculation every time the
            //  progress model is updated? For example, if the segments and parts aren't changed,
            //  there is no need to call `processAndConvertToViewParts` again.

            final int progress = mProgressModel.getProgress();
            final int progressMax = mProgressModel.getProgressMax();

            mParts = processModelAndConvertToViewParts(mProgressModel.getSegments(),
                    mProgressModel.getPoints(),
                    progress,
                    progressMax);

            setMax(progressMax);
            setProgress(progress);

            if (mNotificationProgressDrawable != null
                    && mNotificationProgressDrawable.getBounds().width() != 0) {
                updateDrawableParts();
            }
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
        return () -> setTracker(progressTrackerDrawable);
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
        final boolean hasTrackerIcon = (mTracker != null);
        if (mHasTrackerIcon != hasTrackerIcon) {
            mHasTrackerIcon = hasTrackerIcon;
            if (mNotificationProgressDrawable != null
                    && mNotificationProgressDrawable.getBounds().width() != 0
                    && mProgressModel.isStyledByProgress()) {
                updateDrawableParts();
            }
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

    // This updates the visual position of the progress indicator, i.e., the tracker. It doesn't
    // update the NotificationProgressDrawable, which is updated by {@code #setProgressModel}.
    @Override
    public synchronized void setProgress(int progress) {
        super.setProgress(progress);

        onMaybeVisualProgressChanged();
    }

    // This updates the visual position of the progress indicator, i.e., the tracker. It doesn't
    // update the NotificationProgressDrawable, which is updated by {@code #setProgressModel}.
    @Override
    public void setProgress(int progress, boolean animate) {
        // Animation isn't supported by NotificationProgressBar.
        super.setProgress(progress, false);

        onMaybeVisualProgressChanged();
    }

    // This updates the visual position of the progress indicator, i.e., the tracker. It doesn't
    // update the NotificationProgressDrawable, which is updated by {@code #setProgressModel}.
    @Override
    public synchronized void setMin(int min) {
        super.setMin(min);

        onMaybeVisualProgressChanged();
    }

    // This updates the visual position of the progress indicator, i.e., the tracker. It doesn't
    // update the NotificationProgressDrawable, which is updated by {@code #setProgressModel}.
    @Override
    public synchronized void setMax(int max) {
        super.setMax(max);

        onMaybeVisualProgressChanged();
    }

    private void onMaybeVisualProgressChanged() {
        float progressFraction = getProgressFraction();
        if (mProgressFraction == progressFraction) return;

        mProgressFraction = progressFraction;
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
        if (tracker != null && tracker.isStateful() && tracker.setState(getDrawableState())) {
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

    @Override
    public void onDrawableBoundsChanged() {
        final Rect progressDrawableBounds = mNotificationProgressDrawable.getBounds();

        if (mProgressDrawableBounds.equals(progressDrawableBounds)) return;

        if (mProgressDrawableBounds.width() != progressDrawableBounds.width()) {
            updateDrawableParts();
        }

        mProgressDrawableBounds.set(progressDrawableBounds);
    }

    private void updateDrawableParts() {
        if (DEBUG) {
            Log.d(TAG, "updateDrawableParts() called. mNotificationProgressDrawable = "
                    + mNotificationProgressDrawable + ", mParts = " + mParts);
        }

        if (mNotificationProgressDrawable == null) return;
        if (mParts == null) return;

        final float width = mNotificationProgressDrawable.getBounds().width();
        if (width == 0) {
            if (mProgressDrawableParts != null) {
                if (DEBUG) {
                    Log.d(TAG, "Clearing mProgressDrawableParts");
                }
                mProgressDrawableParts.clear();
                mNotificationProgressDrawable.setParts(mProgressDrawableParts);
            }
            return;
        }

        final float segSegGap = mNotificationProgressDrawable.getSegSegGap();
        final float segPointGap = mNotificationProgressDrawable.getSegPointGap();
        final float pointRadius = mNotificationProgressDrawable.getPointRadius();
        mProgressDrawableParts = processPartsAndConvertToDrawableParts(
                mParts,
                width,
                segSegGap,
                segPointGap,
                pointRadius,
                mHasTrackerIcon
        );

        final float segmentMinWidth = mNotificationProgressDrawable.getSegmentMinWidth();
        final float progressFraction = getProgressFraction();
        final boolean isStyledByProgress = mProgressModel.isStyledByProgress();
        final float progressGap =
                mHasTrackerIcon ? 0F : mNotificationProgressDrawable.getSegSegGap();
        Pair<List<DrawablePart>, Float> p = null;
        try {
            p = maybeStretchAndRescaleSegments(
                    mParts,
                    mProgressDrawableParts,
                    segmentMinWidth,
                    pointRadius,
                    progressFraction,
                    width,
                    isStyledByProgress,
                    progressGap
            );
        } catch (NotEnoughWidthToFitAllPartsException ex) {
            Log.w(TAG, "Failed to stretch and rescale segments", ex);
        }

        List<ProgressStyle.Segment> fallbackSegments = null;
        if (p == null && mProgressModel.getSegments().size() > 1) {
            Log.w(TAG, "Falling back to single segment");
            try {
                fallbackSegments = List.of(new ProgressStyle.Segment(getMax()).setColor(
                        mProgressModel.getSegmentsFallbackColor()
                                == NotificationProgressModel.INVALID_COLOR
                                ? mProgressModel.getSegments().getFirst().getColor()
                                : mProgressModel.getSegmentsFallbackColor()));
                p = processModelAndConvertToFinalDrawableParts(
                        fallbackSegments,
                        mProgressModel.getPoints(),
                        mProgressModel.getProgress(),
                        getMax(),
                        width,
                        segSegGap,
                        segPointGap,
                        pointRadius,
                        mHasTrackerIcon,
                        segmentMinWidth,
                        isStyledByProgress
                );
            } catch (NotEnoughWidthToFitAllPartsException ex) {
                Log.w(TAG, "Failed to stretch and rescale segments with single segment fallback",
                        ex);
            }
        }

        if (p == null && !mProgressModel.getPoints().isEmpty()) {
            Log.w(TAG, "Falling back to single segment and no points");
            if (fallbackSegments == null) {
                fallbackSegments = List.of(new ProgressStyle.Segment(getMax()).setColor(
                        mProgressModel.getSegmentsFallbackColor()
                                == NotificationProgressModel.INVALID_COLOR
                                ? mProgressModel.getSegments().getFirst().getColor()
                                : mProgressModel.getSegmentsFallbackColor()));
            }
            try {
                p = processModelAndConvertToFinalDrawableParts(
                        fallbackSegments,
                        Collections.emptyList(),
                        mProgressModel.getProgress(),
                        getMax(),
                        width,
                        segSegGap,
                        segPointGap,
                        pointRadius,
                        mHasTrackerIcon,
                        segmentMinWidth,
                        isStyledByProgress
                );
            } catch (NotEnoughWidthToFitAllPartsException ex) {
                Log.w(TAG,
                        "Failed to stretch and rescale segments with single segments and no points",
                        ex);
            }
        }

        if (p == null) {
            Log.w(TAG, "Falling back to no stretching and rescaling");
            p = maybeSplitDrawableSegmentsByProgress(
                    mParts,
                    mProgressDrawableParts,
                    progressFraction,
                    width,
                    isStyledByProgress,
                    progressGap);
        }

        if (DEBUG) {
            Log.d(TAG, "Updating NotificationProgressDrawable parts");
        }
        mNotificationProgressDrawable.setParts(p.first);
        mAdjustedProgressFraction = p.second / width;
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
            setTrackerPos(w, tracker, mAdjustedProgressFraction, trackerOffsetY);
        }
    }

    private float getProgressFraction() {
        int min = getMin();
        int max = getMax();
        int range = max - min;
        return getProgressFraction(range, (getProgress() - min));
    }

    private static float getProgressFraction(int progressMax, int progress) {
        return progressMax > 0 ? progress / (float) progressMax : 0;
    }

    /**
     * Updates the tracker drawable bounds.
     *
     * @param w                Width of the view, including padding
     * @param tracker          Drawable used for the tracker
     * @param progressFraction Current progress between 0 and 1
     * @param offsetY          Vertical offset for centering. If set to
     *                         {@link Integer#MIN_VALUE}, the current offset will be used.
     */
    private void setTrackerPos(int w, Drawable tracker, float progressFraction, int offsetY) {
        int available = w - mPaddingLeft - mPaddingRight;
        final int trackerWidth = tracker.getIntrinsicWidth();
        final int trackerHeight = tracker.getIntrinsicHeight();
        available -= ((mTrackerHeight <= 0) ? trackerWidth : mTrackerWidth);

        final int trackerPos = (int) (progressFraction * available + 0.5f);

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
            background.setHotspotBounds(left + bkgOffsetX, top + bkgOffsetY, right + bkgOffsetX,
                    bottom + bkgOffsetY);
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
            setTrackerPos(getWidth(), mTracker, mAdjustedProgressFraction, Integer.MIN_VALUE);
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
            setTrackerPos(getWidth(), tracker, mAdjustedProgressFraction, Integer.MIN_VALUE);

            // Since we draw translated, the drawable's bounds that it signals
            // for invalidation won't be the actual bounds we want invalidated,
            // so just invalidate this whole view.
            invalidate();
        }
    }

    /**
     * Processes the ProgressStyle data and convert to a list of {@code Part}.
     */
    @VisibleForTesting
    public static List<Part> processModelAndConvertToViewParts(
            List<ProgressStyle.Segment> segments,
            List<ProgressStyle.Point> points,
            int progress,
            int progressMax
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
                positionToPointMap);

        final Map<Integer, ProgressStyle.Segment> startToSplitSegmentMap = splitSegmentsByPoints(
                startToSegmentMap, sortedPos, progressMax);

        return convertToViewParts(startToSplitSegmentMap, positionToPointMap, sortedPos,
                progressMax);
    }

    // Any segment with a point on it gets split by the point.
    private static Map<Integer, ProgressStyle.Segment> splitSegmentsByPoints(
            Map<Integer, ProgressStyle.Segment> startToSegmentMap,
            SortedSet<Integer> sortedPos,
            int progressMax
    ) {
        int prevSegStart = 0;
        for (Integer pos : sortedPos) {
            if (pos == 0 || pos == progressMax) continue;
            if (startToSegmentMap.containsKey(pos)) {
                prevSegStart = pos;
                continue;
            }

            final ProgressStyle.Segment prevSeg = startToSegmentMap.get(prevSegStart);
            final ProgressStyle.Segment leftSeg = new ProgressStyle.Segment(
                    pos - prevSegStart).setColor(prevSeg.getColor());
            final ProgressStyle.Segment rightSeg = new ProgressStyle.Segment(
                    prevSegStart + prevSeg.getLength() - pos).setColor(prevSeg.getColor());

            startToSegmentMap.put(prevSegStart, leftSeg);
            startToSegmentMap.put(pos, rightSeg);

            prevSegStart = pos;
        }

        return startToSegmentMap;
    }

    private static List<Part> convertToViewParts(
            Map<Integer, ProgressStyle.Segment> startToSegmentMap,
            Map<Integer, ProgressStyle.Point> positionToPointMap,
            SortedSet<Integer> sortedPos,
            int progressMax
    ) {
        List<Part> parts = new ArrayList<>();
        for (Integer pos : sortedPos) {
            if (positionToPointMap.containsKey(pos)) {
                final ProgressStyle.Point point = positionToPointMap.get(pos);
                parts.add(new Point(point.getColor()));
            }
            if (startToSegmentMap.containsKey(pos)) {
                final ProgressStyle.Segment seg = startToSegmentMap.get(pos);
                parts.add(new Segment((float) seg.getLength() / progressMax, seg.getColor()));
            }
        }

        return parts;
    }

    @ColorInt
    private static int maybeGetFadedColor(@ColorInt int color, boolean fade) {
        if (!fade) return color;

        return getFadedColor(color);
    }

    /**
     * Get a color with an opacity that's 50% of the input color.
     */
    @ColorInt
    static int getFadedColor(@ColorInt int color) {
        return Color.argb(
                (int) (Color.alpha(color) * 0.5f + 0.5f),
                Color.red(color),
                Color.green(color),
                Color.blue(color));
    }

    private static Map<Integer, ProgressStyle.Segment> generateStartToSegmentMap(
            List<ProgressStyle.Segment> segments
    ) {
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
            List<ProgressStyle.Point> points
    ) {
        final Map<Integer, ProgressStyle.Point> positionToPointMap = new HashMap<>();

        for (ProgressStyle.Point point : points) {
            positionToPointMap.put(point.getPosition(), point);
        }

        return positionToPointMap;
    }

    private static SortedSet<Integer> generateSortedPositionSet(
            Map<Integer, ProgressStyle.Segment> startToSegmentMap,
            Map<Integer, ProgressStyle.Point> positionToPointMap
    ) {
        final SortedSet<Integer> sortedPos = new TreeSet<>(startToSegmentMap.keySet());
        sortedPos.addAll(positionToPointMap.keySet());

        return sortedPos;
    }

    /**
     * Processes the list of {@code Part} and convert to a list of {@code DrawablePart}.
     */
    @VisibleForTesting
    public static List<DrawablePart> processPartsAndConvertToDrawableParts(
            List<Part> parts,
            float totalWidth,
            float segSegGap,
            float segPointGap,
            float pointRadius,
            boolean hasTrackerIcon
    ) {
        List<DrawablePart> drawableParts = new ArrayList<>();

        // generally, we will start drawing at (x, y) and end at (x+w, y)
        float x = (float) 0;

        final int nParts = parts.size();
        for (int iPart = 0; iPart < nParts; iPart++) {
            final Part part = parts.get(iPart);
            final Part prevPart = iPart == 0 ? null : parts.get(iPart - 1);
            final Part nextPart = iPart + 1 == nParts ? null : parts.get(iPart + 1);
            if (part instanceof Segment segment) {
                final float segWidth = segment.mFraction * totalWidth;
                // Advance the start position to account for a point immediately prior.
                final float startOffset = getSegStartOffset(prevPart, pointRadius, segPointGap,
                        iPart == 1);
                final float start = x + startOffset;
                // Retract the end position to account for the padding and a point immediately
                // after.
                final float endOffset = getSegEndOffset(segment, nextPart, pointRadius, segPointGap,
                        segSegGap, iPart == nParts - 2, hasTrackerIcon);
                final float end = x + segWidth - endOffset;

                drawableParts.add(new DrawableSegment(start, end, segment.mColor, segment.mFaded));

                segment.mStart = x;
                segment.mEnd = x + segWidth;

                // Advance the current position to account for the segment's fraction of the total
                // width (ignoring offset and padding)
                x += segWidth;
            } else if (part instanceof Point point) {
                final float pointWidth = 2 * pointRadius;
                float start = x - pointRadius;
                float end = x + pointRadius;
                // Only shift the points right at the start/end.
                // For the points close to the start/end, the segment minimum width requirement
                // would take care of shifting them to be within the bounds.
                if (iPart == 0) {
                    start = 0;
                    end = pointWidth;
                } else if (iPart == nParts - 1) {
                    start = totalWidth - pointWidth;
                    end = totalWidth;
                }

                drawableParts.add(new DrawablePoint(start, end, point.mColor));
            }
        }

        return drawableParts;
    }

    private static float getSegStartOffset(Part prevPart, float pointRadius, float segPointGap,
            boolean isSecondPart) {
        if (!(prevPart instanceof Point)) return 0F;
        final float pointOffset = isSecondPart ? pointRadius : 0;
        return pointOffset + pointRadius + segPointGap;
    }

    private static float getSegEndOffset(Segment seg, Part nextPart, float pointRadius,
            float segPointGap, float segSegGap, boolean isSecondToLastPart,
            boolean hasTrackerIcon) {
        if (nextPart == null) return 0F;
        if (nextPart instanceof Segment nextSeg) {
            if (!seg.mFaded && nextSeg.mFaded) {
                // @see Segment#mFaded
                return hasTrackerIcon ? 0F : segSegGap;
            }
            return segSegGap;
        }

        final float pointOffset = isSecondToLastPart ? pointRadius : 0;
        return segPointGap + pointRadius + pointOffset;
    }

    /**
     * Processes the list of {@code DrawablePart} data and convert to a pair of:
     * - list of processed {@code DrawablePart}.
     * - location of progress on the stretched and rescaled progress bar.
     */
    @VisibleForTesting
    public static Pair<List<DrawablePart>, Float> maybeStretchAndRescaleSegments(
            List<Part> parts,
            List<DrawablePart> drawableParts,
            float segmentMinWidth,
            float pointRadius,
            float progressFraction,
            float totalWidth,
            boolean isStyledByProgress,
            float progressGap
    ) throws NotEnoughWidthToFitAllPartsException {
        final List<DrawableSegment> drawableSegments = drawableParts
                .stream()
                .filter(DrawableSegment.class::isInstance)
                .map(DrawableSegment.class::cast)
                .toList();
        float totalExcessWidth = 0;
        float totalPositiveExcessWidth = 0;
        for (DrawableSegment drawableSegment : drawableSegments) {
            final float excessWidth = drawableSegment.getWidth() - segmentMinWidth;
            totalExcessWidth += excessWidth;
            if (excessWidth > 0) totalPositiveExcessWidth += excessWidth;
        }

        // All drawable segments are above minimum width. No need to stretch and rescale.
        if (totalExcessWidth == totalPositiveExcessWidth) {
            return maybeSplitDrawableSegmentsByProgress(
                    parts,
                    drawableParts,
                    progressFraction,
                    totalWidth,
                    isStyledByProgress,
                    progressGap);
        }

        if (totalExcessWidth < 0) {
            throw new NotEnoughWidthToFitAllPartsException(
                    "Not enough width to satisfy the minimum width for segments.");
        }

        final int nParts = drawableParts.size();
        float startOffset = 0;
        for (int iPart = 0; iPart < nParts; iPart++) {
            final DrawablePart drawablePart = drawableParts.get(iPart);
            if (drawablePart instanceof DrawableSegment drawableSegment) {
                final float origDrawableSegmentWidth = drawableSegment.getWidth();

                float drawableSegmentWidth = segmentMinWidth;
                // Allocate the totalExcessWidth to the segments above minimum, proportionally to
                // their initial excessWidth.
                if (origDrawableSegmentWidth > segmentMinWidth) {
                    drawableSegmentWidth +=
                            totalExcessWidth * (origDrawableSegmentWidth - segmentMinWidth)
                                    / totalPositiveExcessWidth;
                }

                final float widthDiff = drawableSegmentWidth - drawableSegment.getWidth();

                // Adjust drawable segments to new widths
                drawableSegment.setStart(drawableSegment.getStart() + startOffset);
                drawableSegment.setEnd(
                        drawableSegment.getStart() + origDrawableSegmentWidth + widthDiff);

                // Also adjust view segments to new width. (For view segments, only start is
                // needed?)
                // Check that segments and drawableSegments are of the same size?
                final Segment segment = (Segment) parts.get(iPart);
                final float origSegmentWidth = segment.getWidth();
                segment.mStart = segment.mStart + startOffset;
                segment.mEnd = segment.mStart + origSegmentWidth + widthDiff;

                // Increase startOffset for the subsequent segments.
                startOffset += widthDiff;
            } else if (drawablePart instanceof DrawablePoint drawablePoint) {
                drawablePoint.setStart(drawablePoint.getStart() + startOffset);
                drawablePoint.setEnd(drawablePoint.getStart() + 2 * pointRadius);
            }
        }

        return maybeSplitDrawableSegmentsByProgress(
                parts,
                drawableParts,
                progressFraction,
                totalWidth,
                isStyledByProgress,
                progressGap);
    }

    /**
     * Find the location of progress on the stretched and rescaled progress bar.
     * If isStyledByProgress is true, also split the drawable segment with the progress value in its
     * range. Style the drawable parts after process with reduced opacity and segment height.
     */
    private static Pair<List<DrawablePart>, Float> maybeSplitDrawableSegmentsByProgress(
            // Needed to get the original segment start and end positions in pixels.
            List<Part> parts,
            List<DrawablePart> drawableParts,
            float progressFraction,
            float totalWidth,
            boolean isStyledByProgress,
            float progressGap
    ) {
        if (progressFraction == 1) return new Pair<>(drawableParts, totalWidth);

        int iPartFirstSegmentToStyle = -1;
        int iPartSegmentToSplit = -1;
        float rescaledProgressX = 0;
        float startFraction = 0;
        final int nParts = parts.size();
        for (int iPart = 0; iPart < nParts; iPart++) {
            final Part part = parts.get(iPart);
            if (!(part instanceof Segment segment)) continue;
            if (startFraction == progressFraction) {
                iPartFirstSegmentToStyle = iPart;
                rescaledProgressX = segment.mStart;
                break;
            } else if (startFraction < progressFraction
                    && progressFraction < startFraction + segment.mFraction) {
                iPartSegmentToSplit = iPart;
                rescaledProgressX = segment.mStart
                        + (progressFraction - startFraction) / segment.mFraction
                        * segment.getWidth();
                break;
            }
            startFraction += segment.mFraction;
        }

        if (!isStyledByProgress) return new Pair<>(drawableParts, rescaledProgressX);

        List<DrawablePart> splitDrawableParts = new ArrayList<>();
        boolean styleRemainingParts = false;
        for (int iPart = 0; iPart < nParts; iPart++) {
            final DrawablePart drawablePart = drawableParts.get(iPart);
            if (drawablePart instanceof DrawablePoint drawablePoint) {
                final int color = maybeGetFadedColor(drawablePoint.getColor(), styleRemainingParts);
                splitDrawableParts.add(
                        new DrawablePoint(drawablePoint.getStart(), drawablePoint.getEnd(), color));
            }
            if (iPart == iPartFirstSegmentToStyle) styleRemainingParts = true;
            if (drawablePart instanceof DrawableSegment drawableSegment) {
                if (iPart == iPartSegmentToSplit) {
                    if (rescaledProgressX <= drawableSegment.getStart()) {
                        styleRemainingParts = true;
                        final int color = maybeGetFadedColor(drawableSegment.getColor(), true);
                        splitDrawableParts.add(new DrawableSegment(drawableSegment.getStart(),
                                drawableSegment.getEnd(), color, true));
                    } else if (drawableSegment.getStart() < rescaledProgressX
                            && rescaledProgressX < drawableSegment.getEnd()) {
                        splitDrawableParts.add(new DrawableSegment(drawableSegment.getStart(),
                                rescaledProgressX - progressGap, drawableSegment.getColor()));
                        final int color = maybeGetFadedColor(drawableSegment.getColor(), true);
                        splitDrawableParts.add(
                                new DrawableSegment(rescaledProgressX, drawableSegment.getEnd(),
                                        color, true));
                        styleRemainingParts = true;
                    } else {
                        splitDrawableParts.add(new DrawableSegment(drawableSegment.getStart(),
                                drawableSegment.getEnd(), drawableSegment.getColor()));
                        styleRemainingParts = true;
                    }
                } else {
                    final int color = maybeGetFadedColor(drawableSegment.getColor(),
                            styleRemainingParts);
                    splitDrawableParts.add(new DrawableSegment(drawableSegment.getStart(),
                            drawableSegment.getEnd(), color, styleRemainingParts));
                }
            }
        }

        return new Pair<>(splitDrawableParts, rescaledProgressX);
    }

    /**
     * Processes the ProgressStyle data and convert to a pair of:
     * - list of processed {@code DrawablePart}.
     * - location of progress on the stretched and rescaled progress bar.
     */
    @VisibleForTesting
    public static Pair<List<DrawablePart>, Float> processModelAndConvertToFinalDrawableParts(
            List<ProgressStyle.Segment> segments,
            List<ProgressStyle.Point> points,
            int progress,
            int progressMax,
            float totalWidth,
            float segSegGap,
            float segPointGap,
            float pointRadius,
            boolean hasTrackerIcon,
            float segmentMinWidth,
            boolean isStyledByProgress
    ) throws NotEnoughWidthToFitAllPartsException {
        List<Part> parts = processModelAndConvertToViewParts(segments, points, progress,
                progressMax);
        List<DrawablePart> drawableParts = processPartsAndConvertToDrawableParts(parts, totalWidth,
                segSegGap, segPointGap, pointRadius, hasTrackerIcon);
        return maybeStretchAndRescaleSegments(parts, drawableParts, segmentMinWidth, pointRadius,
                getProgressFraction(progressMax, progress), totalWidth, isStyledByProgress,
                hasTrackerIcon ? 0F : segSegGap);
    }

    /**
     * A part of the progress bar, which is either a {@link Segment} with non-zero length, or a
     * {@link Point} with zero length.
     */
    public interface Part {
    }

    /**
     * A segment is a part of the progress bar with non-zero length. For example, it can
     * represent a portion in a navigation journey with certain traffic condition.
     */
    public static final class Segment implements Part {
        private final float mFraction;
        @ColorInt
        private final int mColor;
        /**
         * Whether the segment is faded or not.
         * <p>
         * <pre>
         *     When mFaded is set to true, a combination of the following is done to the segment:
         *       1. The drawing color is mColor with opacity updated to 50%.
         *       2. The gap between faded and non-faded segments is:
         *          - the segment-segment gap, when there is no tracker icon
         *          - 0, when there is tracker icon
         *     </pre>
         * </p>
         */
        private final boolean mFaded;

        /** Start position (in pixels) */
        private float mStart;
        /** End position (in pixels */
        private float mEnd;

        public Segment(float fraction, @ColorInt int color) {
            this(fraction, color, false);
        }

        public Segment(float fraction, @ColorInt int color, boolean faded) {
            mFraction = fraction;
            mColor = color;
            mFaded = faded;
        }

        /** Returns the calculated drawing width of the part */
        public float getWidth() {
            return mEnd - mStart;
        }

        @Override
        public String toString() {
            return "Segment(fraction=" + this.mFraction + ", color=" + this.mColor + ", faded="
                    + this.mFaded + "), mStart = " + this.mStart + ", mEnd = " + this.mEnd;
        }

        // Needed for unit tests
        @Override
        public boolean equals(@androidx.annotation.Nullable Object other) {
            if (this == other) return true;

            if (other == null || getClass() != other.getClass()) return false;

            Segment that = (Segment) other;
            if (Float.compare(this.mFraction, that.mFraction) != 0) return false;
            if (this.mColor != that.mColor) return false;
            return this.mFaded == that.mFaded;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mFraction, mColor, mFaded);
        }
    }

    /**
     * A point is a part of the progress bar with zero length. Points are designated points within a
     * progress bar to visualize distinct stages or milestones. For example, a stop in a multi-stop
     * ride-share journey.
     */
    public static final class Point implements Part {
        @ColorInt
        private final int mColor;

        public Point(@ColorInt int color) {
            mColor = color;
        }

        @Override
        public String toString() {
            return "Point(color=" + this.mColor + ")";
        }

        // Needed for unit tests.
        @Override
        public boolean equals(@androidx.annotation.Nullable Object other) {
            if (this == other) return true;

            if (other == null || getClass() != other.getClass()) return false;

            Point that = (Point) other;

            return this.mColor == that.mColor;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mColor);
        }
    }

    public static class NotEnoughWidthToFitAllPartsException extends Exception {
        public NotEnoughWidthToFitAllPartsException(String message) {
            super(message);
        }
    }
}
