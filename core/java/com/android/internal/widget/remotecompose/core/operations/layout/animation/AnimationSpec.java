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

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.utilities.easing.GeneralEasing;

import java.util.List;

/** Basic component animation spec */
public class AnimationSpec extends Operation {
    int mAnimationId = -1;
    int mMotionDuration = 300;
    int mMotionEasingType = GeneralEasing.CUBIC_STANDARD;
    int mVisibilityDuration = 300;
    int mVisibilityEasingType = GeneralEasing.CUBIC_STANDARD;
    @NonNull ANIMATION mEnterAnimation = ANIMATION.FADE_IN;
    @NonNull ANIMATION mExitAnimation = ANIMATION.FADE_OUT;

    public AnimationSpec(
            int animationId,
            int motionDuration,
            int motionEasingType,
            int visibilityDuration,
            int visibilityEasingType,
            @NonNull ANIMATION enterAnimation,
            @NonNull ANIMATION exitAnimation) {
        this.mAnimationId = animationId;
        this.mMotionDuration = motionDuration;
        this.mMotionEasingType = motionEasingType;
        this.mVisibilityDuration = visibilityDuration;
        this.mVisibilityEasingType = visibilityEasingType;
        this.mEnterAnimation = enterAnimation;
        this.mExitAnimation = exitAnimation;
    }

    public AnimationSpec() {
        this(
                -1,
                600,
                GeneralEasing.CUBIC_STANDARD,
                500,
                GeneralEasing.CUBIC_STANDARD,
                ANIMATION.FADE_IN,
                ANIMATION.FADE_OUT);
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

    @NonNull
    public ANIMATION getEnterAnimation() {
        return mEnterAnimation;
    }

    @NonNull
    public ANIMATION getExitAnimation() {
        return mExitAnimation;
    }

    @NonNull
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
    public void write(@NonNull WireBuffer buffer) {
        apply(
                buffer,
                mAnimationId,
                mMotionDuration,
                mMotionEasingType,
                mVisibilityDuration,
                mVisibilityEasingType,
                mEnterAnimation,
                mExitAnimation);
    }

    @Override
    public void apply(@NonNull RemoteContext context) {
        // nothing here
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return (indent != null ? indent : "") + toString();
    }

    /**
     * The name of the class
     *
     * @return the name
     */
    @NonNull
    public static String name() {
        return "AnimationSpec";
    }

    /**
     * The OP_CODE for this command
     *
     * @return the opcode
     */
    public static int id() {
        return Operations.ANIMATION_SPEC;
    }

    public static int animationToInt(@NonNull ANIMATION animation) {
        return animation.ordinal();
    }

    @NonNull
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

    public static void apply(
            @NonNull WireBuffer buffer,
            int animationId,
            int motionDuration,
            int motionEasingType,
            int visibilityDuration,
            int visibilityEasingType,
            @NonNull ANIMATION enterAnimation,
            @NonNull ANIMATION exitAnimation) {
        buffer.start(Operations.ANIMATION_SPEC);
        buffer.writeInt(animationId);
        buffer.writeInt(motionDuration);
        buffer.writeInt(motionEasingType);
        buffer.writeInt(visibilityDuration);
        buffer.writeInt(visibilityEasingType);
        buffer.writeInt(animationToInt(enterAnimation));
        buffer.writeInt(animationToInt(exitAnimation));
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int animationId = buffer.readInt();
        int motionDuration = buffer.readInt();
        int motionEasingType = buffer.readInt();
        int visibilityDuration = buffer.readInt();
        int visibilityEasingType = buffer.readInt();
        ANIMATION enterAnimation = intToAnimation(buffer.readInt());
        ANIMATION exitAnimation = intToAnimation(buffer.readInt());
        AnimationSpec op =
                new AnimationSpec(
                        animationId,
                        motionDuration,
                        motionEasingType,
                        visibilityDuration,
                        visibilityEasingType,
                        enterAnimation,
                        exitAnimation);
        operations.add(op);
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Layout Operations", id(), name())
                .description("define the animation")
                .field(INT, "animationId", "")
                .field(INT, "motionDuration", "")
                .field(INT, "motionEasingType", "")
                .field(INT, "visibilityDuration", "")
                .field(INT, "visibilityEasingType", "");
    }
}
