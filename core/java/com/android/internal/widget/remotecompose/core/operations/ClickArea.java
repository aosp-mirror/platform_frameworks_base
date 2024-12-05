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
package com.android.internal.widget.remotecompose.core.operations;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteComposeOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;
import com.android.internal.widget.remotecompose.core.semantics.AccessibleComponent;

import java.util.List;

/** Add a click area to the document */
public class ClickArea extends Operation
        implements RemoteComposeOperation, AccessibleComponent, VariableSupport {
    private static final int OP_CODE = Operations.CLICK_AREA;
    private static final String CLASS_NAME = "ClickArea";
    int mId;
    int mContentDescription;
    float mLeft;
    float mTop;
    float mRight;
    float mBottom;
    float mOutLeft;
    float mOutTop;
    float mOutRight;
    float mOutBottom;
    int mMetadata;

    /**
     * Add a click area to the document
     *
     * @param id the id of the click area, which will be reported in the listener callback on the
     *     player
     * @param contentDescription the content description (used for accessibility, as a textID)
     * @param left left coordinate of the area bounds
     * @param top top coordinate of the area bounds
     * @param right right coordinate of the area bounds
     * @param bottom bottom coordinate of the area bounds
     * @param metadata associated metadata, user-provided (as a textID, pointing to a string)
     */
    public ClickArea(
            int id,
            int contentDescription,
            float left,
            float top,
            float right,
            float bottom,
            int metadata) {
        this.mId = id;
        this.mContentDescription = contentDescription;
        mOutLeft = mLeft = left;
        mOutTop = mTop = top;
        mOutRight = mRight = right;
        mOutBottom = mBottom = bottom;
        mMetadata = metadata;
    }

    @Override
    public void registerListening(@NonNull RemoteContext context) {
        if (Float.isNaN(mLeft)) {
            context.listensTo(Utils.idFromNan(mLeft), this);
        }
        if (Float.isNaN(mTop)) {
            context.listensTo(Utils.idFromNan(mTop), this);
        }
        if (Float.isNaN(mRight)) {
            context.listensTo(Utils.idFromNan(mRight), this);
        }
        if (Float.isNaN(mBottom)) {
            context.listensTo(Utils.idFromNan(mBottom), this);
        }
    }

    @Override
    public void updateVariables(@NonNull RemoteContext context) {
        mOutLeft = Float.isNaN(mLeft) ? context.getFloat(Utils.idFromNan(mLeft)) : mLeft;
        mOutTop = Float.isNaN(mTop) ? context.getFloat(Utils.idFromNan(mTop)) : mTop;
        mRight = Float.isNaN(mRight) ? context.getFloat(Utils.idFromNan(mRight)) : mRight;
        mOutBottom = Float.isNaN(mBottom) ? context.getFloat(Utils.idFromNan(mBottom)) : mBottom;
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mId, mContentDescription, mLeft, mTop, mRight, mBottom, mMetadata);
    }

    @NonNull
    @Override
    public String toString() {
        return "CLICK_AREA <"
                + mId
                + " <"
                + mContentDescription
                + "> "
                + "<"
                + mMetadata
                + ">+"
                + mLeft
                + " "
                + mTop
                + " "
                + mRight
                + " "
                + mBottom
                + "+"
                + " ("
                + (mRight - mLeft)
                + " x "
                + (mBottom - mTop)
                + " }";
    }

    @Override
    public void apply(@NonNull RemoteContext context) {
        context.addClickArea(
                mId, mContentDescription, mOutLeft, mOutTop, mOutRight, mOutBottom, mMetadata);
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
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

    @Override
    public Integer getContentDescriptionId() {
        return mContentDescription;
    }

    /**
     * @param buffer
     * @param id
     * @param contentDescription
     * @param left
     * @param top
     * @param right
     * @param bottom
     * @param metadata
     */
    public static void apply(
            @NonNull WireBuffer buffer,
            int id,
            int contentDescription,
            float left,
            float top,
            float right,
            float bottom,
            int metadata) {
        buffer.start(OP_CODE);
        buffer.writeInt(id);
        buffer.writeInt(contentDescription);
        buffer.writeFloat(left);
        buffer.writeFloat(top);
        buffer.writeFloat(right);
        buffer.writeFloat(bottom);
        buffer.writeInt(metadata);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int id = buffer.readInt();
        int contentDescription = buffer.readInt();
        float left = buffer.readFloat();
        float top = buffer.readFloat();
        float right = buffer.readFloat();
        float bottom = buffer.readFloat();
        int metadata = buffer.readInt();
        ClickArea clickArea =
                new ClickArea(id, contentDescription, left, top, right, bottom, metadata);
        operations.add(clickArea);
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Canvas Operations", OP_CODE, CLASS_NAME)
                .description("Define a region you can click on")
                .field(DocumentedOperation.FLOAT, "left", "The left side of the region")
                .field(DocumentedOperation.FLOAT, "top", "The top of the region")
                .field(DocumentedOperation.FLOAT, "right", "The right side of the region")
                .field(DocumentedOperation.FLOAT, "bottom", "The bottom of the region")
                .field(
                        DocumentedOperation.FLOAT,
                        "metadata",
                        "user defined string accessible in callback");
    }
}
