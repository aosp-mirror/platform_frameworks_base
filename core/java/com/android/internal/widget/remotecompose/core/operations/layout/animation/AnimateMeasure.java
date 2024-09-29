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
package com.android.internal.widget.remotecompose.core.operations.layout.animation;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.layout.DecoratorComponent;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.ComponentMeasure;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.PaddingModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.paint.PaintBundle;
import com.android.internal.widget.remotecompose.core.operations.utilities.easing.FloatAnimation;
import com.android.internal.widget.remotecompose.core.operations.utilities.easing.GeneralEasing;

/**
 * Basic interpolation manager between two ComponentMeasures
 *
 * Handles position, size and visibility
 */
public class AnimateMeasure {
    long mStartTime = System.currentTimeMillis();
    Component mComponent;
    ComponentMeasure mOriginal;
    ComponentMeasure mTarget;
    int mDuration;
    int mDurationVisibilityChange = mDuration;
    AnimationSpec.ANIMATION mEnterAnimation = AnimationSpec.ANIMATION.FADE_IN;
    AnimationSpec.ANIMATION mExitAnimation = AnimationSpec.ANIMATION.FADE_OUT;
    int mMotionEasingType = GeneralEasing.CUBIC_STANDARD;
    int mVisibilityEasingType = GeneralEasing.CUBIC_ACCELERATE;

    float mP = 0f;
    float mVp = 0f;
    FloatAnimation mMotionEasing = new FloatAnimation(mMotionEasingType,
            mDuration / 1000f, null, 0f, Float.NaN);
    FloatAnimation mVisibilityEasing = new FloatAnimation(mVisibilityEasingType,
            mDurationVisibilityChange / 1000f,
            null, 0f, Float.NaN);
    ParticleAnimation mParticleAnimation;

    public AnimateMeasure(long startTime, Component component, ComponentMeasure original,
                          ComponentMeasure target, int duration, int durationVisibilityChange,
                          AnimationSpec.ANIMATION enterAnimation,
                          AnimationSpec.ANIMATION exitAnimation,
                          int motionEasingType, int visibilityEasingType) {
        this.mStartTime = startTime;
        this.mComponent = component;
        this.mOriginal = original;
        this.mTarget = target;
        this.mDuration = duration;
        this.mDurationVisibilityChange = durationVisibilityChange;
        this.mEnterAnimation = enterAnimation;
        this.mExitAnimation = exitAnimation;

        mMotionEasing.setTargetValue(1f);
        mVisibilityEasing.setTargetValue(1f);
        component.mVisibility = target.getVisibility();
    }

    public void update(long currentTime) {
        long elapsed = currentTime - mStartTime;
        mP = Math.min(elapsed / (float) mDuration, 1f);
        //mP = motionEasing.get(mP);
        mVp = Math.min(elapsed / (float) mDurationVisibilityChange, 1f);
        mVp = mVisibilityEasing.get(mVp);
    }

    public PaintBundle paint = new PaintBundle();

    public void apply(PaintContext context) {
        update(context.getContext().currentTime);

        mComponent.setX(getX());
        mComponent.setY(getY());
        mComponent.setWidth(getWidth());
        mComponent.setHeight(getHeight());

        float w = mComponent.getWidth();
        float h = mComponent.getHeight();
        for (Operation op : mComponent.mList) {
            if (op instanceof PaddingModifierOperation) {
                PaddingModifierOperation pop = (PaddingModifierOperation) op;
                w -= pop.getLeft() + pop.getRight();
                h -= pop.getTop() + pop.getBottom();
            }
            if (op instanceof DecoratorComponent) {
                ((DecoratorComponent) op).layout(context.getContext(), w, h);
            }
        }

        mComponent.mVisibility = mTarget.getVisibility();
        if (mOriginal.getVisibility() != mTarget.getVisibility()) {
            if (mTarget.getVisibility() == Component.Visibility.GONE) {
                switch (mExitAnimation) {
                    case PARTICLE:
                        // particleAnimation(context, component, original, target, vp)
                        if (mParticleAnimation == null) {
                            mParticleAnimation = new ParticleAnimation();
                        }
                        mParticleAnimation.animate(context, mComponent, mOriginal, mTarget, mVp);
                        break;
                    case FADE_OUT:
                        context.save();
                        context.savePaint();
                        paint.reset();
                        paint.setColor(0f, 0f, 0f, 1f - mVp);
                        context.applyPaint(paint);
                        context.saveLayer(mComponent.getX(), mComponent.getY(),
                                mComponent.getWidth(), mComponent.getHeight());
                        mComponent.paintingComponent(context);
                        context.restore();
                        context.restorePaint();
                        context.restore();
                        break;
                    case SLIDE_LEFT:
                        context.save();
                        context.translate(-mVp * mComponent.getParent().getWidth(), 0f);
                        context.saveLayer(mComponent.getX(), mComponent.getY(),
                                mComponent.getWidth(), mComponent.getHeight());
                        mComponent.paintingComponent(context);
                        context.restore();
                        context.restore();
                        break;
                    case SLIDE_RIGHT:
                        context.save();
                        context.savePaint();
                        paint.reset();
                        paint.setColor(0f, 0f, 0f, 1f);
                        context.applyPaint(paint);
                        context.translate(mVp * mComponent.getParent().getWidth(), 0f);
                        context.saveLayer(mComponent.getX(), mComponent.getY(),
                                mComponent.getWidth(), mComponent.getHeight());
                        mComponent.paintingComponent(context);
                        context.restore();
                        context.restorePaint();
                        context.restore();
                        break;
                    case SLIDE_TOP:
                        context.save();
                        context.translate(0f,
                                -mVp * mComponent.getParent().getHeight());
                        context.saveLayer(mComponent.getX(), mComponent.getY(),
                                mComponent.getWidth(), mComponent.getHeight());
                        mComponent.paintingComponent(context);
                        context.restore();
                        context.restore();
                        break;
                    case SLIDE_BOTTOM:
                        context.save();
                        context.translate(0f,
                                mVp * mComponent.getParent().getHeight());
                        context.saveLayer(mComponent.getX(), mComponent.getY(),
                                mComponent.getWidth(), mComponent.getHeight());
                        mComponent.paintingComponent(context);
                        context.restore();
                        context.restore();
                        break;
                    default:
                        //            particleAnimation(context, component, original, target, vp)
                        if (mParticleAnimation == null) {
                            mParticleAnimation = new ParticleAnimation();
                        }
                        mParticleAnimation.animate(context, mComponent, mOriginal, mTarget, mVp);
                        break;
                }
            } else if (mOriginal.getVisibility() == Component.Visibility.GONE
                    && mTarget.getVisibility() == Component.Visibility.VISIBLE) {
                switch (mEnterAnimation) {
                    case ROTATE:
                        float px = mTarget.getX() + mTarget.getW() / 2f;
                        float py = mTarget.getY() + mTarget.getH() / 2f;

                        context.save();
                        context.savePaint();
                        context.matrixRotate(mVp * 360f, px, py);
                        context.matrixScale(1f * mVp, 1f * mVp, px, py);
                        paint.reset();
                        paint.setColor(0f, 0f, 0f, mVp);
                        context.applyPaint(paint);
                        context.saveLayer(mComponent.getX(), mComponent.getY(),
                                mComponent.getWidth(), mComponent.getHeight());
                        mComponent.paintingComponent(context);
                        context.restore();
                        context.restorePaint();
                        context.restore();
                        break;
                    case FADE_IN:
                        context.save();
                        context.savePaint();
                        paint.reset();
                        paint.setColor(0f, 0f, 0f, mVp);
                        context.applyPaint(paint);
                        context.saveLayer(mComponent.getX(), mComponent.getY(),
                                mComponent.getWidth(), mComponent.getHeight());
                        mComponent.paintingComponent(context);
                        context.restore();
                        context.restorePaint();
                        context.restore();
                        break;
                    case SLIDE_LEFT:
                        context.save();
                        context.translate(
                                (1f - mVp) * mComponent.getParent().getWidth(), 0f);
                        context.saveLayer(mComponent.getX(), mComponent.getY(),
                                mComponent.getWidth(), mComponent.getHeight());
                        mComponent.paintingComponent(context);
                        context.restore();
                        context.restore();
                        break;
                    case SLIDE_RIGHT:
                        context.save();
                        context.translate(
                                -(1f - mVp) * mComponent.getParent().getWidth(), 0f);
                        context.saveLayer(mComponent.getX(), mComponent.getY(),
                                mComponent.getWidth(), mComponent.getHeight());
                        mComponent.paintingComponent(context);
                        context.restore();
                        context.restore();
                        break;
                    case SLIDE_TOP:
                        context.save();
                        context.translate(0f,
                                (1f - mVp) * mComponent.getParent().getHeight());
                        context.saveLayer(mComponent.getX(), mComponent.getY(),
                                mComponent.getWidth(), mComponent.getHeight());
                        mComponent.paintingComponent(context);
                        context.restore();
                        context.restore();
                        break;
                    case SLIDE_BOTTOM:
                        context.save();
                        context.translate(0f,
                                -(1f - mVp) * mComponent.getParent().getHeight());
                        context.saveLayer(mComponent.getX(), mComponent.getY(),
                                mComponent.getWidth(), mComponent.getHeight());
                        mComponent.paintingComponent(context);
                        context.restore();
                        context.restore();
                        break;
                    default:
                        break;
                }
            } else {
                mComponent.paintingComponent(context);
            }
        } else {
            mComponent.paintingComponent(context);
        }

        if (mP >= 1f && mVp >= 1f) {
            mComponent.mAnimateMeasure = null;
            mComponent.mVisibility = mTarget.getVisibility();
        }
    }

    public boolean isDone() {
        return mP >= 1f && mVp >= 1f;
    }

    public float getX() {
        return mOriginal.getX() * (1 - mP) + mTarget.getX() * mP;
    }

    public float getY() {
        return mOriginal.getY() * (1 - mP) + mTarget.getY() * mP;
    }

    public float getWidth() {
        return mOriginal.getW() * (1 - mP) + mTarget.getW() * mP;
    }

    public float getHeight() {
        return mOriginal.getH() * (1 - mP) + mTarget.getH() * mP;
    }

    public float getVisibility() {
        if (mOriginal.getVisibility() == mTarget.getVisibility()) {
            return 1f;
        } else if (mTarget.getVisibility() == Component.Visibility.VISIBLE) {
            return mVp;
        } else {
            return 1 - mVp;
        }
    }

    public void updateTarget(ComponentMeasure measure, long currentTime) {
        mOriginal.setX(getX());
        mOriginal.setY(getY());
        mOriginal.setW(getWidth());
        mOriginal.setH(getHeight());
        mTarget.setX(measure.getX());
        mTarget.setY(measure.getY());
        mTarget.setW(measure.getW());
        mTarget.setH(measure.getH());
        mTarget.setVisibility(measure.getVisibility());
        mStartTime = currentTime;
    }
}
