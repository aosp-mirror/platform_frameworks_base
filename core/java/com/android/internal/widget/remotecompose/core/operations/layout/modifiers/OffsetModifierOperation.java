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

/** Represents an offset modifier. */
public class OffsetModifierOperation extends DecoratorModifierOperation {
    private static final int OP_CODE = Operations.MODIFIER_OFFSET;
    public static final String CLASS_NAME = "OffsetModifierOperation";

    float mX;
    float mY;

    public OffsetModifierOperation(float x, float y) {
        this.mX = x;
        this.mY = y;
    }

    public float getX() {
        return mX;
    }

    public float getY() {
        return mY;
    }

    public void setX(float x) {
        this.mX = x;
    }

    public void setY(float y) {
        this.mY = y;
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer, mX, mY);
    }

    // @Override
    public void serializeToString(int indent, StringSerializer serializer) {
        serializer.append(indent, "OFFSET = [" + mX + ", " + mY + "]");
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return (indent != null ? indent : "") + toString();
    }

    @Override
    public void paint(PaintContext context) {
        float x = context.getContext().mRemoteComposeState.getFloat(Utils.idFromNan(mX));
        float y = context.getContext().mRemoteComposeState.getFloat(Utils.idFromNan(mY));
        float density = context.getContext().getDensity();
        x *= density;
        y *= density;
        context.translate(x, y);
    }

    @Override
    public String toString() {
        return "OffsetModifierOperation(" + mX + ", " + mY + ")";
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

    public static void apply(WireBuffer buffer, float x, float y) {
        buffer.start(OP_CODE);
        buffer.writeFloat(x);
        buffer.writeFloat(y);
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        float x = buffer.readFloat();
        float y = buffer.readFloat();
        operations.add(new OffsetModifierOperation(x, y));
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Modifier Operations", OP_CODE, CLASS_NAME)
                .description("define the Offset Modifier")
                .field(FLOAT, "x", "")
                .field(FLOAT, "y", "");
    }

    @Override
    public void layout(RemoteContext context, Component component, float width, float height) {}
}
