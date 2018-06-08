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
 * limitations under the License
 */

package com.android.systemui.statusbar.policy;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.CanvasProperty;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.SystemProperties;
import android.view.DisplayListCanvas;
import android.view.RenderNodeAnimator;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.Interpolator;

import com.android.systemui.Interpolators;
import com.android.systemui.R;

import java.util.ArrayList;
import java.util.HashSet;

public class KeyButtonRipple extends Drawable {

    private static final float GLOW_MAX_SCALE_FACTOR = 1.35f;
    private static final float GLOW_MAX_ALPHA = 0.2f;
    private static final float GLOW_MAX_ALPHA_DARK = 0.1f;
    private static final int ANIMATION_DURATION_SCALE = 350;
    private static final int ANIMATION_DURATION_FADE = 450;

    private Paint mRipplePaint;
    private CanvasProperty<Float> mLeftProp;
    private CanvasProperty<Float> mTopProp;
    private CanvasProperty<Float> mRightProp;
    private CanvasProperty<Float> mBottomProp;
    private CanvasProperty<Float> mRxProp;
    private CanvasProperty<Float> mRyProp;
    private CanvasProperty<Paint> mPaintProp;
    private float mGlowAlpha = 0f;
    private float mGlowScale = 1f;
    private boolean mPressed;
    private boolean mVisible;
    private boolean mDrawingHardwareGlow;
    private int mMaxWidth;
    private boolean mLastDark;
    private boolean mDark;
    private boolean mDelayTouchFeedback;

    private final Interpolator mInterpolator = new LogInterpolator();
    private boolean mSupportHardware;
    private final View mTargetView;
    private final Handler mHandler = new Handler();

    private final HashSet<Animator> mRunningAnimations = new HashSet<>();
    private final ArrayList<Animator> mTmpArray = new ArrayList<>();

    public KeyButtonRipple(Context ctx, View targetView) {
        mMaxWidth =  ctx.getResources().getDimensionPixelSize(R.dimen.key_button_ripple_max_width);
        mTargetView = targetView;
    }

    public void setDarkIntensity(float darkIntensity) {
        mDark = darkIntensity >= 0.5f;
    }

    public void setDelayTouchFeedback(boolean delay) {
        mDelayTouchFeedback = delay;
    }

    private Paint getRipplePaint() {
        if (mRipplePaint == null) {
            mRipplePaint = new Paint();
            mRipplePaint.setAntiAlias(true);
            mRipplePaint.setColor(mLastDark ? 0xff000000 : 0xffffffff);
        }
        return mRipplePaint;
    }

    private void drawSoftware(Canvas canvas) {
        if (mGlowAlpha > 0f) {
            final Paint p = getRipplePaint();
            p.setAlpha((int)(mGlowAlpha * 255f));

            final float w = getBounds().width();
            final float h = getBounds().height();
            final boolean horizontal = w > h;
            final float diameter = getRippleSize() * mGlowScale;
            final float radius = diameter * .5f;
            final float cx = w * .5f;
            final float cy = h * .5f;
            final float rx = horizontal ? radius : cx;
            final float ry = horizontal ? cy : radius;
            final float corner = horizontal ? cy : cx;

            canvas.drawRoundRect(cx - rx, cy - ry,
                    cx + rx, cy + ry,
                    corner, corner, p);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        mSupportHardware = canvas.isHardwareAccelerated();
        if (mSupportHardware) {
            drawHardware((DisplayListCanvas) canvas);
        } else {
            drawSoftware(canvas);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        // Not supported.
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        // Not supported.
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    private boolean isHorizontal() {
        return getBounds().width() > getBounds().height();
    }

    private void drawHardware(DisplayListCanvas c) {
        if (mDrawingHardwareGlow) {
            c.drawRoundRect(mLeftProp, mTopProp, mRightProp, mBottomProp, mRxProp, mRyProp,
                    mPaintProp);
        }
    }

    public float getGlowAlpha() {
        return mGlowAlpha;
    }

    public void setGlowAlpha(float x) {
        mGlowAlpha = x;
        invalidateSelf();
    }

    public float getGlowScale() {
        return mGlowScale;
    }

    public void setGlowScale(float x) {
        mGlowScale = x;
        invalidateSelf();
    }

    private float getMaxGlowAlpha() {
        return mLastDark ? GLOW_MAX_ALPHA_DARK : GLOW_MAX_ALPHA;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean pressed = false;
        for (int i = 0; i < state.length; i++) {
            if (state[i] == android.R.attr.state_pressed) {
                pressed = true;
                break;
            }
        }
        if (pressed != mPressed) {
            setPressed(pressed);
            mPressed = pressed;
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void jumpToCurrentState() {
        cancelAnimations();
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    public boolean hasFocusStateSpecified() {
        return true;
    }

    public void setPressed(boolean pressed) {
        if (mDark != mLastDark && pressed) {
            mRipplePaint = null;
            mLastDark = mDark;
        }
        if (mSupportHardware) {
            setPressedHardware(pressed);
        } else {
            setPressedSoftware(pressed);
        }
    }

    /**
     * Abort the ripple while it is delayed and before shown used only when setShouldDelayStartTouch
     * is enabled.
     */
    public void abortDelayedRipple() {
        mHandler.removeCallbacksAndMessages(null);
    }

    private void cancelAnimations() {
        mVisible = false;
        mTmpArray.addAll(mRunningAnimations);
        int size = mTmpArray.size();
        for (int i = 0; i < size; i++) {
            Animator a = mTmpArray.get(i);
            a.cancel();
        }
        mTmpArray.clear();
        mRunningAnimations.clear();
        mHandler.removeCallbacksAndMessages(null);
    }

    private void setPressedSoftware(boolean pressed) {
        if (pressed) {
            if (mDelayTouchFeedback) {
                if (mRunningAnimations.isEmpty()) {
                    mHandler.removeCallbacksAndMessages(null);
                    mHandler.postDelayed(this::enterSoftware, ViewConfiguration.getTapTimeout());
                } else if (mVisible) {
                    enterSoftware();
                }
            } else {
                enterSoftware();
            }
        } else {
            exitSoftware();
        }
    }

    private void enterSoftware() {
        cancelAnimations();
        mVisible = true;
        mGlowAlpha = getMaxGlowAlpha();
        ObjectAnimator scaleAnimator = ObjectAnimator.ofFloat(this, "glowScale",
                0f, GLOW_MAX_SCALE_FACTOR);
        scaleAnimator.setInterpolator(mInterpolator);
        scaleAnimator.setDuration(ANIMATION_DURATION_SCALE);
        scaleAnimator.addListener(mAnimatorListener);
        scaleAnimator.start();
        mRunningAnimations.add(scaleAnimator);

        // With the delay, it could eventually animate the enter animation with no pressed state,
        // then immediately show the exit animation. If this is skipped there will be no ripple.
        if (mDelayTouchFeedback && !mPressed) {
            exitSoftware();
        }
    }

    private void exitSoftware() {
        ObjectAnimator alphaAnimator = ObjectAnimator.ofFloat(this, "glowAlpha", mGlowAlpha, 0f);
        alphaAnimator.setInterpolator(Interpolators.ALPHA_OUT);
        alphaAnimator.setDuration(ANIMATION_DURATION_FADE);
        alphaAnimator.addListener(mAnimatorListener);
        alphaAnimator.start();
        mRunningAnimations.add(alphaAnimator);
    }

    private void setPressedHardware(boolean pressed) {
        if (pressed) {
            if (mDelayTouchFeedback) {
                if (mRunningAnimations.isEmpty()) {
                    mHandler.removeCallbacksAndMessages(null);
                    mHandler.postDelayed(this::enterHardware, ViewConfiguration.getTapTimeout());
                } else if (mVisible) {
                    enterHardware();
                }
            } else {
                enterHardware();
            }
        } else {
            exitHardware();
        }
    }

    /**
     * Sets the left/top property for the round rect to {@code prop} depending on whether we are
     * horizontal or vertical mode.
     */
    private void setExtendStart(CanvasProperty<Float> prop) {
        if (isHorizontal()) {
            mLeftProp = prop;
        } else {
            mTopProp = prop;
        }
    }

    private CanvasProperty<Float> getExtendStart() {
        return isHorizontal() ? mLeftProp : mTopProp;
    }

    /**
     * Sets the right/bottom property for the round rect to {@code prop} depending on whether we are
     * horizontal or vertical mode.
     */
    private void setExtendEnd(CanvasProperty<Float> prop) {
        if (isHorizontal()) {
            mRightProp = prop;
        } else {
            mBottomProp = prop;
        }
    }

    private CanvasProperty<Float> getExtendEnd() {
        return isHorizontal() ? mRightProp : mBottomProp;
    }

    private int getExtendSize() {
        return isHorizontal() ? getBounds().width() : getBounds().height();
    }

    private int getRippleSize() {
        int size = isHorizontal() ? getBounds().width() : getBounds().height();
        return Math.min(size, mMaxWidth);
    }

    private void enterHardware() {
        cancelAnimations();
        mVisible = true;
        mDrawingHardwareGlow = true;
        setExtendStart(CanvasProperty.createFloat(getExtendSize() / 2));
        final RenderNodeAnimator startAnim = new RenderNodeAnimator(getExtendStart(),
                getExtendSize()/2 - GLOW_MAX_SCALE_FACTOR * getRippleSize()/2);
        startAnim.setDuration(ANIMATION_DURATION_SCALE);
        startAnim.setInterpolator(mInterpolator);
        startAnim.addListener(mAnimatorListener);
        startAnim.setTarget(mTargetView);

        setExtendEnd(CanvasProperty.createFloat(getExtendSize() / 2));
        final RenderNodeAnimator endAnim = new RenderNodeAnimator(getExtendEnd(),
                getExtendSize()/2 + GLOW_MAX_SCALE_FACTOR * getRippleSize()/2);
        endAnim.setDuration(ANIMATION_DURATION_SCALE);
        endAnim.setInterpolator(mInterpolator);
        endAnim.addListener(mAnimatorListener);
        endAnim.setTarget(mTargetView);

        if (isHorizontal()) {
            mTopProp = CanvasProperty.createFloat(0f);
            mBottomProp = CanvasProperty.createFloat(getBounds().height());
            mRxProp = CanvasProperty.createFloat(getBounds().height()/2);
            mRyProp = CanvasProperty.createFloat(getBounds().height()/2);
        } else {
            mLeftProp = CanvasProperty.createFloat(0f);
            mRightProp = CanvasProperty.createFloat(getBounds().width());
            mRxProp = CanvasProperty.createFloat(getBounds().width()/2);
            mRyProp = CanvasProperty.createFloat(getBounds().width()/2);
        }

        mGlowScale = GLOW_MAX_SCALE_FACTOR;
        mGlowAlpha = getMaxGlowAlpha();
        mRipplePaint = getRipplePaint();
        mRipplePaint.setAlpha((int) (mGlowAlpha * 255));
        mPaintProp = CanvasProperty.createPaint(mRipplePaint);

        startAnim.start();
        endAnim.start();
        mRunningAnimations.add(startAnim);
        mRunningAnimations.add(endAnim);

        invalidateSelf();

        // With the delay, it could eventually animate the enter animation with no pressed state,
        // then immediately show the exit animation. If this is skipped there will be no ripple.
        if (mDelayTouchFeedback && !mPressed) {
            exitHardware();
        }
    }

    private void exitHardware() {
        mPaintProp = CanvasProperty.createPaint(getRipplePaint());
        final RenderNodeAnimator opacityAnim = new RenderNodeAnimator(mPaintProp,
                RenderNodeAnimator.PAINT_ALPHA, 0);
        opacityAnim.setDuration(ANIMATION_DURATION_FADE);
        opacityAnim.setInterpolator(Interpolators.ALPHA_OUT);
        opacityAnim.addListener(mAnimatorListener);
        opacityAnim.setTarget(mTargetView);

        opacityAnim.start();
        mRunningAnimations.add(opacityAnim);

        invalidateSelf();
    }

    private final AnimatorListenerAdapter mAnimatorListener =
            new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mRunningAnimations.remove(animation);
            if (mRunningAnimations.isEmpty() && !mPressed) {
                mVisible = false;
                mDrawingHardwareGlow = false;
                invalidateSelf();
            }
        }
    };

    /**
     * Interpolator with a smooth log deceleration
     */
    private static final class LogInterpolator implements Interpolator {
        @Override
        public float getInterpolation(float input) {
            return 1 - (float) Math.pow(400, -input * 1.4);
        }
    }
}
