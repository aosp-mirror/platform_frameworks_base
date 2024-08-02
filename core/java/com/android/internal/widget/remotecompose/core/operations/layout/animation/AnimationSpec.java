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

import com.android.internal.widget.remotecompose.core.CompanionOperation;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.operations.utilities.easing.GeneralEasing;

import java.util.List;

/**
 * Basic component animation spec
 */
public class AnimationSpec implements Operation {

    public static final AnimationSpec.Companion COMPANION = new AnimationSpec.Companion();

    int mAnimationId = -1;
    int mMotionDuration = 300;
    int mMotionEasingType = GeneralEasing.CUBIC_STANDARD;
    int mVisibilityDuration = 300;
    int mVisibilityEasingType = GeneralEasing.CUBIC_STANDARD;
    ANIMATION mEnterAnimation = ANIMATION.FADE_IN;
    ANIMATION mExitAnimation = ANIMATION.FADE_OUT;

    public AnimationSpec(int animationId, int motionDuration, int motionEasingType,
                         int visibilityDuration, int visibilityEasingType,
                         ANIMATION enterAnimation, ANIMATION exitAnimation) {
        this.mAnimationId = animationId;
        this.mMotionDuration = motionDuration;
        this.mMotionEasingType = motionEasingType;
        this.mVisibilityDuration = visibilityDuration;
        this.mVisibilityEasingType = visibilityEasingType;
        this.mEnterAnimation = enterAnimation;
        this.mExitAnimation = exitAnimation;
    }

    public AnimationSpec() {
        this(-1, 300, GeneralEasing.CUBIC_STANDARD,
                300, GeneralEasing.CUBIC_STANDARD,
                ANIMATION.FADE_IN, ANIMATION.FADE_OUT);
    }

    public int getAnimationId() {
        return mAnimationId;
    }

    public int getMotionDuration() {
        return mMotionDuration;
    }

    public int getMotionEasingType() {
        return mMotionEasingType;
    }

    public int getVisibilityDuration() {
        return mVisibilityDuration;
    }

    public int getVisibilityEasingType() {
        return mVisibilityEasingType;
    }

    public ANIMATION getEnterAnimation() {
        return mEnterAnimation;
    }

    public ANIMATION getExitAnimation() {
        return mExitAnimation;
    }

    @Override
    public String toString() {
        return "ANIMATION_SPEC (" + mMotionDuration + " ms)";
    }

    public enum ANIMATION {
        FADE_IN,
        FADE_OUT,
        SLIDE_LEFT,
        SLIDE_RIGHT,
        SLIDE_TOP,
        SLIDE_BOTTOM,
        ROTATE,
        PARTICLE
    }

    @Override
    public void write(WireBuffer buffer) {
        Companion.apply(buffer, mAnimationId, mMotionDuration, mMotionEasingType,
                mVisibilityDuration, mVisibilityEasingType, mEnterAnimation, mExitAnimation);
    }

    @Override
    public void apply(RemoteContext context) {
        // nothing here
    }

    @Override
    public String deepToString(String indent) {
        return (indent != null ? indent : "") + toString();
    }

    public static class Companion implements CompanionOperation {
        @Override
        public String name() {
            return "AnimationSpec";
        }

        @Override
        public int id() {
            return Operations.ANIMATION_SPEC;
        }

        public static int animationToInt(ANIMATION animation) {
            return animation.ordinal();
        }

        public static ANIMATION intToAnimation(int value) {
            switch (value) {
                case 0:
                    return ANIMATION.FADE_IN;
                case 1:
                    return ANIMATION.FADE_OUT;
                case 2:
                    return ANIMATION.SLIDE_LEFT;
                case 3:
                    return ANIMATION.SLIDE_RIGHT;
                case 4:
                    return ANIMATION.SLIDE_TOP;
                case 5:
                    return ANIMATION.SLIDE_BOTTOM;
                case 6:
                    return ANIMATION.ROTATE;
                case 7:
                    return ANIMATION.PARTICLE;
                default:
                    return ANIMATION.FADE_IN;
            }
        }

        public static void apply(WireBuffer buffer, int animationId, int motionDuration,
                                 int motionEasingType, int visibilityDuration,
                                 int visibilityEasingType, ANIMATION enterAnimation,
                                 ANIMATION exitAnimation) {
            buffer.start(Operations.ANIMATION_SPEC);
            buffer.writeInt(animationId);
            buffer.writeInt(motionDuration);
            buffer.writeInt(motionEasingType);
            buffer.writeInt(visibilityDuration);
            buffer.writeInt(visibilityEasingType);
            buffer.writeInt(animationToInt(enterAnimation));
            buffer.writeInt(animationToInt(exitAnimation));
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            int animationId = buffer.readInt();
            int motionDuration = buffer.readInt();
            int motionEasingType = buffer.readInt();
            int visibilityDuration = buffer.readInt();
            int visibilityEasingType = buffer.readInt();
            ANIMATION enterAnimation = intToAnimation(buffer.readInt());
            ANIMATION exitAnimation = intToAnimation(buffer.readInt());
            AnimationSpec op = new AnimationSpec(animationId, motionDuration, motionEasingType,
                    visibilityDuration, visibilityEasingType, enterAnimation, exitAnimation);
            operations.add(op);
        }
    }
}
