/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.graphics.drawable;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Paint.Cap;
import android.graphics.Paint.Style;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Fancy progress indicator for Material theme.
 *
 * TODO: Replace this class with something less ridiculous.
 */
class MaterialProgressDrawable extends Drawable implements Animatable {
    private static final TimeInterpolator LINEAR_INTERPOLATOR = new LinearInterpolator();
    private static final TimeInterpolator END_CURVE_INTERPOLATOR = new EndCurveInterpolator();
    private static final TimeInterpolator START_CURVE_INTERPOLATOR = new StartCurveInterpolator();

    /** The duration of a single progress spin in milliseconds. */
    private static final int ANIMATION_DURATION = 1000 * 80 / 60;

    /** The number of points in the progress "star". */
    private static final int NUM_POINTS = 5;

    /** The list of animators operating on this drawable. */
    private final ArrayList<Animator> mAnimators = new ArrayList<Animator>();

    /** The indicator ring, used to manage animation state. */
    private final Ring mRing;

    private MaterialProgressState mState;

    /** Canvas rotation in degrees. */
    private float mRotation;

    private boolean mMutated;

    public MaterialProgressDrawable() {
        this(new MaterialProgressState(null), null);
    }

    private MaterialProgressDrawable(MaterialProgressState state, Theme theme) {
        mState = state;
        if (theme != null && state.canApplyTheme()) {
            applyTheme(theme);
        }

        mRing = new Ring(mCallback);
        mMutated = false;

        initializeFromState();
        setupAnimators();
    }

    private void initializeFromState() {
        final MaterialProgressState state = mState;

        final Ring ring = mRing;
        ring.setStrokeWidth(state.mStrokeWidth);

        final int color = state.mColor.getColorForState(getState(), Color.TRANSPARENT);
        ring.setColor(color);

        final float minEdge = Math.min(state.mWidth, state.mHeight);
        if (state.mInnerRadius <= 0 || minEdge < 0) {
            ring.setInsets((int) Math.ceil(state.mStrokeWidth / 2.0f));
        } else {
            float insets = minEdge / 2.0f - state.mInnerRadius;
            ring.setInsets(insets);
        }
    }

    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mState = new MaterialProgressState(mState);
            mMutated = true;
        }
        return this;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean changed = super.onStateChange(state);

        final int color = mState.mColor.getColorForState(state, Color.TRANSPARENT);
        if (color != mRing.getColor()) {
            mRing.setColor(color);
            changed = true;
        }

        return changed;
    }

    @Override
    public boolean isStateful() {
        return super.isStateful() || mState.mColor.isStateful();
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.MaterialProgressDrawable);
        super.inflateWithAttributes(r, parser, a, R.styleable.MaterialProgressDrawable_visible);
        updateStateFromTypedArray(a);
        a.recycle();

        initializeFromState();
    }

    @Override
    public void applyTheme(Theme t) {
        final TypedArray a = t.resolveAttributes(mState.mThemeAttrs,
                R.styleable.MaterialProgressDrawable);
        updateStateFromTypedArray(a);
        a.recycle();
    }

    private void updateStateFromTypedArray(TypedArray a) {
        final MaterialProgressState state = mState;
        state.mThemeAttrs = a.extractThemeAttrs();
        state.mWidth = a.getDimensionPixelSize(
                R.styleable.MaterialProgressDrawable_width, state.mWidth);
        state.mHeight = a.getDimensionPixelSize(
                R.styleable.MaterialProgressDrawable_height, state.mHeight);
        state.mInnerRadius = a.getDimension(
                R.styleable.MaterialProgressDrawable_innerRadius, state.mInnerRadius);
        state.mStrokeWidth = a.getDimension(
                R.styleable.MaterialProgressDrawable_thickness, state.mStrokeWidth);

        if (a.hasValue(R.styleable.MaterialProgressDrawable_color)) {
            state.mColor = a.getColorStateList(R.styleable.MaterialProgressDrawable_color);
        }
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (visible) {
            if (changed || restart) {
                start();
            }
        } else {
            stop();
        }
        return changed;
    }

    @Override
    public int getIntrinsicHeight() {
        return mState.mHeight;
    }

    @Override
    public int getIntrinsicWidth() {
        return mState.mWidth;
    }

    @Override
    public void draw(Canvas c) {
        final Rect bounds = getBounds();
        final int saveCount = c.save();
        c.rotate(mRotation, bounds.exactCenterX(), bounds.exactCenterY());
        mRing.draw(c, bounds);
        c.restoreToCount(saveCount);
    }

    @Override
    public void setAlpha(int alpha) {
        mRing.setAlpha(alpha);
    }

    @Override
    public int getAlpha() {
        return mRing.getAlpha();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mRing.setColorFilter(colorFilter);
    }

    @Override
    public ColorFilter getColorFilter() {
        return mRing.getColorFilter();
    }

    private void setRotation(float rotation) {
        mRotation = rotation;
        invalidateSelf();
    }

    private float getRotation() {
        return mRotation;
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public boolean isRunning() {
        final ArrayList<Animator> animators = mAnimators;
        final int N = animators.size();
        for (int i = 0; i < N; i++) {
            final Animator animator = animators.get(i);
            if (animator.isRunning()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void start() {
        final ArrayList<Animator> animators = mAnimators;
        final int N = animators.size();
        for (int i = 0; i < N; i++) {
            final Animator animator = animators.get(i);
            if (animator.isPaused()) {
                animator.resume();
            } else if (!animator.isRunning()){
                animator.start();
            }
        }
    }

    @Override
    public void stop() {
        final ArrayList<Animator> animators = mAnimators;
        final int N = animators.size();
        for (int i = 0; i < N; i++) {
            final Animator animator = animators.get(i);
            animator.pause();
        }
    }

    private void setupAnimators() {
        final Ring ring = mRing;

        final ObjectAnimator endTrim = ObjectAnimator.ofFloat(ring, "endTrim", 0, 0.75f);
        endTrim.setDuration(ANIMATION_DURATION);
        endTrim.setInterpolator(START_CURVE_INTERPOLATOR);
        endTrim.setRepeatCount(ObjectAnimator.INFINITE);
        endTrim.setRepeatMode(ObjectAnimator.RESTART);

        final ObjectAnimator startTrim = ObjectAnimator.ofFloat(ring, "startTrim", 0.0f, 0.75f);
        startTrim.setDuration(ANIMATION_DURATION);
        startTrim.setInterpolator(END_CURVE_INTERPOLATOR);
        startTrim.setRepeatCount(ObjectAnimator.INFINITE);
        startTrim.setRepeatMode(ObjectAnimator.RESTART);

        final ObjectAnimator rotation = ObjectAnimator.ofFloat(ring, "rotation", 0.0f, 0.25f);
        rotation.setDuration(ANIMATION_DURATION);
        rotation.setInterpolator(LINEAR_INTERPOLATOR);
        rotation.setRepeatCount(ObjectAnimator.INFINITE);
        rotation.setRepeatMode(ObjectAnimator.RESTART);

        final ObjectAnimator groupRotation = ObjectAnimator.ofFloat(this, "rotation", 0.0f, 360.0f);
        groupRotation.setDuration(NUM_POINTS * ANIMATION_DURATION);
        groupRotation.setInterpolator(LINEAR_INTERPOLATOR);
        groupRotation.setRepeatCount(ObjectAnimator.INFINITE);
        groupRotation.setRepeatMode(ObjectAnimator.RESTART);

        mAnimators.add(endTrim);
        mAnimators.add(startTrim);
        mAnimators.add(rotation);
        mAnimators.add(groupRotation);
    }

    private final Callback mCallback = new Callback() {
        @Override
        public void invalidateDrawable(Drawable d) {
            invalidateSelf();
        }

        @Override
        public void scheduleDrawable(Drawable d, Runnable what, long when) {
            scheduleSelf(what, when);
        }

        @Override
        public void unscheduleDrawable(Drawable d, Runnable what) {
            unscheduleSelf(what);
        }
    };

    private static class MaterialProgressState extends ConstantState {
        private int[] mThemeAttrs = null;
        private float mStrokeWidth = 5.0f;
        private float mInnerRadius = -1.0f;
        private int mWidth = -1;
        private int mHeight = -1;
        private ColorStateList mColor = ColorStateList.valueOf(Color.TRANSPARENT);

        public MaterialProgressState(MaterialProgressState orig) {
            if (orig != null) {
                mThemeAttrs = orig.mThemeAttrs;
                mStrokeWidth = orig.mStrokeWidth;
                mInnerRadius = orig.mInnerRadius;
                mWidth = orig.mWidth;
                mHeight = orig.mHeight;
                mColor = orig.mColor;
            }
        }

        @Override
        public boolean canApplyTheme() {
            return mThemeAttrs != null;
        }

        @Override
        public Drawable newDrawable() {
            return newDrawable(null, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return newDrawable(res, null);
        }

        @Override
        public Drawable newDrawable(Resources res, Theme theme) {
            return new MaterialProgressDrawable(this, theme);
        }

        @Override
        public int getChangingConfigurations() {
            return 0;
        }
    }

    private static class Ring {
        private final RectF mTempBounds = new RectF();
        private final Paint mPaint = new Paint();

        private final Callback mCallback;

        private float mStartTrim = 0.0f;
        private float mEndTrim = 0.0f;
        private float mRotation = 0.0f;
        private float mStrokeWidth = 5.0f;
        private float mStrokeInset = 2.5f;

        private int mAlpha = 0xFF;
        private int mColor = Color.BLACK;

        public Ring(Callback callback) {
            mCallback = callback;

            mPaint.setStrokeCap(Cap.ROUND);
            mPaint.setAntiAlias(true);
            mPaint.setStyle(Style.STROKE);
        }

        public void draw(Canvas c, Rect bounds) {
            final RectF arcBounds = mTempBounds;
            arcBounds.set(bounds);
            arcBounds.inset(mStrokeInset, mStrokeInset);

            final float startAngle = (mStartTrim + mRotation) * 360;
            final float endAngle = (mEndTrim + mRotation) * 360;
            float sweepAngle = endAngle - startAngle;

            // Ensure the sweep angle isn't too small to draw.
            final float diameter = Math.min(arcBounds.width(), arcBounds.height());
            final float minAngle = (float) (360.0 / (diameter * Math.PI));
            if (sweepAngle < minAngle && sweepAngle > -minAngle) {
                sweepAngle = Math.signum(sweepAngle) * minAngle;
            }

            c.drawArc(arcBounds, startAngle, sweepAngle, false, mPaint);
        }

        public void setColorFilter(ColorFilter filter) {
            mPaint.setColorFilter(filter);
            invalidateSelf();
        }

        public ColorFilter getColorFilter() {
            return mPaint.getColorFilter();
        }

        public void setAlpha(int alpha) {
            mAlpha = alpha;
            mPaint.setColor(mColor & 0xFFFFFF | alpha << 24);
            invalidateSelf();
        }

        public int getAlpha() {
            return mAlpha;
        }

        public void setColor(int color) {
            mColor = color;
            mPaint.setColor(color & 0xFFFFFF | mAlpha << 24);
            invalidateSelf();
        }

        public int getColor() {
            return mColor;
        }

        public void setStrokeWidth(float strokeWidth) {
            mStrokeWidth = strokeWidth;
            mPaint.setStrokeWidth(strokeWidth);
            invalidateSelf();
        }

        @SuppressWarnings("unused")
        public float getStrokeWidth() {
            return mStrokeWidth;
        }

        @SuppressWarnings("unused")
        public void setStartTrim(float startTrim) {
            mStartTrim = startTrim;
            invalidateSelf();
        }

        @SuppressWarnings("unused")
        public float getStartTrim() {
            return mStartTrim;
        }

        @SuppressWarnings("unused")
        public void setEndTrim(float endTrim) {
            mEndTrim = endTrim;
            invalidateSelf();
        }

        @SuppressWarnings("unused")
        public float getEndTrim() {
            return mEndTrim;
        }

        @SuppressWarnings("unused")
        public void setRotation(float rotation) {
            mRotation = rotation;
            invalidateSelf();
        }

        @SuppressWarnings("unused")
        public float getRotation() {
            return mRotation;
        }

        public void setInsets(float insets) {
            mStrokeInset = insets;
        }

        @SuppressWarnings("unused")
        public float getInsets() {
            return mStrokeInset;
        }

        private void invalidateSelf() {
            mCallback.invalidateDrawable(null);
        }
    }

    /**
     * Squishes the interpolation curve into the second half of the animation.
     */
    private static class EndCurveInterpolator extends AccelerateDecelerateInterpolator {
        @Override
        public float getInterpolation(float input) {
            return super.getInterpolation(Math.max(0, (input - 0.5f) * 2.0f));
        }
    }

    /**
     * Squishes the interpolation curve into the first half of the animation.
     */
    private static class StartCurveInterpolator extends AccelerateDecelerateInterpolator {
        @Override
        public float getInterpolation(float input) {
            return super.getInterpolation(Math.min(1, input * 2.0f));
        }
    }
}
