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
import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;

import java.util.List;

/** Set the width dimension on a component */
public class WidthModifierOperation extends DimensionModifierOperation {
    private static final int OP_CODE = Operations.MODIFIER_WIDTH;
    public static final String CLASS_NAME = "WidthModifierOperation";

    public static String name() {
        return CLASS_NAME;
    }

    public static int id() {
        return OP_CODE;
    }

    public static void apply(WireBuffer buffer, int type, float value) {
        buffer.start(OP_CODE);
        buffer.writeInt(type);
        buffer.writeFloat(value);
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        Type type = Type.fromInt(buffer.readInt());
        float value = buffer.readFloat();
        Operation op = new WidthModifierOperation(type, value);
        operations.add(op);
    }

    public WidthModifierOperation(Type type, float value) {
        super(type, value);
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer, mType.ordinal(), mValue);
    }

    public WidthModifierOperation(Type type) {
        super(type);
    }

    public WidthModifierOperation(float value) {
        super(value);
    }

    @Override
    public String toString() {
        return "Width(" + mValue + ")";
    }

    @Override
    public String serializedName() {
        return "WIDTH";
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Modifier Operations", OP_CODE, CLASS_NAME)
                .description("define the animation")
                .field(INT, "type", "")
                .field(FLOAT, "value", "");
    }
}
