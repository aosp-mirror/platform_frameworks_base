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

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;
import com.android.internal.widget.remotecompose.core.serialize.MapSerializer;
import com.android.internal.widget.remotecompose.core.serialize.SerializeTags;

import java.util.List;

/** Set the min / max height dimension on a component */
public class HeightInModifierOperation extends DimensionInModifierOperation {
    private static final int OP_CODE = Operations.MODIFIER_HEIGHT_IN;
    public static final String CLASS_NAME = "HeightInModifierOperation";

    public HeightInModifierOperation(float min, float max) {
        super(OP_CODE, min, max);
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, getMin(), getMax());
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        float v1 = buffer.readFloat();
        float v2 = buffer.readFloat();
        operations.add(new HeightInModifierOperation(v1, v2));
    }

    /**
     * The OP_CODE for this command
     *
     * @return the opcode
     */
    public static int id() {
        return OP_CODE;
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
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Layout Operations", OP_CODE, "HeightInModifierOperation")
                .description("Add additional constraints to the height")
                .field(DocumentedOperation.FLOAT, "min", "The minimum height, -1 if not applied")
                .field(DocumentedOperation.FLOAT, "max", "The maximum height, -1 if not applied");
    }

    /**
     * Writes out the HeightInModifier to the buffer
     *
     * @param buffer buffer to write to
     * @param x1 start x of DrawOval
     * @param y1 start y of the DrawOval
     */
    public static void apply(@NonNull WireBuffer buffer, float x1, float y1) {
        buffer.start(OP_CODE);
        buffer.writeFloat(x1);
        buffer.writeFloat(y1);
    }

    @Override
    public void serializeToString(int indent, @NonNull StringSerializer serializer) {
        serializer.append(indent, "HEIGHT_IN = [" + getMin() + ", " + getMax() + "]");
    }

    @Override
    public void serialize(MapSerializer serializer) {
        serializer
                .addTags(SerializeTags.MODIFIER)
                .add("type", "HeightInModifierOperation")
                .add("min", mV1, mValue1)
                .add("max", mV2, mValue2);
    }
}
