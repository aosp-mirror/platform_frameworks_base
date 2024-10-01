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

import static com.android.internal.widget.remotecompose.core.documentation.Operation.FLOAT;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteComposeOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;

import java.util.List;

/**
 * Add a click area to the document
 */
public class ClickArea implements RemoteComposeOperation {
    private static final int OP_CODE = Operations.CLICK_AREA;
    private static final String CLASS_NAME = "ClickArea";
    int mId;
    int mContentDescription;
    float mLeft;
    float mTop;
    float mRight;
    float mBottom;
    int mMetadata;

    /**
     * Add a click area to the document
     *
     * @param id the id of the click area, which will be reported in the listener
     *                           callback on the player
     * @param contentDescription the content description (used for accessibility, as a textID)
     * @param left left coordinate of the area bounds
     * @param top top coordinate of the area bounds
     * @param right right coordinate of the area bounds
     * @param bottom bottom coordinate of the area bounds
     * @param metadata associated metadata, user-provided (as a textID, pointing to a string)
     */
    public ClickArea(int id, int contentDescription,
                     float left, float top,
                     float right, float bottom,
                     int metadata) {
        this.mId = id;
        this.mContentDescription = contentDescription;
        this.mLeft = left;
        this.mTop = top;
        this.mRight = right;
        this.mBottom = bottom;
        this.mMetadata = metadata;
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer, mId, mContentDescription, mLeft, mTop, mRight, mBottom, mMetadata);
    }

    @Override
    public String toString() {
        return "CLICK_AREA <" + mId + " <" + mContentDescription + "> "
                + "<" + mMetadata + ">+" + mLeft + " "
                + mTop + " " + mRight + " " + mBottom + "+"
                + " (" + (mRight - mLeft) + " x " + (mBottom - mTop) + " }";
    }

    @Override
    public void apply(RemoteContext context) {
        if (context.getMode() != RemoteContext.ContextMode.DATA) {
            return;
        }
        context.addClickArea(mId, mContentDescription, mLeft, mTop, mRight, mBottom, mMetadata);
    }

    @Override
    public String deepToString(String indent) {
        return indent + toString();
    }

    public static String name() {
        return CLASS_NAME;
    }


    public static int id() {
        return OP_CODE;
    }

    public static void apply(WireBuffer buffer, int id, int contentDescription,
                             float left, float top, float right, float bottom,
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


    public static void read(WireBuffer buffer, List<Operation> operations) {
        int id = buffer.readInt();
        int contentDescription = buffer.readInt();
        float left = buffer.readFloat();
        float top = buffer.readFloat();
        float right = buffer.readFloat();
        float bottom = buffer.readFloat();
        int metadata = buffer.readInt();
        ClickArea clickArea = new ClickArea(id, contentDescription,
                left, top, right, bottom, metadata);
        operations.add(clickArea);
    }


    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Canvas Operations",
                        OP_CODE,
                        CLASS_NAME)
                .description("Define a region you can click on")
                .field(FLOAT, "left",
                        "The left side of the region")
                .field(FLOAT, "top",
                        "The top of the region")
                .field(FLOAT, "right",
                        "The right side of the region")
                .field(FLOAT, "bottom",
                        "The bottom of the region")
                .field(FLOAT, "metadata",
                        "user defined string accessible in callback");
    }
}
