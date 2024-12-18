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

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.FLOAT;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.List;

/**
 * Represents a padding modifier. Padding modifiers can be chained and will impact following
 * modifiers.
 */
public class PaddingModifierOperation extends Operation implements ModifierOperation {
    private static final int OP_CODE = Operations.MODIFIER_PADDING;
    public static final String CLASS_NAME = "PaddingModifierOperation";
    float mLeft;
    float mTop;
    float mRight;
    float mBottom;

    public PaddingModifierOperation(float left, float top, float right, float bottom) {
        this.mLeft = left;
        this.mTop = top;
        this.mRight = right;
        this.mBottom = bottom;
    }

    public float getLeft() {
        return mLeft;
    }

    public float getTop() {
        return mTop;
    }

    public float getRight() {
        return mRight;
    }

    public float getBottom() {
        return mBottom;
    }

    public void setLeft(float left) {
        this.mLeft = left;
    }

    public void setTop(float top) {
        this.mTop = top;
    }

    public void setRight(float right) {
        this.mRight = right;
    }

    public void setBottom(float bottom) {
        this.mBottom = bottom;
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mLeft, mTop, mRight, mBottom);
    }

    @Override
    public void serializeToString(int indent, @NonNull StringSerializer serializer) {
        serializer.append(
                indent, "PADDING = [" + mLeft + ", " + mTop + ", " + mRight + ", " + mBottom + "]");
    }

    @Override
    public void apply(@NonNull RemoteContext context) {}

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return (indent != null ? indent : "") + toString();
    }

    @NonNull
    @Override
    public String toString() {
        return "PaddingModifierOperation("
                + mLeft
                + ", "
                + mTop
                + ", "
                + mRight
                + ", "
                + mBottom
                + ")";
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
        return Operations.MODIFIER_PADDING;
    }

    public static void apply(
            @NonNull WireBuffer buffer, float left, float top, float right, float bottom) {
        buffer.start(Operations.MODIFIER_PADDING);
        buffer.writeFloat(left);
        buffer.writeFloat(top);
        buffer.writeFloat(right);
        buffer.writeFloat(bottom);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        float left = buffer.readFloat();
        float top = buffer.readFloat();
        float right = buffer.readFloat();
        float bottom = buffer.readFloat();
        operations.add(new PaddingModifierOperation(left, top, right, bottom));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Modifier Operations", OP_CODE, CLASS_NAME)
                .description("define the Padding Modifier")
                .field(FLOAT, "left", "")
                .field(FLOAT, "top", "")
                .field(FLOAT, "right", "")
                .field(FLOAT, "bottom", "");
    }
}
