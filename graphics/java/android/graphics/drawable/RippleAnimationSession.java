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
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

import java.util.function.Consumer;

/**
 * @hide
 */
public final class RippleAnimationSession {
    private static final String TAG = "RippleAnimationSession";
    private static final int ENTER_ANIM_DURATION = 450;
    private static final int EXIT_ANIM_DURATION = 300;
    private static final TimeInterpolator LINEAR_INTERPOLATOR = new LinearInterpolator();
    private static final Interpolator FAST_OUT_LINEAR_IN =
            new PathInterpolator(0.4f, 0f, 1f, 1f);
    private Consumer<RippleAnimationSession> mOnSessionEnd;
    private final AnimationProperties<Float, Paint> mProperties;
    private AnimationProperties<CanvasProperty<Float>, CanvasProperty<Paint>> mCanvasProperties;
    private Runnable mOnUpdate;
    private long mStartTime;
    private boolean mForceSoftware;
    private boolean mAnimateSparkle;

    RippleAnimationSession(@NonNull AnimationProperties<Float, Paint> properties,
            boolean forceSoftware) {
        mProperties = properties;
        mForceSoftware = forceSoftware;
    }

    @NonNull RippleAnimationSession enter(Canvas canvas) {
        if (isHwAccelerated(canvas)) {
            enterHardware((RecordingCanvas) canvas);
        } else {
            enterSoftware();
        }
        mStartTime = AnimationUtils.currentAnimationTimeMillis();
        return this;
    }

    @NonNull RippleAnimationSession exit(Canvas canvas) {
        if (isHwAccelerated(canvas)) exitHardware((RecordingCanvas) canvas);
        else exitSoftware();
        return this;
    }

    private void onAnimationEnd(Animator anim) {
        notifyUpdate();
    }

    @NonNull RippleAnimationSession setOnSessionEnd(
            @Nullable Consumer<RippleAnimationSession> onSessionEnd) {
        mOnSessionEnd = onSessionEnd;
        return this;
    }

    RippleAnimationSession setOnAnimationUpdated(@Nullable Runnable run) {
        mOnUpdate = run;
        return this;
    }

    public boolean shouldAnimateSparkle() {
        return mAnimateSparkle;
    }

    public float getSparklePhase() {
        final long now = AnimationUtils.currentAnimationTimeMillis();
        final long elapsed = now - mStartTime;
        return  (float) elapsed / 800;
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
                mAnimateSparkle = false;
                Consumer<RippleAnimationSession> onEnd = mOnSessionEnd;
                if (onEnd != null) onEnd.accept(RippleAnimationSession.this);
            }
        });
        expand.setInterpolator(LINEAR_INTERPOLATOR);
        expand.start();
    }

    private long computeDelay() {
        final long timePassed =  AnimationUtils.currentAnimationTimeMillis() - mStartTime;
        return Math.max((long) ENTER_ANIM_DURATION - timePassed, 0);
    }

    private void notifyUpdate() {
        if (mOnUpdate != null) mOnUpdate.run();
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
                mAnimateSparkle = false;
                Consumer<RippleAnimationSession> onEnd = mOnSessionEnd;
                if (onEnd != null) onEnd.accept(RippleAnimationSession.this);
            }
        });
        exit.setTarget(canvas);
        exit.setInterpolator(LINEAR_INTERPOLATOR);

        long delay = computeDelay();
        exit.setStartDelay(delay);
        exit.start();
    }

    private void enterHardware(RecordingCanvas canvas) {
        AnimationProperties<CanvasProperty<Float>, CanvasProperty<Paint>>
                props = getCanvasProperties();
        RenderNodeAnimator expand =
                new RenderNodeAnimator(props.getProgress(), .5f);
        expand.setTarget(canvas);
        startAnimation(expand);
    }

    private void startAnimation(Animator expand) {
        expand.setDuration(ENTER_ANIM_DURATION);
        expand.addListener(new AnimatorListener(this));
        expand.setInterpolator(FAST_OUT_LINEAR_IN);
        expand.start();
        mAnimateSparkle = true;
    }

    private void enterSoftware() {
        ValueAnimator expand = ValueAnimator.ofFloat(0f, 0.5f);
        expand.addUpdateListener(updatedAnimation -> {
            notifyUpdate();
            mProperties.getShader().setProgress((Float) expand.getAnimatedValue());
        });
        startAnimation(expand);
    }

    @NonNull AnimationProperties<Float, Paint> getProperties() {
        return mProperties;
    }

    @NonNull
    AnimationProperties<CanvasProperty<Float>, CanvasProperty<Paint>> getCanvasProperties() {
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
        private final FloatType mProgress;
        private final FloatType mMaxRadius;
        private final PaintType mPaint;
        private final RippleShader mShader;
        private FloatType mX;
        private FloatType mY;

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

        void setOrigin(FloatType x, FloatType y) {
            mX = x;
            mY = y;
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
