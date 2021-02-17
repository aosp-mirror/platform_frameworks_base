/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Canvas;
import android.graphics.CanvasProperty;
import android.graphics.Paint;
import android.graphics.RecordingCanvas;
import android.graphics.animation.RenderNodeAnimator;
import android.util.ArraySet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

import java.util.function.Consumer;

/**
 * @hide
 */
public final class RippleAnimationSession {
    private static final int ENTER_ANIM_DURATION = 350;
    private static final int EXIT_ANIM_OFFSET = ENTER_ANIM_DURATION;
    private static final int EXIT_ANIM_DURATION = 350;
    private static final TimeInterpolator LINEAR_INTERPOLATOR = new LinearInterpolator();
    // Matches R.interpolator.fast_out_slow_in but as we have no context we can't just import that
    private static final TimeInterpolator DECELERATE_INTERPOLATOR = new DecelerateInterpolator();

    private Consumer<RippleAnimationSession> mOnSessionEnd;
    private AnimationProperties<Float, Paint> mProperties;
    private AnimationProperties<CanvasProperty<Float>, CanvasProperty<Paint>> mCanvasProperties;
    private Runnable mOnUpdate;
    private long mStartTime;
    private boolean mForceSoftware;
    private ArraySet<Animator> mActiveAnimations = new ArraySet(3);

    RippleAnimationSession(@NonNull AnimationProperties<Float, Paint> properties,
            boolean forceSoftware) {
        mProperties = properties;
        mForceSoftware = forceSoftware;
    }

    void end() {
        for (Animator anim: mActiveAnimations) {
            if (anim != null) anim.end();
        }
        mActiveAnimations.clear();
    }

    @NonNull RippleAnimationSession enter(Canvas canvas) {
        if (isHwAccelerated(canvas)) {
            enterHardware((RecordingCanvas) canvas);
        } else {
            enterSoftware();
        }
        mStartTime = System.nanoTime();
        return this;
    }

    @NonNull RippleAnimationSession exit(Canvas canvas) {
        if (isHwAccelerated(canvas)) exitHardware((RecordingCanvas) canvas);
        else exitSoftware();
        return this;
    }

    private void onAnimationEnd(Animator anim) {
        mActiveAnimations.remove(anim);
    }

    @NonNull RippleAnimationSession setOnSessionEnd(
            @Nullable Consumer<RippleAnimationSession> onSessionEnd) {
        mOnSessionEnd = onSessionEnd;
        return this;
    }

    RippleAnimationSession setOnAnimationUpdated(@Nullable Runnable run) {
        mOnUpdate = run;
        mProperties.setOnChange(mOnUpdate);
        return this;
    }

    private boolean isHwAccelerated(Canvas canvas) {
        return canvas.isHardwareAccelerated() && !mForceSoftware;
    }

    private void exitSoftware() {
        ValueAnimator expand = ValueAnimator.ofFloat(.5f, 1f);
        expand.setDuration(EXIT_ANIM_DURATION);
        expand.setStartDelay(computeDelay());
        expand.addUpdateListener(updatedAnimation -> {
            notifyUpdate();
            mProperties.getShader().setProgress((Float) expand.getAnimatedValue());
        });
        expand.addListener(new AnimatorListener(this) {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                Consumer<RippleAnimationSession> onEnd = mOnSessionEnd;
                if (onEnd != null) onEnd.accept(RippleAnimationSession.this);
            }
        });
        expand.setInterpolator(LINEAR_INTERPOLATOR);
        expand.start();
        mActiveAnimations.add(expand);
    }

    private long computeDelay() {
        long currentTime = System.nanoTime();
        long timePassed =  (currentTime - mStartTime) / 1_000_000;
        long difference = EXIT_ANIM_OFFSET;
        return Math.max(difference - timePassed, 0);
    }
    private void notifyUpdate() {
        Runnable onUpdate = mOnUpdate;
        if (onUpdate != null) onUpdate.run();
    }

    RippleAnimationSession setForceSoftwareAnimation(boolean forceSw) {
        mForceSoftware = forceSw;
        return this;
    }


    private void exitHardware(RecordingCanvas canvas) {
        AnimationProperties<CanvasProperty<Float>, CanvasProperty<Paint>>
                props = getCanvasProperties();
        RenderNodeAnimator exit =
                new RenderNodeAnimator(props.getProgress(), 1f);
        exit.setDuration(EXIT_ANIM_DURATION);
        exit.addListener(new AnimatorListener(this) {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                Consumer<RippleAnimationSession> onEnd = mOnSessionEnd;
                if (onEnd != null) onEnd.accept(RippleAnimationSession.this);
            }
        });
        exit.setTarget(canvas);
        exit.setInterpolator(DECELERATE_INTERPOLATOR);

        long delay = computeDelay();
        exit.setStartDelay(delay);
        exit.start();
        mActiveAnimations.add(exit);
    }

    private void enterHardware(RecordingCanvas can) {
        AnimationProperties<CanvasProperty<Float>, CanvasProperty<Paint>>
                props = getCanvasProperties();
        RenderNodeAnimator expand =
                new RenderNodeAnimator(props.getProgress(), .5f);
        expand.setTarget(can);
        expand.setDuration(ENTER_ANIM_DURATION);
        expand.addListener(new AnimatorListener(this));
        expand.setInterpolator(LINEAR_INTERPOLATOR);
        expand.start();
        mActiveAnimations.add(expand);
    }

    private void enterSoftware() {
        ValueAnimator expand = ValueAnimator.ofFloat(0f, 0.5f);
        expand.addUpdateListener(updatedAnimation -> {
            notifyUpdate();
            mProperties.getShader().setProgress((Float) expand.getAnimatedValue());
        });
        expand.addListener(new AnimatorListener(this));
        expand.setInterpolator(LINEAR_INTERPOLATOR);
        expand.start();
        mActiveAnimations.add(expand);
    }

    @NonNull AnimationProperties<Float, Paint> getProperties() {
        return mProperties;
    }

    @NonNull AnimationProperties getCanvasProperties() {
        if (mCanvasProperties == null) {
            mCanvasProperties = new AnimationProperties<>(
                    CanvasProperty.createFloat(mProperties.getX()),
                    CanvasProperty.createFloat(mProperties.getY()),
                    CanvasProperty.createFloat(mProperties.getMaxRadius()),
                    CanvasProperty.createPaint(mProperties.getPaint()),
                    CanvasProperty.createFloat(mProperties.getProgress()),
                    mProperties.getShader());
        }
        return mCanvasProperties;
    }

    private static class AnimatorListener implements Animator.AnimatorListener {
        private final RippleAnimationSession mSession;

        AnimatorListener(RippleAnimationSession session) {
            mSession = session;
        }
        @Override
        public void onAnimationStart(Animator animation) {

        }

        @Override
        public void onAnimationEnd(Animator animation) {
            mSession.onAnimationEnd(animation);
        }

        @Override
        public void onAnimationCancel(Animator animation) {

        }

        @Override
        public void onAnimationRepeat(Animator animation) {

        }
    }

    static class AnimationProperties<FloatType, PaintType> {
        private final FloatType mY;
        private FloatType mProgress;
        private FloatType mMaxRadius;
        private final PaintType mPaint;
        private final FloatType mX;
        private final RippleShader mShader;
        private Runnable mOnChange;

        private void onChange() {
            if (mOnChange != null) mOnChange.run();
        }

        private void setOnChange(Runnable onChange) {
            mOnChange = onChange;
        }

        AnimationProperties(FloatType x, FloatType y, FloatType maxRadius,
                PaintType paint, FloatType progress, RippleShader shader) {
            mY = y;
            mX = x;
            mMaxRadius = maxRadius;
            mPaint = paint;
            mShader = shader;
            mProgress = progress;
        }

        FloatType getProgress() {
            return mProgress;
        }

        FloatType getX() {
            return mX;
        }

        FloatType getY() {
            return mY;
        }

        FloatType getMaxRadius() {
            return mMaxRadius;
        }

        PaintType getPaint() {
            return mPaint;
        }

        RippleShader getShader() {
            return mShader;
        }
    }
}
