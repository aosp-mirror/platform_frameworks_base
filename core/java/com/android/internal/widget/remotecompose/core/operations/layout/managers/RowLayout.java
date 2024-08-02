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

import static com.android.internal.widget.remotecompose.core.documentation.Operation.FLOAT;
import static com.android.internal.widget.remotecompose.core.documentation.Operation.INT;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedCompanionOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.layout.ComponentStartOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.LayoutComponent;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.ComponentMeasure;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.MeasurePass;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.Size;
import com.android.internal.widget.remotecompose.core.operations.layout.utils.DebugLog;

import java.util.List;

/**
 * Simple Row layout implementation
 * - also supports weight and horizontal/vertical positioning
 */
public class RowLayout extends LayoutManager implements ComponentStartOperation {
    public static final int START = 1;
    public static final int CENTER = 2;
    public static final int END = 3;
    public static final int TOP = 4;
    public static final int BOTTOM = 5;
    public static final int SPACE_BETWEEN = 6;
    public static final int SPACE_EVENLY = 7;
    public static final int SPACE_AROUND = 8;

    public static final RowLayout.Companion COMPANION = new RowLayout.Companion();

    int mHorizontalPositioning;
    int mVerticalPositioning;
    float mSpacedBy = 0f;

    public RowLayout(Component parent, int componentId, int animationId,
                     float x, float y, float width, float height,
                     int horizontalPositioning, int verticalPositioning, float spacedBy) {
        super(parent, componentId, animationId, x, y, width, height);
        mHorizontalPositioning = horizontalPositioning;
        mVerticalPositioning = verticalPositioning;
        mSpacedBy = spacedBy;
    }

    public RowLayout(Component parent, int componentId, int animationId,
                     int horizontalPositioning, int verticalPositioning, float spacedBy) {
        this(parent, componentId, animationId, 0, 0, 0, 0,
                horizontalPositioning, verticalPositioning, spacedBy);
    }
    @Override
    public String toString() {
        return "ROW [" + mComponentId + ":" + mAnimationId + "] (" + mX + ", "
                + mY + " - " + mWidth + " x " + mHeight + ") " + mVisibility;
    }

    protected String getSerializedName() {
        return "ROW";
    }

    @Override
    public void computeWrapSize(PaintContext context, float maxWidth, float maxHeight,
                                MeasurePass measure, Size size) {
        DebugLog.s(() -> "COMPUTE WRAP SIZE in " + this + " (" + mComponentId + ")");
        for (Component c : mChildrenComponents) {
            c.measure(context, 0f, maxWidth, 0f, maxHeight, measure);
            ComponentMeasure m = measure.get(c);
            size.setWidth(size.getWidth() + m.getW());
            size.setHeight(Math.max(size.getHeight(), m.getH()));
        }
        if (!mChildrenComponents.isEmpty()) {
            size.setWidth(size.getWidth()
                    + (mSpacedBy * (mChildrenComponents.size() - 1)));
        }
        DebugLog.e();
    }

    @Override
    public void computeSize(PaintContext context, float minWidth, float maxWidth,
                            float minHeight, float maxHeight, MeasurePass measure) {
        DebugLog.s(() -> "COMPUTE SIZE in " + this + " (" + mComponentId + ")");
        float mw = maxWidth;
        for (Component child : mChildrenComponents) {
            child.measure(context, minWidth, mw, minHeight, maxHeight, measure);
            ComponentMeasure m = measure.get(child);
            mw -= m.getW();
        }
        DebugLog.e();
    }

    @Override
    public void internalLayoutMeasure(PaintContext context,
                                      MeasurePass measure) {
        ComponentMeasure selfMeasure = measure.get(this);
        DebugLog.s(() -> "INTERNAL LAYOUT " + this + " (" + mComponentId + ") children: "
                + mChildrenComponents.size() + " size (" + selfMeasure.getW()
                + " x " + selfMeasure.getH() + ")");
        if (mChildrenComponents.isEmpty()) {
            DebugLog.e();
            return;
        }
        float selfWidth = selfMeasure.getW() - mPaddingLeft - mPaddingRight;
        float selfHeight = selfMeasure.getH() - mPaddingTop - mPaddingBottom;
        float childrenWidth = 0f;
        float childrenHeight = 0f;

        boolean hasWeights = false;
        float totalWeights = 0f;
        for (Component child : mChildrenComponents) {
            ComponentMeasure childMeasure = measure.get(child);
            if (child instanceof LayoutComponent
                    && ((LayoutComponent) child).getWidthModifier().hasWeight()) {
                hasWeights = true;
                totalWeights += ((LayoutComponent) child).getWidthModifier().getValue();
            } else {
                childrenWidth += childMeasure.getW();
            }
        }

        // TODO: need to move the weight measuring in the measure function,
        // currently we'll measure unnecessarily
        if (hasWeights) {
            float availableSpace = selfWidth - childrenWidth;
            for (Component child : mChildrenComponents) {
                if (child instanceof LayoutComponent
                        && ((LayoutComponent) child).getWidthModifier().hasWeight()) {
                    ComponentMeasure childMeasure = measure.get(child);
                    float weight = ((LayoutComponent) child).getWidthModifier().getValue();
                    childMeasure.setW((weight * availableSpace) / totalWeights);
                    child.measure(context, childMeasure.getW(),
                            childMeasure.getW(), childMeasure.getH(), childMeasure.getH(), measure);
                }
            }
        }

        childrenWidth = 0f;
        for (Component child : mChildrenComponents) {
            ComponentMeasure childMeasure = measure.get(child);
            childrenWidth += childMeasure.getW();
            childrenHeight = Math.max(childrenHeight, childMeasure.getH());
        }
        childrenWidth += mSpacedBy * (mChildrenComponents.size() - 1);

        float tx = 0f;
        float ty = 0f;

        float horizontalGap = 0f;
        float total = 0f;

        switch (mHorizontalPositioning) {
            case START:
                tx = 0f;
                break;
            case END:
                tx = selfWidth - childrenWidth;
                break;
            case CENTER:
                tx = (selfWidth - childrenWidth) / 2f;
                break;
            case SPACE_BETWEEN:
                for (Component child : mChildrenComponents) {
                    ComponentMeasure childMeasure = measure.get(child);
                    total += childMeasure.getW();
                }
                horizontalGap = (selfWidth - total) / (mChildrenComponents.size() - 1);
                break;
            case SPACE_EVENLY:
                for (Component child : mChildrenComponents) {
                    ComponentMeasure childMeasure = measure.get(child);
                    total += childMeasure.getW();
                }
                horizontalGap = (selfWidth - total) / (mChildrenComponents.size() + 1);
                tx = horizontalGap;
                break;
            case SPACE_AROUND:
                for (Component child : mChildrenComponents) {
                    ComponentMeasure childMeasure = measure.get(child);
                    total += childMeasure.getW();
                }
                horizontalGap = (selfWidth - total) / (mChildrenComponents.size());
                tx = horizontalGap / 2f;
                break;
        }

        for (Component child : mChildrenComponents) {
            ComponentMeasure childMeasure = measure.get(child);
            switch (mVerticalPositioning) {
                case TOP:
                    ty = 0f;
                    break;
                case CENTER:
                    ty = (selfHeight - childMeasure.getH()) / 2f;
                    break;
                case BOTTOM:
                    ty = selfHeight - childMeasure.getH();
                    break;
            }
            childMeasure.setX(tx);
            childMeasure.setY(ty);
            childMeasure.setVisibility(child.mVisibility);
            tx += childMeasure.getW();
            if (mHorizontalPositioning == SPACE_BETWEEN
                    || mHorizontalPositioning == SPACE_AROUND
                    || mHorizontalPositioning == SPACE_EVENLY) {
                tx += horizontalGap;
            }
            tx += mSpacedBy;
        }
        DebugLog.e();
    }

    public static class Companion implements DocumentedCompanionOperation {
        @Override
        public String name() {
            return "RowLayout";
        }

        @Override
        public int id() {
            return Operations.LAYOUT_ROW;
        }

        public void apply(WireBuffer buffer, int componentId, int animationId,
                          int horizontalPositioning, int verticalPositioning, float spacedBy) {
            buffer.start(Operations.LAYOUT_ROW);
            buffer.writeInt(componentId);
            buffer.writeInt(animationId);
            buffer.writeInt(horizontalPositioning);
            buffer.writeInt(verticalPositioning);
            buffer.writeFloat(spacedBy);
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            int componentId = buffer.readInt();
            int animationId = buffer.readInt();
            int horizontalPositioning = buffer.readInt();
            int verticalPositioning = buffer.readInt();
            float spacedBy = buffer.readFloat();
            operations.add(new RowLayout(null, componentId, animationId,
                    horizontalPositioning, verticalPositioning, spacedBy));
        }

        @Override
        public void documentation(DocumentationBuilder doc) {
            doc.operation("Layout Operations", id(), name())
                    .description("Row layout implementation, positioning components one"
                            + " after the other horizontally.\n\n"
                            + "It supports weight and horizontal/vertical positioning.")
                    .examplesDimension(400, 100)
                    .exampleImage("Start", "layout-RowLayout-start-top.png")
                    .exampleImage("Center", "layout-RowLayout-center-top.png")
                    .exampleImage("End", "layout-RowLayout-end-top.png")
                    .exampleImage("SpaceEvenly", "layout-RowLayout-space-evenly-top.png")
                    .exampleImage("SpaceAround", "layout-RowLayout-space-around-top.png")
                    .exampleImage("SpaceBetween", "layout-RowLayout-space-between-top.png")
                    .field(INT, "COMPONENT_ID", "unique id for this component")
                    .field(INT, "ANIMATION_ID", "id used to match components,"
                          + " for animation purposes")
                    .field(INT, "HORIZONTAL_POSITIONING", "horizontal positioning value")
                    .possibleValues("START", RowLayout.START)
                    .possibleValues("CENTER", RowLayout.CENTER)
                    .possibleValues("END", RowLayout.END)
                    .possibleValues("SPACE_BETWEEN", RowLayout.SPACE_BETWEEN)
                    .possibleValues("SPACE_EVENLY", RowLayout.SPACE_EVENLY)
                    .possibleValues("SPACE_AROUND", RowLayout.SPACE_AROUND)
                    .field(INT, "VERTICAL_POSITIONING", "vertical positioning value")
                    .possibleValues("TOP", RowLayout.TOP)
                    .possibleValues("CENTER", RowLayout.CENTER)
                    .possibleValues("BOTTOM", RowLayout.BOTTOM)
                    .field(FLOAT, "SPACED_BY", "Horizontal spacing between components");
        }
    }
}
