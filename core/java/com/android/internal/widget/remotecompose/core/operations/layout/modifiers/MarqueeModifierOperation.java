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
package com.android.internal.widget.remotecompose.core.operations.layout.modifiers;

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.FLOAT;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.layout.LayoutComponent;
import com.android.internal.widget.remotecompose.core.operations.layout.ScrollDelegate;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.List;

/** Represents a Marquee modifier. */
public class MarqueeModifierOperation extends DecoratorModifierOperation implements ScrollDelegate {
    private static final int OP_CODE = Operations.MODIFIER_MARQUEE;
    public static final String CLASS_NAME = "MarqueeModifierOperation";

    int mIterations;
    int mAnimationMode;
    float mRepeatDelayMillis;
    float mInitialDelayMillis;
    float mSpacing;
    float mVelocity;

    private float mComponentWidth;
    private float mComponentHeight;
    private float mContentWidth;
    private float mContentHeight;

    public MarqueeModifierOperation(
            int iterations,
            int animationMode,
            float repeatDelayMillis,
            float initialDelayMillis,
            float spacing,
            float velocity) {
        this.mIterations = iterations;
        this.mAnimationMode = animationMode;
        this.mRepeatDelayMillis = repeatDelayMillis;
        this.mInitialDelayMillis = initialDelayMillis;
        this.mSpacing = spacing;
        this.mVelocity = velocity;
    }

    public void setContentWidth(float value) {
        mContentWidth = value;
    }

    public void setContentHeight(float value) {
        mContentHeight = value;
    }

    @Override
    public float getScrollX(float currentValue) {
        return mScrollX;
    }

    @Override
    public float getScrollY(float currentValue) {
        return 0;
    }

    @Override
    public boolean handlesHorizontalScroll() {
        return true;
    }

    @Override
    public boolean handlesVerticalScroll() {
        return false;
    }

    /** Reset the modifier */
    public void reset() {
        mLastTime = 0;
        mScrollX = 0f;
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(
                buffer,
                mIterations,
                mAnimationMode,
                mRepeatDelayMillis,
                mInitialDelayMillis,
                mSpacing,
                mVelocity);
    }

    // @Override
    public void serializeToString(int indent, StringSerializer serializer) {
        serializer.append(indent, "MARQUEE = [" + mIterations + "]");
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return (indent != null ? indent : "") + toString();
    }

    private long mLastTime = 0;
    private long mStartTime = 0;

    private float mScrollX = 0f;

    @Override
    public void paint(PaintContext context) {
        long currentTime = System.currentTimeMillis();
        if (mLastTime == 0) {
            mLastTime = currentTime;
            mStartTime = mLastTime + (long) mInitialDelayMillis;
            context.needsRepaint();
        }
        if (mContentWidth > mComponentWidth && currentTime - mStartTime > mInitialDelayMillis) {
            float density = context.getContext().getDensity(); // in dp
            float delta = mContentWidth - mComponentWidth;
            float duration = delta / (density * mVelocity);
            float elapsed = ((System.currentTimeMillis() - mStartTime) / 1000f);
            elapsed = (elapsed % duration) / duration;
            float offset =
                    (1f + (float) Math.sin(elapsed * 2 * Math.PI - Math.PI / 2f)) / 2f * -delta;

            mScrollX = offset;
            context.needsRepaint();
        }
    }

    @Override
    public String toString() {
        return "MarqueeModifierOperation(" + mIterations + ")";
    }

    public static String name() {
        return CLASS_NAME;
    }

    public static int id() {
        return OP_CODE;
    }

    public static void apply(
            WireBuffer buffer,
            int iterations,
            int animationMode,
            float repeatDelayMillis,
            float initialDelayMillis,
            float spacing,
            float velocity) {
        buffer.start(OP_CODE);
        buffer.writeInt(iterations);
        buffer.writeInt(animationMode);
        buffer.writeFloat(repeatDelayMillis);
        buffer.writeFloat(initialDelayMillis);
        buffer.writeFloat(spacing);
        buffer.writeFloat(velocity);
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        int iterations = buffer.readInt();
        int animationMode = buffer.readInt();
        float repeatDelayMillis = buffer.readFloat();
        float initialDelayMillis = buffer.readFloat();
        float spacing = buffer.readFloat();
        float velocity = buffer.readFloat();
        operations.add(
                new MarqueeModifierOperation(
                        iterations,
                        animationMode,
                        repeatDelayMillis,
                        initialDelayMillis,
                        spacing,
                        velocity));
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Modifier Operations", OP_CODE, CLASS_NAME)
                .description("specify a Marquee Modifier")
                .field(FLOAT, "value", "");
    }

    @Override
    public void layout(RemoteContext context, Component component, float width, float height) {
        mComponentWidth = width;
        mComponentHeight = height;
        if (component instanceof LayoutComponent) {
            LayoutComponent layoutComponent = (LayoutComponent) component;
            setContentWidth(layoutComponent.intrinsicWidth(context));
            setContentHeight(layoutComponent.intrinsicHeight(context));
        }
    }
}
