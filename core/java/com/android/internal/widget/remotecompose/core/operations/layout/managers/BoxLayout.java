/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations.layout.managers;

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.layout.ComponentStartOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.ComponentMeasure;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.MeasurePass;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.Size;

import java.util.List;

/** Simple Box layout implementation */
public class BoxLayout extends LayoutManager implements ComponentStartOperation {

    public static final int START = 1;
    public static final int CENTER = 2;
    public static final int END = 3;
    public static final int TOP = 4;
    public static final int BOTTOM = 5;

    int mHorizontalPositioning;
    int mVerticalPositioning;

    public BoxLayout(
            @Nullable Component parent,
            int componentId,
            int animationId,
            float x,
            float y,
            float width,
            float height,
            int horizontalPositioning,
            int verticalPositioning) {
        super(parent, componentId, animationId, x, y, width, height);
        mHorizontalPositioning = horizontalPositioning;
        mVerticalPositioning = verticalPositioning;
    }

    public BoxLayout(
            @Nullable Component parent,
            int componentId,
            int animationId,
            int horizontalPositioning,
            int verticalPositioning) {
        this(
                parent,
                componentId,
                animationId,
                0,
                0,
                0,
                0,
                horizontalPositioning,
                verticalPositioning);
    }

    @NonNull
    @Override
    public String toString() {
        return "BOX ["
                + mComponentId
                + ":"
                + mAnimationId
                + "] ("
                + mX
                + ", "
                + mY
                + " - "
                + mWidth
                + " x "
                + mHeight
                + ") "
                + mVisibility;
    }

    @NonNull
    @Override
    protected String getSerializedName() {
        return "BOX";
    }

    @Override
    public void computeWrapSize(
            @NonNull PaintContext context,
            float maxWidth,
            float maxHeight,
            boolean horizontalWrap,
            boolean verticalWrap,
            @NonNull MeasurePass measure,
            @NonNull Size size) {
        for (Component c : mChildrenComponents) {
            c.measure(context, 0f, maxWidth, 0f, maxHeight, measure);
            ComponentMeasure m = measure.get(c);
            size.setWidth(Math.max(size.getWidth(), m.getW()));
            size.setHeight(Math.max(size.getHeight(), m.getH()));
        }
        // add padding
        size.setWidth(Math.max(size.getWidth(), computeModifierDefinedWidth(context.getContext())));
        size.setHeight(
                Math.max(size.getHeight(), computeModifierDefinedHeight(context.getContext())));
    }

    @Override
    public void computeSize(
            @NonNull PaintContext context,
            float minWidth,
            float maxWidth,
            float minHeight,
            float maxHeight,
            @NonNull MeasurePass measure) {
        for (Component child : mChildrenComponents) {
            child.measure(context, minWidth, maxWidth, minHeight, maxHeight, measure);
        }
    }

    @Override
    public void internalLayoutMeasure(@NonNull PaintContext context, @NonNull MeasurePass measure) {
        ComponentMeasure selfMeasure = measure.get(this);
        float selfWidth = selfMeasure.getW() - mPaddingLeft - mPaddingRight;
        float selfHeight = selfMeasure.getH() - mPaddingTop - mPaddingBottom;
        for (Component child : mChildrenComponents) {
            ComponentMeasure m = measure.get(child);
            float tx = 0f;
            float ty = 0f;
            switch (mVerticalPositioning) {
                case TOP:
                    ty = 0f;
                    break;
                case CENTER:
                    ty = (selfHeight - m.getH()) / 2f;
                    break;
                case BOTTOM:
                    ty = selfHeight - m.getH();
                    break;
            }
            switch (mHorizontalPositioning) {
                case START:
                    tx = 0f;
                    break;
                case CENTER:
                    tx = (selfWidth - m.getW()) / 2f;
                    break;
                case END:
                    tx = selfWidth - m.getW();
                    break;
            }
            m.setX(tx);
            m.setY(ty);
        }
    }

    /**
     * The name of the class
     *
     * @return the name
     */
    @NonNull
    public static String name() {
        return "BoxLayout";
    }

    /**
     * The OP_CODE for this command
     *
     * @return the opcode
     */
    public static int id() {
        return Operations.LAYOUT_BOX;
    }

    public static void apply(
            @NonNull WireBuffer buffer,
            int componentId,
            int animationId,
            int horizontalPositioning,
            int verticalPositioning) {
        buffer.start(Operations.LAYOUT_BOX);
        buffer.writeInt(componentId);
        buffer.writeInt(animationId);
        buffer.writeInt(horizontalPositioning);
        buffer.writeInt(verticalPositioning);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int componentId = buffer.readInt();
        int animationId = buffer.readInt();
        int horizontalPositioning = buffer.readInt();
        int verticalPositioning = buffer.readInt();
        operations.add(
                new BoxLayout(
                        null,
                        componentId,
                        animationId,
                        horizontalPositioning,
                        verticalPositioning));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Layout Operations", id(), name())
                .description(
                        "Box layout implementation.\n\n"
                                + "Child components are laid out independently from one another,\n"
                                + " and painted in their hierarchy order (first children drawn"
                                + "before the latter). Horizontal and Vertical positioning"
                                + "are supported.")
                .examplesDimension(150, 100)
                .exampleImage("Top", "layout-BoxLayout-start-top.png")
                .exampleImage("Center", "layout-BoxLayout-center-center.png")
                .exampleImage("Bottom", "layout-BoxLayout-end-bottom.png")
                .field(INT, "COMPONENT_ID", "unique id for this component")
                .field(
                        INT,
                        "ANIMATION_ID",
                        "id used to match components," + " for animation purposes")
                .field(INT, "HORIZONTAL_POSITIONING", "horizontal positioning value")
                .possibleValues("START", BoxLayout.START)
                .possibleValues("CENTER", BoxLayout.CENTER)
                .possibleValues("END", BoxLayout.END)
                .field(INT, "VERTICAL_POSITIONING", "vertical positioning value")
                .possibleValues("TOP", BoxLayout.TOP)
                .possibleValues("CENTER", BoxLayout.CENTER)
                .possibleValues("BOTTOM", BoxLayout.BOTTOM);
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mComponentId, mAnimationId, mHorizontalPositioning, mVerticalPositioning);
    }
}
