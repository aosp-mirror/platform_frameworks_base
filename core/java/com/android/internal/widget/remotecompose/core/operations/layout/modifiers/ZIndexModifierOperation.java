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
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.Utils;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.List;

/** Represents a ZIndex modifier, allowing to change the z-index of a component. */
public class ZIndexModifierOperation extends DecoratorModifierOperation {
    private static final int OP_CODE = Operations.MODIFIER_ZINDEX;
    public static final String CLASS_NAME = "ZIndexModifierOperation";
    float mValue;
    float mCurrentValue;

    public ZIndexModifierOperation(float value) {
        this.mValue = value;
    }

    public float getValue() {
        return mCurrentValue;
    }

    public void setValue(float value) {
        this.mValue = value;
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer, mValue);
    }

    // @Override
    public void serializeToString(int indent, StringSerializer serializer) {
        serializer.append(indent, "ZINDEX = [" + mValue + "]");
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return (indent != null ? indent : "") + toString();
    }

    @Override
    public void paint(PaintContext context) {
        mCurrentValue = mValue;
        if (Utils.isVariable(mValue)) {
            mCurrentValue =
                    context.getContext().mRemoteComposeState.getFloat(Utils.idFromNan(mValue));
        }
    }

    @Override
    public String toString() {
        return "ZIndexModifierOperation(" + mValue + ")";
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

    public static void apply(WireBuffer buffer, float value) {
        buffer.start(OP_CODE);
        buffer.writeFloat(value);
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        float value = buffer.readFloat();
        operations.add(new ZIndexModifierOperation(value));
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Modifier Operations", OP_CODE, CLASS_NAME)
                .description("define the Z-Index Modifier")
                .field(FLOAT, "value", "");
    }

    @Override
    public void layout(RemoteContext context, Component component, float width, float height) {}
}
