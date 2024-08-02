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
package com.android.internal.widget.remotecompose.core.operations.layout;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedCompanionOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.Measurable;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.MeasurePass;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ComponentModifiers;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.List;

/**
 * Represents the root layout component. Entry point to the component tree layout/paint.
 */
public class RootLayoutComponent extends Component implements ComponentStartOperation {

    public static final RootLayoutComponent.Companion COMPANION =
            new RootLayoutComponent.Companion();

    int mCurrentId = -1;

    public RootLayoutComponent(int componentId, float x, float y,
                               float width, float height, Component parent, int animationId) {
        super(parent, componentId, animationId, x, y, width, height);
    }

    public RootLayoutComponent(int componentId, float x, float y,
                               float width, float height, Component parent) {
        super(parent, componentId, -1, x, y, width, height);
    }

    @Override
    public String toString() {
        return "ROOT (" + mX + ", " + mY + " - " + mWidth + " x " + mHeight + ") " + mVisibility;
    }

    @Override
    public void serializeToString(int indent, StringSerializer serializer) {
        serializer.append(indent, "ROOT [" + mComponentId + ":" + mAnimationId
                + "] = [" + mX + ", " + mY + ", " + mWidth + ", " + mHeight + "] " + mVisibility);
    }

    public int getNextId() {
        mCurrentId--;
        return mCurrentId;
    }

    public void assignIds() {
        assignId(this);
    }

    void assignId(Component component) {
        if (component.mComponentId == -1) {
            component.mComponentId = getNextId();
        }
        for (Operation op : component.mList) {
            if (op instanceof Component) {
                assignId((Component) op);
            }
        }
    }

    /**
     * This will measure then layout the tree of components
     */
    public void layout(RemoteContext context) {
        if (!mNeedsMeasure) {
            return;
        }
        context.lastComponent = this;
        mWidth = context.mWidth;
        mHeight = context.mHeight;

        // TODO: reuse MeasurePass
        MeasurePass measurePass = new MeasurePass();
        for (Operation op : mList) {
            if (op instanceof Measurable) {
                Measurable m = (Measurable) op;
                m.measure(context.getPaintContext(),
                        0f, mWidth, 0f, mHeight, measurePass);
                m.layout(context, measurePass);
            }
        }
        mNeedsMeasure = false;
    }

    @Override
    public void paint(PaintContext context) {
        mNeedsRepaint = false;
        context.getContext().lastComponent = this;
        context.save();

        if (mParent == null) { // root layout
            context.clipRect(0f, 0f, mWidth, mHeight);
        }

        for (Operation op : mList) {
            if (op instanceof PaintOperation) {
                ((PaintOperation) op).paint(context);
            }
        }

        context.restore();
    }

    public String displayHierarchy() {
        StringSerializer serializer = new StringSerializer();
        displayHierarchy(this, 0, serializer);
        return serializer.toString();
    }

    public void displayHierarchy(Component component, int indent, StringSerializer serializer) {
        component.serializeToString(indent, serializer);
        for (Operation c : component.mList) {
            if (c instanceof ComponentModifiers) {
                ((ComponentModifiers) c).serializeToString(indent + 1, serializer);
            }
            if (c instanceof Component) {
                displayHierarchy((Component) c, indent + 1, serializer);
            }
        }
    }

    public static class Companion implements DocumentedCompanionOperation {
        @Override
        public String name() {
            return "RootLayout";
        }

        @Override
        public int id() {
            return Operations.LAYOUT_ROOT;
        }

        public void apply(WireBuffer buffer) {
            buffer.start(Operations.LAYOUT_ROOT);
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            operations.add(new RootLayoutComponent(
                    -1, 0, 0, 0, 0, null, -1));
        }

        @Override
        public void documentation(DocumentationBuilder doc) {
            doc.operation("Layout Operations", id(), name())
                    .description("Root element for a document. Other components / layout managers "
                         + "are children in the component tree starting from this Root component.");
        }
    }
}
