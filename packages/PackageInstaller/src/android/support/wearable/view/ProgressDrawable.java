/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package androidx.wear.ble.view;

import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.annotation.TargetApi;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Property;
import android.view.animation.LinearInterpolator;

/**
 * Drawable for showing an indeterminate progress indicator.
 *
 * TODO: When Material progress drawable is available in the support library stop using this.
 *
 * @hide
 */
@TargetApi(Build.VERSION_CODES.KITKAT_WATCH)
class ProgressDrawable extends Drawable {

    private static Property<ProgressDrawable, Integer> LEVEL =
            new Property<ProgressDrawable, Integer>(Integer.class, "level") {
        @Override
        public Integer get(ProgressDrawable drawable) {
            return drawable.getLevel();
        }

        @Override
        public void set(ProgressDrawable drawable, Integer value) {
            drawable.setLevel(value);
            drawable.invalidateSelf();
        }
    };
    /** Max level for a level drawable, as specified in developer docs for {@link Drawable}. */
    private static final int MAX_LEVEL = 10000;

    /** How many different sections are there, five gives us the material style star. **/
    private static final int NUMBER_OF_SEGMENTS = 5;

    private static final int LEVELS_PER_SEGMENT = MAX_LEVEL / NUMBER_OF_SEGMENTS;
    private static final float STARTING_ANGLE = -90f;
    private static final long ANIMATION_DURATION = 6000;
    private static final int FULL_CIRCLE = 360;
    private static final int MAX_SWEEP = 306;
    private static final int CORRECTION_ANGLE = FULL_CIRCLE - MAX_SWEEP;
    /** How far through each cycle does the bar stop growing and start shrinking, half way. **/
    private static final float GROW_SHRINK_RATIO = 0.5f;
    // TODO: replace this with BakedBezierInterpolator when its available in support library.
    private static final TimeInterpolator mInterpolator = Gusterpolator.INSTANCE;

    private final RectF mInnerCircleBounds = new RectF();
    private final Paint mPaint = new Paint();
    private final ObjectAnimator mAnimator;
    private float mCircleBorderWidth;
    private int mCircleBorderColor;

    public ProgressDrawable() {
        mPaint.setAntiAlias(true);
        mPaint.setStyle(Paint.Style.STROKE);
        mAnimator = ObjectAnimator.ofInt(this, LEVEL, 0, MAX_LEVEL);
        mAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mAnimator.setRepeatMode(ValueAnimator.RESTART);
        mAnimator.setDuration(ANIMATION_DURATION);
        mAnimator.setInterpolator(new LinearInterpolator());
    }

    public void setRingColor(int color) {
        mCircleBorderColor = color;
    }

    public void setRingWidth(float width) {
        mCircleBorderWidth = width;
    }

    public void startAnimation() {
        mAnimator.start();
    }

    public void stopAnimation() {
        mAnimator.cancel();
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.save();
        mInnerCircleBounds.set(getBounds());
        mInnerCircleBounds.inset(mCircleBorderWidth / 2.0f, mCircleBorderWidth / 2.0f);
        mPaint.setStrokeWidth(mCircleBorderWidth);
        mPaint.setColor(mCircleBorderColor);

        float sweepAngle = FULL_CIRCLE;
        boolean growing = false;
        float correctionAngle = 0;
        int level = getLevel();

        int currentSegment = level / LEVELS_PER_SEGMENT;
        int offset = currentSegment * LEVELS_PER_SEGMENT;
        float progress = (level - offset) / (float) LEVELS_PER_SEGMENT;

        growing = progress < GROW_SHRINK_RATIO;
        correctionAngle = CORRECTION_ANGLE * progress;

        if (growing) {
            sweepAngle = MAX_SWEEP * mInterpolator.getInterpolation(
                    lerpInv(0f, GROW_SHRINK_RATIO, progress));
        } else {
            sweepAngle = MAX_SWEEP * (1.0f - mInterpolator.getInterpolation(
                    lerpInv(GROW_SHRINK_RATIO, 1.0f, progress)));
        }

        sweepAngle = Math.max(1, sweepAngle);

        canvas.rotate(
                level * (1.0f / MAX_LEVEL) * 2 * FULL_CIRCLE + STARTING_ANGLE + correctionAngle,
                mInnerCircleBounds.centerX(),
                mInnerCircleBounds.centerY());
        canvas.drawArc(mInnerCircleBounds,
                growing ? 0 : MAX_SWEEP - sweepAngle,
                sweepAngle,
                false,
                mPaint);
        canvas.restore();
    }

    @Override
    public void setAlpha(int i) {
        // Not supported.
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // Not supported.
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }

    @Override
    protected boolean onLevelChange(int level) {
        return true; // Changing the level of this drawable does change its appearance.
    }

    /**
     * Returns the interpolation scalar (s) that satisfies the equation:
     * {@code value = }lerp(a, b, s)
     *
     * <p>If {@code a == b}, then this function will return 0.
     */
    private static float lerpInv(float a, float b, float value) {
        return a != b ? ((value - a) / (b - a)) : 0.0f;
    }
}
