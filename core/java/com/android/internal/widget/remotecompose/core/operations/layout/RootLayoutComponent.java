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

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.SerializableToString;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.Measurable;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.MeasurePass;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ComponentModifiers;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.List;

/** Represents the root layout component. Entry point to the component tree layout/paint. */
public class RootLayoutComponent extends Component implements ComponentStartOperation {
    private int mCurrentId = -1;
    private boolean mHasTouchListeners = false;

    public RootLayoutComponent(
            int componentId,
            float x,
            float y,
            float width,
            float height,
            @Nullable Component parent,
            int animationId) {
        super(parent, componentId, animationId, x, y, width, height);
    }

    public RootLayoutComponent(
            int componentId,
            float x,
            float y,
            float width,
            float height,
            @Nullable Component parent) {
        super(parent, componentId, -1, x, y, width, height);
    }

    @NonNull
    @Override
    public String toString() {
        return "ROOT "
                + mComponentId
                + " ("
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

    @Override
    public void serializeToString(int indent, @NonNull StringSerializer serializer) {
        serializer.append(
                indent,
                "ROOT ["
                        + mComponentId
                        + ":"
                        + mAnimationId
                        + "] = ["
                        + mX
                        + ", "
                        + mY
                        + ", "
                        + mWidth
                        + ", "
                        + mHeight
                        + "] "
                        + mVisibility);
    }

    /**
     * Set the flag to traverse the tree when touch events happen
     *
     * @param value true to indicate that the tree has touch listeners
     */
    public void setHasTouchListeners(boolean value) {
        mHasTouchListeners = value;
    }

    /**
     * Traverse the hierarchy and assign generated ids to component without ids. Most components
     * would already have ids assigned during the document creation, but this allow us to take care
     * of any components added during the inflation.
     *
     * @param lastId the last known generated id
     */
    public void assignIds(int lastId) {
        mCurrentId = lastId;
        assignId(this);
    }

    private void assignId(@NonNull Component component) {
        if (component.mComponentId == -1) {
            mCurrentId--;
            component.mComponentId = mCurrentId;
        }
        for (Operation op : component.mList) {
            if (op instanceof Component) {
                assignId((Component) op);
            }
        }
    }

    /** This will measure then layout the tree of components */
    public void layout(@NonNull RemoteContext context) {
        if (!mNeedsMeasure) {
            return;
        }
        context.mLastComponent = this;
        setWidth(context.mWidth);
        setHeight(context.mHeight);

        // TODO: reuse MeasurePass
        MeasurePass measurePass = new MeasurePass();
        for (Operation op : mList) {
            if (op instanceof Measurable) {
                Measurable m = (Measurable) op;
                m.measure(context.getPaintContext(), 0f, mWidth, 0f, mHeight, measurePass);
                m.layout(context, measurePass);
            }
        }
        mNeedsMeasure = false;
    }

    @Override
    public void paint(@NonNull PaintContext context) {
        mNeedsRepaint = false;
        RemoteContext remoteContext = context.getContext();
        remoteContext.mLastComponent = this;

        context.save();

        if (mParent == null) { // root layout
            context.clipRect(0f, 0f, mWidth, mHeight);
        }

        for (Operation op : mList) {
            if (op instanceof PaintOperation) {
                ((PaintOperation) op).paint(context);
                remoteContext.incrementOpCount();
            }
        }

        context.restore();
    }

    @NonNull
    public String displayHierarchy() {
        StringSerializer serializer = new StringSerializer();
        displayHierarchy(this, 0, serializer);
        return serializer.toString();
    }

    public void displayHierarchy(
            @NonNull Component component, int indent, @NonNull StringSerializer serializer) {
        component.serializeToString(indent, serializer);
        for (Operation c : component.mList) {
            if (c instanceof ComponentModifiers) {
                ((ComponentModifiers) c).serializeToString(indent + 1, serializer);
            } else if (c instanceof Component) {
                displayHierarchy((Component) c, indent + 1, serializer);
            } else if (c instanceof SerializableToString) {
                ((SerializableToString) c).serializeToString(indent + 1, serializer);
            }
        }
    }

    /**
     * The name of the class
     *
     * @return the name
     */
    @NonNull
    public static String name() {
        return "RootLayout";
    }

    /**
     * The OP_CODE for this command
     *
     * @return the opcode
     */
    public static int id() {
        return Operations.LAYOUT_ROOT;
    }

    public static void apply(@NonNull WireBuffer buffer, int componentId) {
        buffer.start(Operations.LAYOUT_ROOT);
        buffer.writeInt(componentId);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int componentId = buffer.readInt();
        operations.add(new RootLayoutComponent(componentId, 0, 0, 0, 0, null, -1));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Layout Operations", id(), name())
                .field(INT, "COMPONENT_ID", "unique id for this component")
                .description(
                        "Root element for a document. Other components / layout managers are"
                                + " children in the component tree starting from"
                                + "this Root component.");
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mComponentId);
    }

    public boolean hasTouchListeners() {
        return mHasTouchListeners;
    }
}
