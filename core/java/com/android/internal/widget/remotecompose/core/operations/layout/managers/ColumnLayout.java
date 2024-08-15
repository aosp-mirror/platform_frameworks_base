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
 * Simple Column layout implementation
 * - also supports weight and horizontal/vertical positioning
 */
public class ColumnLayout extends LayoutManager implements ComponentStartOperation {
    public static final int START = 1;
    public static final int CENTER = 2;
    public static final int END = 3;
    public static final int TOP = 4;
    public static final int BOTTOM = 5;
    public static final int SPACE_BETWEEN = 6;
    public static final int SPACE_EVENLY = 7;
    public static final int SPACE_AROUND = 8;

    public static final ColumnLayout.Companion COMPANION = new ColumnLayout.Companion();

    int mHorizontalPositioning;
    int mVerticalPositioning;
    float mSpacedBy = 0f;

    public ColumnLayout(Component parent, int componentId, int animationId,
                        float x, float y, float width, float height,
                        int horizontalPositioning, int verticalPositioning, float spacedBy) {
        super(parent, componentId, animationId, x, y, width, height);
        mHorizontalPositioning = horizontalPositioning;
        mVerticalPositioning = verticalPositioning;
        mSpacedBy = spacedBy;
    }

    public ColumnLayout(Component parent, int componentId, int animationId,
                     int horizontalPositioning, int verticalPositioning, float spacedBy) {
        this(parent, componentId, animationId, 0, 0, 0, 0,
                horizontalPositioning, verticalPositioning, spacedBy);
    }

    @Override
    public String toString() {
        return "COLUMN [" + mComponentId + ":" + mAnimationId + "] (" + mX + ", "
                + mY + " - " + mWidth + " x " + mHeight + ") " + mVisibility;
    }

    protected String getSerializedName() {
        return "COLUMN";
    }

    @Override
    public void computeWrapSize(PaintContext context, float maxWidth, float maxHeight,
                                MeasurePass measure, Size size) {
        DebugLog.s(() -> "COMPUTE WRAP SIZE in " + this + " (" + mComponentId + ")");
        for (Component c : mChildrenComponents) {
            c.measure(context, 0f, maxWidth,
                    0f, maxHeight, measure);
            ComponentMeasure m = measure.get(c);
            size.setWidth(Math.max(size.getWidth(), m.getW()));
            size.setHeight(size.getHeight() + m.getH());
        }
        if (!mChildrenComponents.isEmpty()) {
            size.setHeight(size.getHeight()
                    + (mSpacedBy * (mChildrenComponents.size() - 1)));
        }
        DebugLog.e();
    }

    @Override
    public void computeSize(PaintContext context, float minWidth, float maxWidth,
                            float minHeight, float maxHeight, MeasurePass measure) {
        DebugLog.s(() -> "COMPUTE SIZE in " + this + " (" + mComponentId + ")");
        float mh = maxHeight;
        for (Component child : mChildrenComponents) {
            child.measure(context, minWidth, maxWidth, minHeight, mh, measure);
            ComponentMeasure m = measure.get(child);
            mh -= m.getH();
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
                    && ((LayoutComponent) child).getHeightModifier().hasWeight()) {
                hasWeights = true;
                totalWeights += ((LayoutComponent) child).getHeightModifier().getValue();
            } else {
                childrenHeight += childMeasure.getH();
            }
        }
        if (hasWeights) {
            float availableSpace = selfHeight - childrenHeight;
            for (Component child : mChildrenComponents) {
                if (child instanceof LayoutComponent
                        && ((LayoutComponent) child).getHeightModifier().hasWeight()) {
                    ComponentMeasure childMeasure = measure.get(child);
                    float weight = ((LayoutComponent) child).getHeightModifier().getValue();
                    childMeasure.setH((weight * availableSpace) / totalWeights);
                    child.measure(context, childMeasure.getW(),
                            childMeasure.getW(), childMeasure.getH(), childMeasure.getH(), measure);
                }
            }
        }

        childrenHeight = 0f;
        for (Component child : mChildrenComponents) {
            ComponentMeasure childMeasure = measure.get(child);
            childrenWidth = Math.max(childrenWidth, childMeasure.getW());
            childrenHeight += childMeasure.getH();
        }
        childrenHeight += mSpacedBy * (mChildrenComponents.size() - 1);

        float tx = 0f;
        float ty = 0f;

        float verticalGap = 0f;
        float total = 0f;
        switch (mVerticalPositioning) {
            case TOP:
                ty = 0f;
                break;
            case CENTER:
                ty = (selfHeight - childrenHeight) / 2f;
                break;
            case BOTTOM:
                ty = selfHeight - childrenHeight;
                break;
            case SPACE_BETWEEN:
                for (Component child : mChildrenComponents) {
                    ComponentMeasure childMeasure = measure.get(child);
                    total += childMeasure.getH();
                }
                verticalGap = (selfHeight - total) / (mChildrenComponents.size() - 1);
                break;
            case SPACE_EVENLY:
                for (Component child : mChildrenComponents) {
                    ComponentMeasure childMeasure = measure.get(child);
                    total += childMeasure.getH();
                }
                verticalGap = (selfHeight - total) / (mChildrenComponents.size() + 1);
                ty = verticalGap;
                break;
            case SPACE_AROUND:
                for (Component child : mChildrenComponents) {
                    ComponentMeasure childMeasure = measure.get(child);
                    total += childMeasure.getH();
                }
                verticalGap = (selfHeight - total) / (mChildrenComponents.size());
                ty = verticalGap / 2f;
                break;
        }
        for (Component child : mChildrenComponents) {
            ComponentMeasure childMeasure = measure.get(child);
            switch (mHorizontalPositioning) {
                case START:
                    tx = 0f;
                    break;
                case CENTER:
                    tx = (selfWidth - childMeasure.getW()) / 2f;
                    break;
                case END:
                    tx = selfWidth - childMeasure.getW();
                    break;
            }
            childMeasure.setX(tx);
            childMeasure.setY(ty);
            childMeasure.setVisibility(child.mVisibility);
            ty += childMeasure.getH();
            if (mVerticalPositioning == SPACE_BETWEEN
                    || mVerticalPositioning == SPACE_AROUND
                    || mVerticalPositioning == SPACE_EVENLY) {
                ty += verticalGap;
            }
            ty += mSpacedBy;
        }
        DebugLog.e();
    }

    public static class Companion implements DocumentedCompanionOperation {
        @Override
        public String name() {
            return "ColumnLayout";
        }

        @Override
        public int id() {
            return Operations.LAYOUT_COLUMN;
        }

        public void apply(WireBuffer buffer, int componentId, int animationId,
                          int horizontalPositioning, int verticalPositioning, float spacedBy) {
            buffer.start(Operations.LAYOUT_COLUMN);
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
            operations.add(new ColumnLayout(null, componentId, animationId,
                    horizontalPositioning, verticalPositioning, spacedBy));
        }

        @Override
        public void documentation(DocumentationBuilder doc) {
            doc.operation("Layout Operations", id(), name())
               .description("Column layout implementation, positioning components one"
                       + " after the other vertically.\n\n"
                       + "It supports weight and horizontal/vertical positioning.")
               .examplesDimension(100, 400)
               .exampleImage("Top", "layout-ColumnLayout-start-top.png")
               .exampleImage("Center", "layout-ColumnLayout-start-center.png")
               .exampleImage("Bottom", "layout-ColumnLayout-start-bottom.png")
               .exampleImage("SpaceEvenly", "layout-ColumnLayout-start-space-evenly.png")
               .exampleImage("SpaceAround", "layout-ColumnLayout-start-space-around.png")
               .exampleImage("SpaceBetween", "layout-ColumnLayout-start-space-between.png")
               .field(INT, "COMPONENT_ID", "unique id for this component")
               .field(INT, "ANIMATION_ID", "id used to match components,"
                       + " for animation purposes")
               .field(INT, "HORIZONTAL_POSITIONING", "horizontal positioning value")
               .possibleValues("START", ColumnLayout.START)
               .possibleValues("CENTER", ColumnLayout.CENTER)
               .possibleValues("END", ColumnLayout.END)
               .field(INT, "VERTICAL_POSITIONING", "vertical positioning value")
               .possibleValues("TOP", ColumnLayout.TOP)
               .possibleValues("CENTER", ColumnLayout.CENTER)
               .possibleValues("BOTTOM", ColumnLayout.BOTTOM)
               .possibleValues("SPACE_BETWEEN", ColumnLayout.SPACE_BETWEEN)
               .possibleValues("SPACE_EVENLY", ColumnLayout.SPACE_EVENLY)
               .possibleValues("SPACE_AROUND", ColumnLayout.SPACE_AROUND)
                    .field(FLOAT, "SPACED_BY", "Horizontal spacing between components");
        }
    }
}
