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
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.TouchExpression;
import com.android.internal.widget.remotecompose.core.operations.Utils;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.layout.DecoratorComponent;
import com.android.internal.widget.remotecompose.core.operations.layout.ListActionsOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.RootLayoutComponent;
import com.android.internal.widget.remotecompose.core.operations.layout.ScrollDelegate;
import com.android.internal.widget.remotecompose.core.operations.layout.TouchHandler;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.List;

/** Represents a scroll modifier. */
public class ScrollModifierOperation extends ListActionsOperation
        implements TouchHandler, DecoratorComponent, ScrollDelegate, VariableSupport {
    private static final int OP_CODE = Operations.MODIFIER_SCROLL;
    public static final String CLASS_NAME = "ScrollModifierOperation";

    private final float mPositionExpression;
    private final float mMax;
    private final float mNotchMax;

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

    private TouchExpression mTouchExpression;

    public ScrollModifierOperation(int direction, float position, float max, float notchMax) {
        super("SCROLL_MODIFIER");
        this.mDirection = direction;
        this.mPositionExpression = position;
        this.mMax = max;
        this.mNotchMax = notchMax;
    }

    /**
     * Inflate the operation
     *
     * @param component
     */
    public void inflate(Component component) {
        for (Operation op : mList) {
            if (op instanceof TouchExpression) {
                mTouchExpression = (TouchExpression) op;
                mTouchExpression.setComponent(component);
            }
        }
    }

    @Override
    public void registerListening(@NonNull RemoteContext context) {
        if (mTouchExpression != null) {
            mTouchExpression.registerListening(context);
        }
    }

    @Override
    public void updateVariables(@NonNull RemoteContext context) {
        if (mTouchExpression != null) {
            mTouchExpression.updateVariables(context);
        }
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
        for (Operation op : mList) {
            op.apply(context.getContext());
        }
        if (mTouchExpression == null) {
            return;
        }
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

    /**
     * The name of the class
     *
     * @return the name
     */
    @NonNull
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
    public void layout(RemoteContext context, Component component, float width, float height) {
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
        if (mTouchExpression != null) {
            mTouchExpression.updateVariables(context);
            mTouchExpression.touchDown(context, x + mScrollX, y + mScrollY);
        }
        document.appliedTouchOperation(component);
    }

    @Override
    public void onTouchUp(
            RemoteContext context,
            CoreDocument document,
            Component component,
            float x,
            float y,
            float dx,
            float dy) {
        if (mTouchExpression != null) {
            mTouchExpression.updateVariables(context);
            mTouchExpression.touchUp(context, x + mScrollX, y + mScrollY, dx, dy);
        }
        // If not using touch expression, should add velocity decay here
    }

    @Override
    public void onTouchDrag(
            RemoteContext context, CoreDocument document, Component component, float x, float y) {
        if (mTouchExpression != null) {
            mTouchExpression.updateVariables(context);
            mTouchExpression.touchDrag(context, x + mScrollX, y + mScrollY);
        }
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

    @Override
    public float getScrollX(float currentValue) {
        if (mDirection == 1) {
            return mScrollX;
        }
        return 0f;
    }

    @Override
    public float getScrollY(float currentValue) {
        if (mDirection == 0) {
            return mScrollY;
        }
        return 0f;
    }

    @Override
    public boolean handlesHorizontalScroll() {
        return mDirection == 1;
    }

    @Override
    public boolean handlesVerticalScroll() {
        return mDirection == 0;
    }

    @Override
    public void reset() {
        // nothing here for now
    }
}
