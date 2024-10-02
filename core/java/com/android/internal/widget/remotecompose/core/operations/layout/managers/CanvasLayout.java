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

import static com.android.internal.widget.remotecompose.core.documentation.Operation.INT;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.ComponentMeasure;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.MeasurePass;

import java.util.List;

public class CanvasLayout extends BoxLayout {
    public CanvasLayout(Component parent, int componentId, int animationId,
                        float x, float y, float width, float height) {
        super(parent, componentId, animationId, x, y, width, height, 0, 0);
    }

    public CanvasLayout(Component parent, int componentId, int animationId) {
        this(parent, componentId, animationId, 0, 0, 0, 0);
    }

    @Override
    public String toString() {
        return "CANVAS [" + mComponentId + ":" + mAnimationId + "] (" + mX + ", "
                + mY + " - " + mWidth + " x " + mHeight + ") " + mVisibility;
    }

    protected String getSerializedName() {
        return "CANVAS";
    }

    public static String name() {
        return "CanvasLayout";
    }

    public static int id() {
        return Operations.LAYOUT_CANVAS;
    }

    public static void apply(WireBuffer buffer, int componentId, int animationId) {
        buffer.start(Operations.LAYOUT_CANVAS);
        buffer.writeInt(componentId);
        buffer.writeInt(animationId);
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        int componentId = buffer.readInt();
        int animationId = buffer.readInt();
        operations.add(new CanvasLayout(null, componentId, animationId));
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Layout Operations", id(), name())
                .description("Canvas implementation. Encapsulate draw operations.\n\n")
                .field(INT, "COMPONENT_ID", "unique id for this component")
                .field(INT, "ANIMATION_ID", "id used to match components,"
                        + " for animation purposes");
    }

    @Override
    public void internalLayoutMeasure(PaintContext context,
                                      MeasurePass measure) {
        ComponentMeasure selfMeasure = measure.get(this);
        float selfWidth = selfMeasure.getW() - mPaddingLeft - mPaddingRight;
        float selfHeight = selfMeasure.getH() - mPaddingTop - mPaddingBottom;
        for (Component child : mChildrenComponents) {
            ComponentMeasure m = measure.get(child);
            m.setX(0f);
            m.setY(0f);
            m.setW(selfWidth);
            m.setH(selfHeight);
        }
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer, mComponentId, mAnimationId);
    }
}
