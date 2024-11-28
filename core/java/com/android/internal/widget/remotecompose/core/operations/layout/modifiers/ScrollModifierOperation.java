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

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.CoreDocument;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.Utils;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.layout.RootLayoutComponent;
import com.android.internal.widget.remotecompose.core.operations.layout.TouchHandler;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.List;

/** Represents a scroll modifier. */
public class ScrollModifierOperation extends DecoratorModifierOperation implements TouchHandler {
    private static final int OP_CODE = Operations.MODIFIER_SCROLL;
    public static final String CLASS_NAME = "ScrollModifierOperation";

    private final float mPositionExpression;
    private final float mMax;
    private final float mNotchMax;

    float mWidth = 0;
    float mHeight = 0;

    int mDirection;

    float mTouchDownX;
    float mTouchDownY;

    float mInitialScrollX;
    float mInitialScrollY;

    float mScrollX;
    float mScrollY;

    float mMaxScrollX;
    float mMaxScrollY;

    float mHostDimension;
    float mContentDimension;

    public ScrollModifierOperation(int direction, float position, float max, float notchMax) {
        this.mDirection = direction;
        this.mPositionExpression = position;
        this.mMax = max;
        this.mNotchMax = notchMax;
    }

    public boolean isVerticalScroll() {
        return mDirection == 0;
    }

    public boolean isHorizontalScroll() {
        return mDirection != 0;
    }

    public float getScrollX() {
        return mScrollX;
    }

    public float getScrollY() {
        return mScrollY;
    }

    @Override
    public void apply(RemoteContext context) {
        RootLayoutComponent root = context.getDocument().getRootLayoutComponent();
        if (root != null) {
            root.setHasTouchListeners(true);
        }
        super.apply(context);
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer, mDirection, mPositionExpression, mMax, mNotchMax);
    }

    // @Override
    public void serializeToString(int indent, StringSerializer serializer) {
        serializer.append(indent, "SCROLL = [" + mDirection + "]");
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return (indent != null ? indent : "") + toString();
    }

    @Override
    public void paint(PaintContext context) {
        float position =
                context.getContext()
                        .mRemoteComposeState
                        .getFloat(Utils.idFromNan(mPositionExpression));

        if (mDirection == 0) {
            mScrollY = -position;
        } else {
            mScrollX = -position;
        }
    }

    @Override
    public String toString() {
        return "ScrollModifierOperation(" + mDirection + ")";
    }

    public static String name() {
        return CLASS_NAME;
    }

    /**
     * The OP_CODE for this command
     *
     * @return the opcode
     */
    public static int id() {
        return OP_CODE;
    }

    public static void apply(
            WireBuffer buffer, int direction, float position, float max, float notchMax) {
        buffer.start(OP_CODE);
        buffer.writeInt(direction);
        buffer.writeFloat(position);
        buffer.writeFloat(max);
        buffer.writeFloat(notchMax);
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        int direction = buffer.readInt();
        float position = buffer.readFloat();
        float max = buffer.readFloat();
        float notchMax = buffer.readFloat();
        operations.add(new ScrollModifierOperation(direction, position, max, notchMax));
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Modifier Operations", OP_CODE, CLASS_NAME)
                .description("define a Scroll Modifier")
                .field(INT, "direction", "");
    }

    @Override
    public void layout(RemoteContext context, float width, float height) {
        mWidth = width;
        mHeight = height;
        if (mDirection == 0) { // VERTICAL
            context.loadFloat(Utils.idFromNan(mMax), mMaxScrollY);
            context.loadFloat(Utils.idFromNan(mNotchMax), mContentDimension);
        } else {
            context.loadFloat(Utils.idFromNan(mMax), mMaxScrollX);
            context.loadFloat(Utils.idFromNan(mNotchMax), mContentDimension);
        }
    }

    @Override
    public void onTouchDown(
            RemoteContext context, CoreDocument document, Component component, float x, float y) {
        mTouchDownX = x;
        mTouchDownY = y;
        mInitialScrollX = mScrollX;
        mInitialScrollY = mScrollY;
        document.appliedTouchOperation(component);
    }

    @Override
    public void onTouchUp(
            RemoteContext context, CoreDocument document, Component component, float x, float y) {
        // If not using touch expression, should add velocity decay here
    }

    @Override
    public void onTouchDrag(
            RemoteContext context, CoreDocument document, Component component, float x, float y) {
        float dx = x - mTouchDownX;
        float dy = y - mTouchDownY;

        if (!Utils.isVariable(mPositionExpression)) {
            if (mDirection == 0) {
                mScrollY = Math.max(-mMaxScrollY, Math.min(0, mInitialScrollY + dy));
            } else {
                mScrollX = Math.max(-mMaxScrollX, Math.min(0, mInitialScrollX + dx));
            }
        }
    }

    @Override
    public void onTouchCancel(
            RemoteContext context, CoreDocument document, Component component, float x, float y) {}

    public void setHorizontalScrollDimension(float hostDimension, float contentDimension) {
        mHostDimension = hostDimension;
        mContentDimension = contentDimension;
        mMaxScrollX = contentDimension - hostDimension;
    }

    public void setVerticalScrollDimension(float hostDimension, float contentDimension) {
        mHostDimension = hostDimension;
        mContentDimension = contentDimension;
        mMaxScrollY = contentDimension - hostDimension;
    }

    public float getContentDimension() {
        return mContentDimension;
    }
}
