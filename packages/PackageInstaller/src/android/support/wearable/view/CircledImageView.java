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
 * limitations under the License.
 */

package androidx.wear.ble.view;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

import java.util.Objects;
import com.android.packageinstaller.R;

import com.android.packageinstaller.R;

/**
 * An image view surrounded by a circle.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class CircledImageView extends View {

    private static final ArgbEvaluator ARGB_EVALUATOR = new ArgbEvaluator();

    private Drawable mDrawable;

    private final RectF mOval;
    private final Paint mPaint;

    private ColorStateList mCircleColor;

    private float mCircleRadius;
    private float mCircleRadiusPercent;

    private float mCircleRadiusPressed;
    private float mCircleRadiusPressedPercent;

    private float mRadiusInset;

    private int mCircleBorderColor;

    private float mCircleBorderWidth;
    private float mProgress = 1f;
    private final float mShadowWidth;

    private float mShadowVisibility;
    private boolean mCircleHidden = false;

    private float mInitialCircleRadius;

    private boolean mPressed = false;

    private boolean mProgressIndeterminate;
    private ProgressDrawable mIndeterminateDrawable;
    private Rect mIndeterminateBounds = new Rect();
    private long mColorChangeAnimationDurationMs = 0;

    private float mImageCirclePercentage = 1f;
    private float mImageHorizontalOffcenterPercentage = 0f;
    private Integer mImageTint;

    private final Drawable.Callback mDrawableCallback = new Drawable.Callback() {
        @Override
        public void invalidateDrawable(Drawable drawable) {
            invalidate();
        }

        @Override
        public void scheduleDrawable(Drawable drawable, Runnable runnable, long l) {
            // Not needed.
        }

        @Override
        public void unscheduleDrawable(Drawable drawable, Runnable runnable) {
            // Not needed.
        }
    };

    private int mCurrentColor;

    private final AnimatorUpdateListener mAnimationListener = new AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            int color = (int) animation.getAnimatedValue();
            if (color != CircledImageView.this.mCurrentColor) {
                CircledImageView.this.mCurrentColor = color;
                CircledImageView.this.invalidate();
            }
        }
    };

    private ValueAnimator mColorAnimator;

    public CircledImageView(Context context) {
        this(context, null);
    }

    public CircledImageView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CircledImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        TypedArray a = getContext().obtainStyledAttributes(attrs, R.styleable.CircledImageView);
        mDrawable = a.getDrawable(R.styleable.CircledImageView_android_src);

        mCircleColor = a.getColorStateList(R.styleable.CircledImageView_circle_color);
        if (mCircleColor == null) {
            mCircleColor = ColorStateList.valueOf(android.R.color.darker_gray);
        }

        mCircleRadius = a.getDimension(
                R.styleable.CircledImageView_circle_radius, 0);
        mInitialCircleRadius = mCircleRadius;
        mCircleRadiusPressed = a.getDimension(
                R.styleable.CircledImageView_circle_radius_pressed, mCircleRadius);
        mCircleBorderColor = a.getColor(
                R.styleable.CircledImageView_circle_border_color, Color.BLACK);
        mCircleBorderWidth = a.getDimension(R.styleable.CircledImageView_circle_border_width, 0);

        if (mCircleBorderWidth > 0) {
            mRadiusInset += mCircleBorderWidth;
        }

        float circlePadding = a.getDimension(R.styleable.CircledImageView_circle_padding, 0);
        if (circlePadding > 0) {
            mRadiusInset += circlePadding;
        }
        mShadowWidth = a.getDimension(R.styleable.CircledImageView_shadow_width, 0);

        mImageCirclePercentage = a.getFloat(
                R.styleable.CircledImageView_image_circle_percentage, 0f);

        mImageHorizontalOffcenterPercentage = a.getFloat(
                R.styleable.CircledImageView_image_horizontal_offcenter_percentage, 0f);

        if (a.hasValue(R.styleable.CircledImageView_image_tint)) {
            mImageTint = a.getColor(R.styleable.CircledImageView_image_tint, 0);
        }

        mCircleRadiusPercent = a.getFraction(R.styleable.CircledImageView_circle_radius_percent,
                1, 1, 0f);

        mCircleRadiusPressedPercent = a.getFraction(
                R.styleable.CircledImageView_circle_radius_pressed_percent, 1, 1,
                mCircleRadiusPercent);

        a.recycle();

        mOval = new RectF();
        mPaint = new Paint();
        mPaint.setAntiAlias(true);

        mIndeterminateDrawable = new ProgressDrawable();
        // {@link #mDrawableCallback} must be retained as a member, as Drawable callback
        // is held by weak reference, we must retain it for it to continue to be called.
        mIndeterminateDrawable.setCallback(mDrawableCallback);

        setWillNotDraw(false);

        setColorForCurrentState();
    }

    public void setCircleHidden(boolean circleHidden) {
        if (circleHidden != mCircleHidden) {
            mCircleHidden = circleHidden;
            invalidate();
        }
    }


    @Override
    protected boolean onSetAlpha(int alpha) {
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int paddingLeft = getPaddingLeft();
        int paddingTop = getPaddingTop();


        float circleRadius = mPressed ? getCircleRadiusPressed() : getCircleRadius();
        if (mShadowWidth > 0 && mShadowVisibility > 0) {
            // First let's find the center of the view.
            mOval.set(paddingLeft, paddingTop, getWidth() - getPaddingRight(),
                    getHeight() - getPaddingBottom());
            // Having the center, lets make the shadow start beyond the circled and possibly the
            // border.
            final float radius = circleRadius + mCircleBorderWidth +
                    mShadowWidth * mShadowVisibility;
            mPaint.setColor(Color.BLACK);
            mPaint.setAlpha(Math.round(mPaint.getAlpha() * getAlpha()));
            mPaint.setStyle(Style.FILL);
            // TODO: precalc and pre-allocate this
            mPaint.setShader(new RadialGradient(mOval.centerX(), mOval.centerY(), radius,
                    new int[]{Color.BLACK, Color.TRANSPARENT}, new float[]{0.6f, 1f},
                    Shader.TileMode.MIRROR));
            canvas.drawCircle(mOval.centerX(), mOval.centerY(), radius, mPaint);
            mPaint.setShader(null);
        }
        if (mCircleBorderWidth > 0) {
            // First let's find the center of the view.
            mOval.set(paddingLeft, paddingTop, getWidth() - getPaddingRight(),
                    getHeight() - getPaddingBottom());
            // Having the center, lets make the border meet the circle.
            mOval.set(mOval.centerX() - circleRadius, mOval.centerY() - circleRadius,
                    mOval.centerX() + circleRadius, mOval.centerY() + circleRadius);
            mPaint.setColor(mCircleBorderColor);
            // {@link #Paint.setAlpha} is a helper method that just sets the alpha portion of the
            // color. {@link #Paint.setPaint} will clear any previously set alpha value.
            mPaint.setAlpha(Math.round(mPaint.getAlpha() * getAlpha()));
            mPaint.setStyle(Style.STROKE);
            mPaint.setStrokeWidth(mCircleBorderWidth);

            if (mProgressIndeterminate) {
                mOval.roundOut(mIndeterminateBounds);
                mIndeterminateDrawable.setBounds(mIndeterminateBounds);
                mIndeterminateDrawable.setRingColor(mCircleBorderColor);
                mIndeterminateDrawable.setRingWidth(mCircleBorderWidth);
                mIndeterminateDrawable.draw(canvas);
            } else {
                canvas.drawArc(mOval, -90, 360 * mProgress, false, mPaint);
            }
        }
        if (!mCircleHidden) {
            mOval.set(paddingLeft, paddingTop, getWidth() - getPaddingRight(),
                    getHeight() - getPaddingBottom());
            // {@link #Paint.setAlpha} is a helper method that just sets the alpha portion of the
            // color. {@link #Paint.setPaint} will clear any previously set alpha value.
            mPaint.setColor(mCurrentColor);
            mPaint.setAlpha(Math.round(mPaint.getAlpha() * getAlpha()));

            mPaint.setStyle(Style.FILL);
            float centerX = mOval.centerX();
            float centerY = mOval.centerY();

            canvas.drawCircle(centerX, centerY, circleRadius, mPaint);
        }

        if (mDrawable != null) {
            mDrawable.setAlpha(Math.round(getAlpha() * 255));

            if (mImageTint != null) {
                mDrawable.setTint(mImageTint);
            }
            mDrawable.draw(canvas);
        }

        super.onDraw(canvas);
    }

    private void setColorForCurrentState() {
        int newColor = mCircleColor.getColorForState(getDrawableState(),
                mCircleColor.getDefaultColor());
        if (mColorChangeAnimationDurationMs > 0) {
            if (mColorAnimator != null) {
                mColorAnimator.cancel();
            } else {
                mColorAnimator = new ValueAnimator();
            }
            mColorAnimator.setIntValues(new int[] {
                    mCurrentColor, newColor });
            mColorAnimator.setEvaluator(ARGB_EVALUATOR);
            mColorAnimator.setDuration(mColorChangeAnimationDurationMs);
            mColorAnimator.addUpdateListener(this.mAnimationListener);
            mColorAnimator.start();
        } else {
            if (newColor != mCurrentColor) {
                mCurrentColor = newColor;
                invalidate();
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {

        final float radius = getCircleRadius() + mCircleBorderWidth +
                mShadowWidth * mShadowVisibility;
        float desiredWidth = radius * 2;
        float desiredHeight = radius * 2;

        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        int width;
        int height;

        if (widthMode == MeasureSpec.EXACTLY) {
            width = widthSize;
        } else if (widthMode == MeasureSpec.AT_MOST) {
            width = (int) Math.min(desiredWidth, widthSize);
        } else {
            width = (int) desiredWidth;
        }

        if (heightMode == MeasureSpec.EXACTLY) {
            height = heightSize;
        } else if (heightMode == MeasureSpec.AT_MOST) {
            height = (int) Math.min(desiredHeight, heightSize);
        } else {
            height = (int) desiredHeight;
        }

        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (mDrawable != null) {
            // Retrieve the sizes of the drawable and the view.
            final int nativeDrawableWidth = mDrawable.getIntrinsicWidth();
            final int nativeDrawableHeight = mDrawable.getIntrinsicHeight();
            final int viewWidth = getMeasuredWidth();
            final int viewHeight = getMeasuredHeight();
            final float imageCirclePercentage = mImageCirclePercentage > 0
                    ? mImageCirclePercentage : 1;

            final float scaleFactor = Math.min(1f,
                    Math.min(
                            (float) nativeDrawableWidth != 0
                                    ? imageCirclePercentage * viewWidth / nativeDrawableWidth : 1,
                            (float) nativeDrawableHeight != 0
                                    ? imageCirclePercentage
                                        * viewHeight / nativeDrawableHeight : 1));

            // Scale the drawable down to fit the view, if needed.
            final int drawableWidth = Math.round(scaleFactor * nativeDrawableWidth);
            final int drawableHeight = Math.round(scaleFactor * nativeDrawableHeight);

            // Center the drawable within the view.
            final int drawableLeft = (viewWidth - drawableWidth) / 2
                    + Math.round(mImageHorizontalOffcenterPercentage * drawableWidth);
            final int drawableTop = (viewHeight - drawableHeight) / 2;

            mDrawable.setBounds(drawableLeft, drawableTop, drawableLeft + drawableWidth,
                    drawableTop + drawableHeight);
        }

        super.onLayout(changed, left, top, right, bottom);
    }

    public void setImageDrawable(Drawable drawable) {
        if (drawable != mDrawable) {
            final Drawable existingDrawable = mDrawable;
            mDrawable = drawable;

            final boolean skipLayout = drawable != null
                    && existingDrawable != null
                    && existingDrawable.getIntrinsicHeight() == drawable.getIntrinsicHeight()
                    && existingDrawable.getIntrinsicWidth() == drawable.getIntrinsicWidth();

            if (skipLayout) {
                mDrawable.setBounds(existingDrawable.getBounds());
            } else {
                requestLayout();
            }

            invalidate();
        }
    }

    public void setImageResource(int resId) {
        setImageDrawable(resId == 0 ? null : getContext().getDrawable(resId));
    }

    public void setImageCirclePercentage(float percentage) {
        float clamped = Math.max(0, Math.min(1, percentage));
        if (clamped != mImageCirclePercentage) {
            mImageCirclePercentage = clamped;
            invalidate();
        }
    }

    public void setImageHorizontalOffcenterPercentage(float percentage) {
        if (percentage != mImageHorizontalOffcenterPercentage) {
            mImageHorizontalOffcenterPercentage = percentage;
            invalidate();
        }
    }

    public void setImageTint(int tint) {
        if (tint != mImageTint) {
            mImageTint = tint;
            invalidate();
        }
    }

    public float getCircleRadius() {
        float radius = mCircleRadius;
        if (mCircleRadius <= 0 && mCircleRadiusPercent > 0) {
            radius = Math.max(getMeasuredHeight(), getMeasuredWidth()) * mCircleRadiusPercent;
        }

        return radius - mRadiusInset;
    }

    public float getCircleRadiusPercent() {
        return mCircleRadiusPercent;
    }

    public float getCircleRadiusPressed() {
        float radius = mCircleRadiusPressed;

        if (mCircleRadiusPressed <= 0 && mCircleRadiusPressedPercent > 0) {
            radius = Math.max(getMeasuredHeight(), getMeasuredWidth())
                    * mCircleRadiusPressedPercent;
        }

        return radius - mRadiusInset;
    }

    public float getCircleRadiusPressedPercent() {
        return mCircleRadiusPressedPercent;
    }

    public void setCircleRadius(float circleRadius) {
        if (circleRadius != mCircleRadius) {
            mCircleRadius = circleRadius;
            invalidate();
        }
    }

    /**
     * Sets the radius of the circle to be a percentage of the largest dimension of the view.
     * @param circleRadiusPercent A {@code float} from 0 to 1 representing the radius percentage.
     */
    public void setCircleRadiusPercent(float circleRadiusPercent) {
        if (circleRadiusPercent != mCircleRadiusPercent) {
            mCircleRadiusPercent = circleRadiusPercent;
            invalidate();
        }
    }

    public void setCircleRadiusPressed(float circleRadiusPressed) {
        if (circleRadiusPressed != mCircleRadiusPressed) {
            mCircleRadiusPressed = circleRadiusPressed;
            invalidate();
        }
    }

    /**
     * Sets the radius of the circle to be a percentage of the largest dimension of the view when
     * pressed.
     * @param circleRadiusPressedPercent A {@code float} from 0 to 1 representing the radius
     *                                   percentage.
     */
    public void setCircleRadiusPressedPercent(float circleRadiusPressedPercent) {
        if (circleRadiusPressedPercent  != mCircleRadiusPressedPercent) {
            mCircleRadiusPressedPercent = circleRadiusPressedPercent;
            invalidate();
        }
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        setColorForCurrentState();
    }

    public void setCircleColor(int circleColor) {
        setCircleColorStateList(ColorStateList.valueOf(circleColor));
    }

    public void setCircleColorStateList(ColorStateList circleColor) {
        if (!Objects.equals(circleColor, mCircleColor)) {
            mCircleColor = circleColor;
            setColorForCurrentState();
            invalidate();
        }
    }

    public ColorStateList getCircleColorStateList() {
        return mCircleColor;
    }

    public int getDefaultCircleColor() {
        return mCircleColor.getDefaultColor();
    }

    /**
     * Show the circle border as an indeterminate progress spinner.
     * The views circle border width and color must be set for this to have an effect.
     *
     * @param show true if the progress spinner is shown, false to hide it.
     */
    public void showIndeterminateProgress(boolean show) {
        mProgressIndeterminate = show;
        if (show) {
            mIndeterminateDrawable.startAnimation();
        } else {
            mIndeterminateDrawable.stopAnimation();
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != View.VISIBLE) {
            showIndeterminateProgress(false);
        } else if (mProgressIndeterminate) {
            showIndeterminateProgress(true);
        }
    }

    public void setProgress(float progress) {
        if (progress != mProgress) {
            mProgress = progress;
            invalidate();
        }
    }

    /**
     * Set how much of the shadow should be shown.
     * @param shadowVisibility Value between 0 and 1.
     */
    public void setShadowVisibility(float shadowVisibility) {
        if (shadowVisibility != mShadowVisibility) {
            mShadowVisibility = shadowVisibility;
            invalidate();
        }
    }

    public float getInitialCircleRadius() {
        return mInitialCircleRadius;
    }

    public void setCircleBorderColor(int circleBorderColor) {
        mCircleBorderColor = circleBorderColor;
    }

    /**
     * Set the border around the circle.
     * @param circleBorderWidth Width of the border around the circle.
     */
    public void setCircleBorderWidth(float circleBorderWidth) {
        if (circleBorderWidth != mCircleBorderWidth) {
            mCircleBorderWidth = circleBorderWidth;
            invalidate();
        }
    }

    @Override
    public void setPressed(boolean pressed) {
        super.setPressed(pressed);
        if (pressed != mPressed) {
            mPressed = pressed;
            invalidate();
        }
    }

    public Drawable getImageDrawable() {
        return mDrawable;
    }

    /**
     * @return the milliseconds duration of the transition animation when the color changes.
     */
    public long getColorChangeAnimationDuration() {
        return mColorChangeAnimationDurationMs;
    }

    /**
     * @param mColorChangeAnimationDurationMs the milliseconds duration of the color change
     *            animation. The color change animation will run if the color changes with {@link #setCircleColor}
     *            or as a result of the active state changing.
     */
    public void setColorChangeAnimationDuration(long mColorChangeAnimationDurationMs) {
        this.mColorChangeAnimationDurationMs = mColorChangeAnimationDurationMs;
    }
}
