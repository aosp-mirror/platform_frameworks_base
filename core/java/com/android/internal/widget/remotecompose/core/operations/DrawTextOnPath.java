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
import static com.android.internal.widget.remotecompose.core.documentation.Operation.INT;
import static com.android.internal.widget.remotecompose.core.operations.Utils.floatToString;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;

import java.util.List;

/**
 * Draw text along a path.
 */
public class DrawTextOnPath extends PaintOperation implements VariableSupport  {
    private static final int OP_CODE = Operations.DRAW_TEXT_ON_PATH;
    private static final String CLASS_NAME = "DrawTextOnPath";
    int mPathId;
    public int mTextId;
    float mVOffset;
    float mHOffset;
    float mOutVOffset;
    float mOutHOffset;

    public DrawTextOnPath(int textId, int pathId, float hOffset, float vOffset) {
        mPathId = pathId;
        mTextId = textId;
        mOutHOffset = mHOffset = vOffset;
        mOutVOffset = mVOffset = hOffset;
    }


    @Override
    public void updateVariables(RemoteContext context) {
        mOutHOffset = (Float.isNaN(mHOffset))
                ? context.getFloat(Utils.idFromNan(mHOffset)) : mHOffset;
        mOutVOffset = (Float.isNaN(mVOffset))
                ? context.getFloat(Utils.idFromNan(mVOffset)) : mVOffset;

    }

    @Override
    public void registerListening(RemoteContext context) {
        if (Float.isNaN(mHOffset)) {
            context.listensTo(Utils.idFromNan(mHOffset), this);
        }
        if (Float.isNaN(mVOffset)) {
            context.listensTo(Utils.idFromNan(mVOffset), this);
        }
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer, mTextId, mPathId, mHOffset, mVOffset);
    }

    @Override
    public String toString() {
        return "DrawTextOnPath [" + mTextId + "] [" + mPathId + "] "
                + floatToString(mHOffset, mOutHOffset) + ", "
                + floatToString(mVOffset, mOutVOffset);
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        int textId = buffer.readInt();
        int pathId = buffer.readInt();
        float hOffset = buffer.readFloat();
        float vOffset = buffer.readFloat();
        DrawTextOnPath op = new DrawTextOnPath(textId, pathId, hOffset, vOffset);
        operations.add(op);
    }

    public static String name() {
        return "DrawTextOnPath";
    }


    public static int id() {
        return Operations.DRAW_TEXT_ON_PATH;
    }

    public static void apply(WireBuffer buffer, int textId, int pathId,
                             float hOffset, float vOffset) {
        buffer.start(OP_CODE);
        buffer.writeInt(textId);
        buffer.writeInt(pathId);
        buffer.writeFloat(hOffset);
        buffer.writeFloat(vOffset);
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Draw Operations",
                        OP_CODE,
                        CLASS_NAME)
                .description("Draw text along path object")
                .field(INT, "textId",
                        "id of the text")
                .field(INT, "pathId",
                        "id of the path")
                .field(FLOAT, "xOffset",
                        "x Shift of the text")
                .field(FLOAT, "yOffset",
                        "y Shift of the text");
    }

    @Override
    public void paint(PaintContext context) {
        context.drawTextOnPath(mTextId, mPathId, mOutHOffset, mOutVOffset);
    }
}
