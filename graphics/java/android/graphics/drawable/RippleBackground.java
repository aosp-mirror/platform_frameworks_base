/*
 * Copyright (C) 2013 The Android Open Source Project
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
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.graphics.Canvas;
import android.graphics.CanvasProperty;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.FloatProperty;
import android.view.DisplayListCanvas;
import android.view.RenderNodeAnimator;
import android.view.animation.LinearInterpolator;

/**
 * Draws a ripple background.
 */
class RippleBackground extends RippleComponent {
    private static final TimeInterpolator LINEAR_INTERPOLATOR = new LinearInterpolator();

    private static final int OPACITY_ENTER_DURATION = 600;
    private static final int OPACITY_ENTER_DURATION_FAST = 120;
    private static final int OPACITY_EXIT_DURATION = 480;

    // Hardware rendering properties.
    private CanvasProperty<Paint> mPropPaint;
    private CanvasProperty<Float> mPropRadius;
    private CanvasProperty<Float> mPropX;
    private CanvasProperty<Float> mPropY;

    // Software rendering properties.
    private float mOpacity = 0;

    public RippleBackground(RippleDrawable owner, Rect bounds, boolean forceSoftware) {
        super(owner, bounds, forceSoftware);
    }

    public boolean isVisible() {
        return mOpacity > 0 || isHardwareAnimating();
    }

    @Override
    protected boolean drawSoftware(Canvas c, Paint p) {
        boolean hasContent = false;

        final int origAlpha = p.getAlpha();
        final int alpha = (int) (origAlpha * mOpacity + 0.5f);
        if (alpha > 0) {
            p.setAlpha(alpha);
            c.drawCircle(0, 0, mTargetRadius, p);
            p.setAlpha(origAlpha);
            hasContent = true;
        }

        return hasContent;
    }

    @Override
    protected boolean drawHardware(DisplayListCanvas c) {
        c.drawCircle(mPropX, mPropY, mPropRadius, mPropPaint);
        return true;
    }

    @Override
    protected Animator createSoftwareEnter(boolean fast) {
        // Linear enter based on current opacity.
        final int maxDuration = fast ? OPACITY_ENTER_DURATION_FAST : OPACITY_ENTER_DURATION;
        final int duration = (int) ((1 - mOpacity) * maxDuration);

        final ObjectAnimator opacity = ObjectAnimator.ofFloat(this, OPACITY, 1);
        opacity.setAutoCancel(true);
        opacity.setDuration(duration);
        opacity.setInterpolator(LINEAR_INTERPOLATOR);

        return opacity;
    }

    @Override
    protected Animator createSoftwareExit() {
        final AnimatorSet set = new AnimatorSet();

        // Linear exit after enter is completed.
        final ObjectAnimator exit = ObjectAnimator.ofFloat(this, RippleBackground.OPACITY, 0);
        exit.setInterpolator(LINEAR_INTERPOLATOR);
        exit.setDuration(OPACITY_EXIT_DURATION);
        exit.setAutoCancel(true);

        final AnimatorSet.Builder builder = set.play(exit);

        // Linear "fast" enter based on current opacity.
        final int fastEnterDuration = (int) ((1 - mOpacity) * OPACITY_ENTER_DURATION_FAST);
        if (fastEnterDuration > 0) {
            final ObjectAnimator enter = ObjectAnimator.ofFloat(this, RippleBackground.OPACITY, 1);
            enter.setInterpolator(LINEAR_INTERPOLATOR);
            enter.setDuration(fastEnterDuration);
            enter.setAutoCancel(true);

            builder.after(enter);
        }

        return set;
    }

    @Override
    protected RenderNodeAnimatorSet createHardwareExit(Paint p) {
        final RenderNodeAnimatorSet set = new RenderNodeAnimatorSet();

        final int targetAlpha = p.getAlpha();
        final int currentAlpha = (int) (mOpacity * targetAlpha + 0.5f);
        p.setAlpha(currentAlpha);

        mPropPaint = CanvasProperty.createPaint(p);
        mPropRadius = CanvasProperty.createFloat(mTargetRadius);
        mPropX = CanvasProperty.createFloat(0);
        mPropY = CanvasProperty.createFloat(0);

        // Linear "fast" enter based on current opacity.
        final int fastEnterDuration = (int) ((1 - mOpacity) * OPACITY_ENTER_DURATION_FAST);
        if (fastEnterDuration > 0) {
            final RenderNodeAnimator enter = new RenderNodeAnimator(
                    mPropPaint, RenderNodeAnimator.PAINT_ALPHA, targetAlpha);
            enter.setInterpolator(LINEAR_INTERPOLATOR);
            enter.setDuration(fastEnterDuration);
            set.add(enter);
        }

        // Linear exit after enter is completed.
        final RenderNodeAnimator exit = new RenderNodeAnimator(
                mPropPaint, RenderNodeAnimator.PAINT_ALPHA, 0);
        exit.setInterpolator(LINEAR_INTERPOLATOR);
        exit.setDuration(OPACITY_EXIT_DURATION);
        exit.setStartDelay(fastEnterDuration);
        set.add(exit);

        return set;
    }

    @Override
    protected void jumpValuesToExit() {
        mOpacity = 0;
    }

    private static abstract class BackgroundProperty extends FloatProperty<RippleBackground> {
        public BackgroundProperty(String name) {
            super(name);
        }
    }

    private static final BackgroundProperty OPACITY = new BackgroundProperty("opacity") {
        @Override
        public void setValue(RippleBackground object, float value) {
            object.mOpacity = value;
            object.invalidateSelf();
        }

        @Override
        public Float get(RippleBackground object) {
            return object.mOpacity;
        }
    };
}
