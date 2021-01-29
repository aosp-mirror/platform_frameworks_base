/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Region.Op;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inspector.InspectableProperty;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * AbsSeekBar extends the capabilities of ProgressBar by adding a draggable thumb.
 */
public abstract class AbsSeekBar extends ProgressBar {
    private final Rect mTempRect = new Rect();

    @UnsupportedAppUsage
    private Drawable mThumb;
    private ColorStateList mThumbTintList = null;
    private BlendMode mThumbBlendMode = null;
    private boolean mHasThumbTint = false;
    private boolean mHasThumbBlendMode = false;

    private Drawable mTickMark;
    private ColorStateList mTickMarkTintList = null;
    private BlendMode mTickMarkBlendMode = null;
    private boolean mHasTickMarkTint = false;
    private boolean mHasTickMarkBlendMode = false;

    private int mThumbOffset;
    @UnsupportedAppUsage
    private boolean mSplitTrack;

    /**
     * On touch, this offset plus the scaled value from the position of the
     * touch will form the progress value. Usually 0.
     */
    @UnsupportedAppUsage
    float mTouchProgressOffset;

    /**
     * Whether this is user seekable.
     */
    @UnsupportedAppUsage
    boolean mIsUserSeekable = true;

    /**
     * On key presses (right or left), the amount to increment/decrement the
     * progress.
     */
    private int mKeyProgressIncrement = 1;

    private static final int NO_ALPHA = 0xFF;
    @UnsupportedAppUsage
    private float mDisabledAlpha;

    private int mThumbExclusionMaxSize;
    private int mScaledTouchSlop;
    private float mTouchDownX;
    @UnsupportedAppUsage
    private boolean mIsDragging;
    private float mTouchThumbOffset = 0.0f;

    private List<Rect> mUserGestureExclusionRects = Collections.emptyList();
    private final List<Rect> mGestureExclusionRects = new ArrayList<>();
    private final Rect mThumbRect = new Rect();

    public AbsSeekBar(Context context) {
        super(context);
    }

    public AbsSeekBar(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AbsSeekBar(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public AbsSeekBar(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.SeekBar, defStyleAttr, defStyleRes);
        saveAttributeDataForStyleable(context, R.styleable.SeekBar, attrs, a, defStyleAttr,
                defStyleRes);

        final Drawable thumb = a.getDrawable(R.styleable.SeekBar_thumb);
        setThumb(thumb);

        if (a.hasValue(R.styleable.SeekBar_thumbTintMode)) {
            mThumbBlendMode = Drawable.parseBlendMode(a.getInt(
                    R.styleable.SeekBar_thumbTintMode, -1), mThumbBlendMode);
            mHasThumbBlendMode = true;
        }

        if (a.hasValue(R.styleable.SeekBar_thumbTint)) {
            mThumbTintList = a.getColorStateList(R.styleable.SeekBar_thumbTint);
            mHasThumbTint = true;
        }

        final Drawable tickMark = a.getDrawable(R.styleable.SeekBar_tickMark);
        setTickMark(tickMark);

        if (a.hasValue(R.styleable.SeekBar_tickMarkTintMode)) {
            mTickMarkBlendMode = Drawable.parseBlendMode(a.getInt(
                    R.styleable.SeekBar_tickMarkTintMode, -1), mTickMarkBlendMode);
            mHasTickMarkBlendMode = true;
        }

        if (a.hasValue(R.styleable.SeekBar_tickMarkTint)) {
            mTickMarkTintList = a.getColorStateList(R.styleable.SeekBar_tickMarkTint);
            mHasTickMarkTint = true;
        }

        mSplitTrack = a.getBoolean(R.styleable.SeekBar_splitTrack, false);

        // Guess thumb offset if thumb != null, but allow layout to override.
        final int thumbOffset = a.getDimensionPixelOffset(
                R.styleable.SeekBar_thumbOffset, getThumbOffset());
        setThumbOffset(thumbOffset);

        final boolean useDisabledAlpha = a.getBoolean(R.styleable.SeekBar_useDisabledAlpha, true);
        a.recycle();

        if (useDisabledAlpha) {
            final TypedArray ta = context.obtainStyledAttributes(attrs, R.styleable.Theme, 0, 0);
            mDisabledAlpha = ta.getFloat(R.styleable.Theme_disabledAlpha, 0.5f);
            ta.recycle();
        } else {
            mDisabledAlpha = 1.0f;
        }

        applyThumbTint();
        applyTickMarkTint();

        mScaledTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mThumbExclusionMaxSize = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.seekbar_thumb_exclusion_max_size);
    }

    /**
     * Sets the thumb that will be drawn at the end of the progress meter within the SeekBar.
     * <p>
     * If the thumb is a valid drawable (i.e. not null), half its width will be
     * used as the new thumb offset (@see #setThumbOffset(int)).
     *
     * @param thumb Drawable representing the thumb
     */
    public void setThumb(Drawable thumb) {
        final boolean needUpdate;
        // This way, calling setThumb again with the same bitmap will result in
        // it recalcuating mThumbOffset (if for example it the bounds of the
        // drawable changed)
        if (mThumb != null && thumb != mThumb) {
            mThumb.setCallback(null);
            needUpdate = true;
        } else {
            needUpdate = false;
        }

        if (thumb != null) {
            thumb.setCallback(this);
            if (canResolveLayoutDirection()) {
                thumb.setLayoutDirection(getLayoutDirection());
            }

            // Assuming the thumb drawable is symmetric, set the thumb offset
            // such that the thumb will hang halfway off either edge of the
            // progress bar.
            mThumbOffset = thumb.getIntrinsicWidth() / 2;

            // If we're updating get the new states
            if (needUpdate &&
                    (thumb.getIntrinsicWidth() != mThumb.getIntrinsicWidth()
                        || thumb.getIntrinsicHeight() != mThumb.getIntrinsicHeight())) {
                requestLayout();
            }
        }

        mThumb = thumb;

        applyThumbTint();
        invalidate();

        if (needUpdate) {
            updateThumbAndTrackPos(getWidth(), getHeight());
            if (thumb != null && thumb.isStateful()) {
                // Note that if the states are different this won't work.
                // For now, let's consider that an app bug.
                int[] state = getDrawableState();
                thumb.setState(state);
            }
        }
    }

    /**
     * Return the drawable used to represent the scroll thumb - the component that
     * the user can drag back and forth indicating the current value by its position.
     *
     * @return The current thumb drawable
     */
    public Drawable getThumb() {
        return mThumb;
    }

    /**
     * Applies a tint to the thumb drawable. Does not modify the current tint
     * mode, which is {@link PorterDuff.Mode#SRC_IN} by default.
     * <p>
     * Subsequent calls to {@link #setThumb(Drawable)} will automatically
     * mutate the drawable and apply the specified tint and tint mode using
     * {@link Drawable#setTintList(ColorStateList)}.
     *
     * @param tint the tint to apply, may be {@code null} to clear tint
     *
     * @attr ref android.R.styleable#SeekBar_thumbTint
     * @see #getThumbTintList()
     * @see Drawable#setTintList(ColorStateList)
     */
    public void setThumbTintList(@Nullable ColorStateList tint) {
        mThumbTintList = tint;
        mHasThumbTint = true;

        applyThumbTint();
    }

    /**
     * Returns the tint applied to the thumb drawable, if specified.
     *
     * @return the tint applied to the thumb drawable
     * @attr ref android.R.styleable#SeekBar_thumbTint
     * @see #setThumbTintList(ColorStateList)
     */
    @InspectableProperty(name = "thumbTint")
    @Nullable
    public ColorStateList getThumbTintList() {
        return mThumbTintList;
    }

    /**
     * Specifies the blending mode used to apply the tint specified by
     * {@link #setThumbTintList(ColorStateList)}} to the thumb drawable. The
     * default mode is {@link PorterDuff.Mode#SRC_IN}.
     *
     * @param tintMode the blending mode used to apply the tint, may be
     *                 {@code null} to clear tint
     *
     * @attr ref android.R.styleable#SeekBar_thumbTintMode
     * @see #getThumbTintMode()
     * @see Drawable#setTintMode(PorterDuff.Mode)
     */
    public void setThumbTintMode(@Nullable PorterDuff.Mode tintMode) {
        setThumbTintBlendMode(tintMode != null ? BlendMode.fromValue(tintMode.nativeInt) :
                null);
    }

    /**
     * Specifies the blending mode used to apply the tint specified by
     * {@link #setThumbTintList(ColorStateList)}} to the thumb drawable. The
     * default mode is {@link BlendMode#SRC_IN}.
     *
     * @param blendMode the blending mode used to apply the tint, may be
     *                 {@code null} to clear tint
     *
     * @attr ref android.R.styleable#SeekBar_thumbTintMode
     * @see #getThumbTintMode()
     * @see Drawable#setTintBlendMode(BlendMode)
     */
    public void setThumbTintBlendMode(@Nullable BlendMode blendMode) {
        mThumbBlendMode = blendMode;
        mHasThumbBlendMode = true;
        applyThumbTint();
    }

    /**
     * Returns the blending mode used to apply the tint to the thumb drawable,
     * if specified.
     *
     * @return the blending mode used to apply the tint to the thumb drawable
     * @attr ref android.R.styleable#SeekBar_thumbTintMode
     * @see #setThumbTintMode(PorterDuff.Mode)
     */
    @InspectableProperty
    @Nullable
    public PorterDuff.Mode getThumbTintMode() {
        return mThumbBlendMode != null
                ? BlendMode.blendModeToPorterDuffMode(mThumbBlendMode) : null;
    }

    /**
     * Returns the blending mode used to apply the tint to the thumb drawable,
     * if specified.
     *
     * @return the blending mode used to apply the tint to the thumb drawable
     * @attr ref android.R.styleable#SeekBar_thumbTintMode
     * @see #setThumbTintBlendMode(BlendMode)
     */
    @Nullable
    public BlendMode getThumbTintBlendMode() {
        return mThumbBlendMode;
    }

    private void applyThumbTint() {
        if (mThumb != null && (mHasThumbTint || mHasThumbBlendMode)) {
            mThumb = mThumb.mutate();

            if (mHasThumbTint) {
                mThumb.setTintList(mThumbTintList);
            }

            if (mHasThumbBlendMode) {
                mThumb.setTintBlendMode(mThumbBlendMode);
            }

            // The drawable (or one of its children) may not have been
            // stateful before applying the tint, so let's try again.
            if (mThumb.isStateful()) {
                mThumb.setState(getDrawableState());
            }
        }
    }

    /**
     * @see #setThumbOffset(int)
     */
    public int getThumbOffset() {
        return mThumbOffset;
    }

    /**
     * Sets the thumb offset that allows the thumb to extend out of the range of
     * the track.
     *
     * @param thumbOffset The offset amount in pixels.
     */
    public void setThumbOffset(int thumbOffset) {
        mThumbOffset = thumbOffset;
        invalidate();
    }

    /**
     * Specifies whether the track should be split by the thumb. When true,
     * the thumb's optical bounds will be clipped out of the track drawable,
     * then the thumb will be drawn into the resulting gap.
     *
     * @param splitTrack Whether the track should be split by the thumb
     */
    public void setSplitTrack(boolean splitTrack) {
        mSplitTrack = splitTrack;
        invalidate();
    }

    /**
     * Returns whether the track should be split by the thumb.
     */
    public boolean getSplitTrack() {
        return mSplitTrack;
    }

    /**
     * Sets the drawable displayed at each progress position, e.g. at each
     * possible thumb position.
     *
     * @param tickMark the drawable to display at each progress position
     */
    public void setTickMark(Drawable tickMark) {
        if (mTickMark != null) {
            mTickMark.setCallback(null);
        }

        mTickMark = tickMark;

        if (tickMark != null) {
            tickMark.setCallback(this);
            tickMark.setLayoutDirection(getLayoutDirection());
            if (tickMark.isStateful()) {
                tickMark.setState(getDrawableState());
            }
            applyTickMarkTint();
        }

        invalidate();
    }

    /**
     * @return the drawable displayed at each progress position
     */
    public Drawable getTickMark() {
        return mTickMark;
    }

    /**
     * Applies a tint to the tick mark drawable. Does not modify the current tint
     * mode, which is {@link PorterDuff.Mode#SRC_IN} by default.
     * <p>
     * Subsequent calls to {@link #setTickMark(Drawable)} will automatically
     * mutate the drawable and apply the specified tint and tint mode using
     * {@link Drawable#setTintList(ColorStateList)}.
     *
     * @param tint the tint to apply, may be {@code null} to clear tint
     *
     * @attr ref android.R.styleable#SeekBar_tickMarkTint
     * @see #getTickMarkTintList()
     * @see Drawable#setTintList(ColorStateList)
     */
    public void setTickMarkTintList(@Nullable ColorStateList tint) {
        mTickMarkTintList = tint;
        mHasTickMarkTint = true;

        applyTickMarkTint();
    }

    /**
     * Returns the tint applied to the tick mark drawable, if specified.
     *
     * @return the tint applied to the tick mark drawable
     * @attr ref android.R.styleable#SeekBar_tickMarkTint
     * @see #setTickMarkTintList(ColorStateList)
     */
    @InspectableProperty(name = "tickMarkTint")
    @Nullable
    public ColorStateList getTickMarkTintList() {
        return mTickMarkTintList;
    }

    /**
     * Specifies the blending mode used to apply the tint specified by
     * {@link #setTickMarkTintList(ColorStateList)}} to the tick mark drawable. The
     * default mode is {@link PorterDuff.Mode#SRC_IN}.
     *
     * @param tintMode the blending mode used to apply the tint, may be
     *                 {@code null} to clear tint
     *
     * @attr ref android.R.styleable#SeekBar_tickMarkTintMode
     * @see #getTickMarkTintMode()
     * @see Drawable#setTintMode(PorterDuff.Mode)
     */
    public void setTickMarkTintMode(@Nullable PorterDuff.Mode tintMode) {
        setTickMarkTintBlendMode(tintMode != null ? BlendMode.fromValue(tintMode.nativeInt) : null);
    }

    /**
     * Specifies the blending mode used to apply the tint specified by
     * {@link #setTickMarkTintList(ColorStateList)}} to the tick mark drawable. The
     * default mode is {@link BlendMode#SRC_IN}.
     *
     * @param blendMode the blending mode used to apply the tint, may be
     *                 {@code null} to clear tint
     *
     * @attr ref android.R.styleable#SeekBar_tickMarkTintMode
     * @see #getTickMarkTintMode()
     * @see Drawable#setTintBlendMode(BlendMode)
     */
    public void setTickMarkTintBlendMode(@Nullable BlendMode blendMode) {
        mTickMarkBlendMode = blendMode;
        mHasTickMarkBlendMode = true;

        applyTickMarkTint();
    }

    /**
     * Returns the blending mode used to apply the tint to the tick mark drawable,
     * if specified.
     *
     * @return the blending mode used to apply the tint to the tick mark drawable
     * @attr ref android.R.styleable#SeekBar_tickMarkTintMode
     * @see #setTickMarkTintMode(PorterDuff.Mode)
     */
    @InspectableProperty
    @Nullable
    public PorterDuff.Mode getTickMarkTintMode() {
        return mTickMarkBlendMode != null
                ? BlendMode.blendModeToPorterDuffMode(mTickMarkBlendMode) : null;
    }

    /**
     * Returns the blending mode used to apply the tint to the tick mark drawable,
     * if specified.
     *
     * @return the blending mode used to apply the tint to the tick mark drawable
     * @attr ref android.R.styleable#SeekBar_tickMarkTintMode
     * @see #setTickMarkTintMode(PorterDuff.Mode)
     */
    @InspectableProperty(attributeId = android.R.styleable.SeekBar_tickMarkTintMode)
    @Nullable
    public BlendMode getTickMarkTintBlendMode() {
        return mTickMarkBlendMode;
    }

    private void applyTickMarkTint() {
        if (mTickMark != null && (mHasTickMarkTint || mHasTickMarkBlendMode)) {
            mTickMark = mTickMark.mutate();

            if (mHasTickMarkTint) {
                mTickMark.setTintList(mTickMarkTintList);
            }

            if (mHasTickMarkBlendMode) {
                mTickMark.setTintBlendMode(mTickMarkBlendMode);
            }

            // The drawable (or one of its children) may not have been
            // stateful before applying the tint, so let's try again.
            if (mTickMark.isStateful()) {
                mTickMark.setState(getDrawableState());
            }
        }
    }

    /**
     * Sets the amount of progress changed via the arrow keys.
     *
     * @param increment The amount to increment or decrement when the user
     *            presses the arrow keys.
     */
    public void setKeyProgressIncrement(int increment) {
        mKeyProgressIncrement = increment < 0 ? -increment : increment;
    }

    /**
     * Returns the amount of progress changed via the arrow keys.
     * <p>
     * By default, this will be a value that is derived from the progress range.
     *
     * @return The amount to increment or decrement when the user presses the
     *         arrow keys. This will be positive.
     */
    public int getKeyProgressIncrement() {
        return mKeyProgressIncrement;
    }

    @Override
    public synchronized void setMin(int min) {
        super.setMin(min);
        int range = getMax() - getMin();

        if ((mKeyProgressIncrement == 0) || (range / mKeyProgressIncrement > 20)) {

            // It will take the user too long to change this via keys, change it
            // to something more reasonable
            setKeyProgressIncrement(Math.max(1, Math.round((float) range / 20)));
        }
    }

    @Override
    public synchronized void setMax(int max) {
        super.setMax(max);
        int range = getMax() - getMin();

        if ((mKeyProgressIncrement == 0) || (range / mKeyProgressIncrement > 20)) {
            // It will take the user too long to change this via keys, change it
            // to something more reasonable
            setKeyProgressIncrement(Math.max(1, Math.round((float) range / 20)));
        }
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return who == mThumb || who == mTickMark || super.verifyDrawable(who);
    }

    @Override
    public void jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState();

        if (mThumb != null) {
            mThumb.jumpToCurrentState();
        }

        if (mTickMark != null) {
            mTickMark.jumpToCurrentState();
        }
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();

        final Drawable progressDrawable = getProgressDrawable();
        if (progressDrawable != null && mDisabledAlpha < 1.0f) {
            progressDrawable.setAlpha(isEnabled() ? NO_ALPHA : (int) (NO_ALPHA * mDisabledAlpha));
        }

        final Drawable thumb = mThumb;
        if (thumb != null && thumb.isStateful()
                && thumb.setState(getDrawableState())) {
            invalidateDrawable(thumb);
        }

        final Drawable tickMark = mTickMark;
        if (tickMark != null && tickMark.isStateful()
                && tickMark.setState(getDrawableState())) {
            invalidateDrawable(tickMark);
        }
    }

    @Override
    public void drawableHotspotChanged(float x, float y) {
        super.drawableHotspotChanged(x, y);

        if (mThumb != null) {
            mThumb.setHotspot(x, y);
        }
    }

    @Override
    void onVisualProgressChanged(int id, float scale) {
        super.onVisualProgressChanged(id, scale);

        if (id == R.id.progress) {
            final Drawable thumb = mThumb;
            if (thumb != null) {
                setThumbPos(getWidth(), thumb, scale, Integer.MIN_VALUE);

                // Since we draw translated, the drawable's bounds that it signals
                // for invalidation won't be the actual bounds we want invalidated,
                // so just invalidate this whole view.
                invalidate();
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        updateThumbAndTrackPos(w, h);
    }

    private void updateThumbAndTrackPos(int w, int h) {
        final int paddedHeight = h - mPaddingTop - mPaddingBottom;
        final Drawable track = getCurrentDrawable();
        final Drawable thumb = mThumb;

        // The max height does not incorporate padding, whereas the height
        // parameter does.
        final int trackHeight = Math.min(mMaxHeight, paddedHeight);
        final int thumbHeight = thumb == null ? 0 : thumb.getIntrinsicHeight();

        // Apply offset to whichever item is taller.
        final int trackOffset;
        final int thumbOffset;
        if (thumbHeight > trackHeight) {
            final int offsetHeight = (paddedHeight - thumbHeight) / 2;
            trackOffset = offsetHeight + (thumbHeight - trackHeight) / 2;
            thumbOffset = offsetHeight;
        } else {
            final int offsetHeight = (paddedHeight - trackHeight) / 2;
            trackOffset = offsetHeight;
            thumbOffset = offsetHeight + (trackHeight - thumbHeight) / 2;
        }

        if (track != null) {
            final int trackWidth = w - mPaddingRight - mPaddingLeft;
            track.setBounds(0, trackOffset, trackWidth, trackOffset + trackHeight);
        }

        if (thumb != null) {
            setThumbPos(w, thumb, getScale(), thumbOffset);
        }
    }

    private float getScale() {
        int min = getMin();
        int max = getMax();
        int range = max - min;
        return range > 0 ? (getProgress() - min) / (float) range : 0;
    }

    /**
     * Updates the thumb drawable bounds.
     *
     * @param w Width of the view, including padding
     * @param thumb Drawable used for the thumb
     * @param scale Current progress between 0 and 1
     * @param offset Vertical offset for centering. If set to
     *            {@link Integer#MIN_VALUE}, the current offset will be used.
     */
    private void setThumbPos(int w, Drawable thumb, float scale, int offset) {
        int available = w - mPaddingLeft - mPaddingRight;
        final int thumbWidth = thumb.getIntrinsicWidth();
        final int thumbHeight = thumb.getIntrinsicHeight();
        available -= thumbWidth;

        // The extra space for the thumb to move on the track
        available += mThumbOffset * 2;

        final int thumbPos = (int) (scale * available + 0.5f);

        final int top, bottom;
        if (offset == Integer.MIN_VALUE) {
            final Rect oldBounds = thumb.getBounds();
            top = oldBounds.top;
            bottom = oldBounds.bottom;
        } else {
            top = offset;
            bottom = offset + thumbHeight;
        }

        final int left = (isLayoutRtl() && mMirrorForRtl) ? available - thumbPos : thumbPos;
        final int right = left + thumbWidth;

        final Drawable background = getBackground();
        if (background != null) {
            final int offsetX = mPaddingLeft - mThumbOffset;
            final int offsetY = mPaddingTop;
            background.setHotspotBounds(left + offsetX, top + offsetY,
                    right + offsetX, bottom + offsetY);
        }

        // Canvas will be translated, so 0,0 is where we start drawing
        thumb.setBounds(left, top, right, bottom);
        updateGestureExclusionRects();
    }

    @Override
    public void setSystemGestureExclusionRects(@NonNull List<Rect> rects) {
        Preconditions.checkNotNull(rects, "rects must not be null");
        mUserGestureExclusionRects = rects;
        updateGestureExclusionRects();
    }

    private void updateGestureExclusionRects() {
        final Drawable thumb = mThumb;
        if (thumb == null) {
            super.setSystemGestureExclusionRects(mUserGestureExclusionRects);
            return;
        }
        mGestureExclusionRects.clear();
        thumb.copyBounds(mThumbRect);
        mThumbRect.offset(mPaddingLeft - mThumbOffset, mPaddingTop);
        growRectTo(mThumbRect, Math.min(getHeight(), mThumbExclusionMaxSize));
        mGestureExclusionRects.add(mThumbRect);
        mGestureExclusionRects.addAll(mUserGestureExclusionRects);
        super.setSystemGestureExclusionRects(mGestureExclusionRects);
    }

    /**
     * Grows {@code r} from its center such that each dimension is at least {@code minimumSize}.
     *
     * The result will still have the same {@link Rect#centerX()} and {@link Rect#centerY()} as the
     * input.
     *
     * @hide
     */
    @VisibleForTesting
    public void growRectTo(Rect r, int minimumSize) {
        int dy = minimumSize - r.height();
        if (dy > 0) {
            r.top -= (dy + 1) / 2;
            r.bottom += dy / 2;
        }
        int dx = minimumSize - r.width();
        if (dx > 0) {
            r.left -= (dx + 1) / 2;
            r.right += dx / 2;
        }
    }

    /**
     * @hide
     */
    @Override
    public void onResolveDrawables(int layoutDirection) {
        super.onResolveDrawables(layoutDirection);

        if (mThumb != null) {
            mThumb.setLayoutDirection(layoutDirection);
        }
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawThumb(canvas);
    }

    @Override
    void drawTrack(Canvas canvas) {
        final Drawable thumbDrawable = mThumb;
        if (thumbDrawable != null && mSplitTrack) {
            final Insets insets = thumbDrawable.getOpticalInsets();
            final Rect tempRect = mTempRect;
            thumbDrawable.copyBounds(tempRect);
            tempRect.offset(mPaddingLeft - mThumbOffset, mPaddingTop);
            tempRect.left += insets.left;
            tempRect.right -= insets.right;

            final int saveCount = canvas.save();
            canvas.clipRect(tempRect, Op.DIFFERENCE);
            super.drawTrack(canvas);
            drawTickMarks(canvas);
            canvas.restoreToCount(saveCount);
        } else {
            super.drawTrack(canvas);
            drawTickMarks(canvas);
        }
    }

    /**
     * @hide
     */
    protected void drawTickMarks(Canvas canvas) {
        if (mTickMark != null) {
            final int count = getMax() - getMin();
            if (count > 1) {
                final int w = mTickMark.getIntrinsicWidth();
                final int h = mTickMark.getIntrinsicHeight();
                final int halfW = w >= 0 ? w / 2 : 1;
                final int halfH = h >= 0 ? h / 2 : 1;
                mTickMark.setBounds(-halfW, -halfH, halfW, halfH);

                final float spacing = (getWidth() - mPaddingLeft - mPaddingRight) / (float) count;
                final int saveCount = canvas.save();
                canvas.translate(mPaddingLeft, getHeight() / 2);
                for (int i = 0; i <= count; i++) {
                    mTickMark.draw(canvas);
                    canvas.translate(spacing, 0);
                }
                canvas.restoreToCount(saveCount);
            }
        }
    }

    /**
     * Draw the thumb.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    void drawThumb(Canvas canvas) {
        if (mThumb != null) {
            final int saveCount = canvas.save();
            // Translate the padding. For the x, we need to allow the thumb to
            // draw in its extra space
            canvas.translate(mPaddingLeft - mThumbOffset, mPaddingTop);
            mThumb.draw(canvas);
            canvas.restoreToCount(saveCount);
        }
    }

    @Override
    protected synchronized void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        Drawable d = getCurrentDrawable();

        int thumbHeight = mThumb == null ? 0 : mThumb.getIntrinsicHeight();
        int dw = 0;
        int dh = 0;
        if (d != null) {
            dw = Math.max(mMinWidth, Math.min(mMaxWidth, d.getIntrinsicWidth()));
            dh = Math.max(mMinHeight, Math.min(mMaxHeight, d.getIntrinsicHeight()));
            dh = Math.max(thumbHeight, dh);
        }
        dw += mPaddingLeft + mPaddingRight;
        dh += mPaddingTop + mPaddingBottom;

        setMeasuredDimension(resolveSizeAndState(dw, widthMeasureSpec, 0),
                resolveSizeAndState(dh, heightMeasureSpec, 0));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!mIsUserSeekable || !isEnabled()) {
            return false;
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (mThumb != null) {
                    final int availableWidth = getWidth() - mPaddingLeft - mPaddingRight;
                    mTouchThumbOffset = (getProgress() - getMin()) / (float) (getMax()
                        - getMin()) - (event.getX() - mPaddingLeft) / availableWidth;
                    if (Math.abs(mTouchThumbOffset * availableWidth) > getThumbOffset()) {
                        mTouchThumbOffset = 0;
                    }
                }
                if (isInScrollingContainer()) {
                    mTouchDownX = event.getX();
                } else {
                    startDrag(event);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (mIsDragging) {
                    trackTouchEvent(event);
                } else {
                    final float x = event.getX();
                    if (Math.abs(x - mTouchDownX) > mScaledTouchSlop) {
                        startDrag(event);
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
                if (mIsDragging) {
                    trackTouchEvent(event);
                    onStopTrackingTouch();
                    setPressed(false);
                } else {
                    // Touch up when we never crossed the touch slop threshold should
                    // be interpreted as a tap-seek to that location.
                    onStartTrackingTouch();
                    trackTouchEvent(event);
                    onStopTrackingTouch();
                }
                // ProgressBar doesn't know to repaint the thumb drawable
                // in its inactive state when the touch stops (because the
                // value has not apparently changed)
                invalidate();
                break;

            case MotionEvent.ACTION_CANCEL:
                if (mIsDragging) {
                    onStopTrackingTouch();
                    setPressed(false);
                }
                invalidate(); // see above explanation
                break;
        }
        return true;
    }

    private void startDrag(MotionEvent event) {
        setPressed(true);

        if (mThumb != null) {
            // This may be within the padding region.
            invalidate(mThumb.getBounds());
        }

        onStartTrackingTouch();
        trackTouchEvent(event);
        attemptClaimDrag();
    }

    private void setHotspot(float x, float y) {
        final Drawable bg = getBackground();
        if (bg != null) {
            bg.setHotspot(x, y);
        }
    }

    @UnsupportedAppUsage
    private void trackTouchEvent(MotionEvent event) {
        final int x = Math.round(event.getX());
        final int y = Math.round(event.getY());
        final int width = getWidth();
        final int availableWidth = width - mPaddingLeft - mPaddingRight;

        final float scale;
        float progress = 0.0f;
        if (isLayoutRtl() && mMirrorForRtl) {
            if (x > width - mPaddingRight) {
                scale = 0.0f;
            } else if (x < mPaddingLeft) {
                scale = 1.0f;
            } else {
                scale = (availableWidth - x + mPaddingLeft) / (float) availableWidth
                    + mTouchThumbOffset;
                progress = mTouchProgressOffset;
            }
        } else {
            if (x < mPaddingLeft) {
                scale = 0.0f;
            } else if (x > width - mPaddingRight) {
                scale = 1.0f;
            } else {
                scale = (x - mPaddingLeft) / (float) availableWidth + mTouchThumbOffset;
                progress = mTouchProgressOffset;
            }
        }

        final int range = getMax() - getMin();
        progress += scale * range + getMin();

        setHotspot(x, y);
        setProgressInternal(Math.round(progress), true, false);
    }

    /**
     * Tries to claim the user's drag motion, and requests disallowing any
     * ancestors from stealing events in the drag.
     */
    private void attemptClaimDrag() {
        if (mParent != null) {
            mParent.requestDisallowInterceptTouchEvent(true);
        }
    }

    /**
     * This is called when the user has started touching this widget.
     */
    void onStartTrackingTouch() {
        mIsDragging = true;
    }

    /**
     * This is called when the user either releases their touch or the touch is
     * canceled.
     */
    void onStopTrackingTouch() {
        mIsDragging = false;
    }

    /**
     * Called when the user changes the seekbar's progress by using a key event.
     */
    void onKeyChange() {
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isEnabled()) {
            int increment = mKeyProgressIncrement;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_MINUS:
                    increment = -increment;
                    // fallthrough
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_PLUS:
                case KeyEvent.KEYCODE_EQUALS:
                    increment = isLayoutRtl() ? -increment : increment;

                    if (setProgressInternal(getProgress() + increment, true, true)) {
                        onKeyChange();
                        return true;
                    }
                    break;
            }
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return AbsSeekBar.class.getName();
    }

    /** @hide */
    @Override
    public void onInitializeAccessibilityNodeInfoInternal(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfoInternal(info);

        if (isEnabled()) {
            final int progress = getProgress();
            if (progress > getMin()) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            }
            if (progress < getMax()) {
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            }
        }
    }

    /** @hide */
    @Override
    public boolean performAccessibilityActionInternal(int action, Bundle arguments) {
        if (super.performAccessibilityActionInternal(action, arguments)) {
            return true;
        }

        if (!isEnabled()) {
            return false;
        }

        switch (action) {
            case R.id.accessibilityActionSetProgress: {
                if (!canUserSetProgress()) {
                    return false;
                }
                if (arguments == null || !arguments.containsKey(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) {
                    return false;
                }
                float value = arguments.getFloat(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);
                return setProgressInternal((int) value, true, true);
            }
            case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:
            case AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD: {
                if (!canUserSetProgress()) {
                    return false;
                }
                int range = getMax() - getMin();
                int increment = Math.max(1, Math.round((float) range / 20));
                if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                    increment = -increment;
                }

                // Let progress bar handle clamping values.
                if (setProgressInternal(getProgress() + increment, true, true)) {
                    onKeyChange();
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    /**
     * @return whether user can change progress on the view
     */
    boolean canUserSetProgress() {
        return !isIndeterminate() && isEnabled();
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);

        final Drawable thumb = mThumb;
        if (thumb != null) {
            setThumbPos(getWidth(), thumb, getScale(), Integer.MIN_VALUE);

            // Since we draw translated, the drawable's bounds that it signals
            // for invalidation won't be the actual bounds we want invalidated,
            // so just invalidate this whole view.
            invalidate();
        }
    }
}
