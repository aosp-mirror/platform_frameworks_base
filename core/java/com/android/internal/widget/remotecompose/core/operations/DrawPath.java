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

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;

import java.util.List;

public class DrawPath extends PaintOperation {
    private static final int OP_CODE = Operations.DRAW_PATH;
    private static final String CLASS_NAME = "DrawPath";

    int mId;
    float mStart = 0;
    float mEnd = 1;

    public DrawPath(int pathId) {
        mId = pathId;
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer, mId);
    }

    @Override
    public String toString() {
        return "DrawPath " + "[" + mId + "]" + ", " + mStart + ", " + mEnd;
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        int id = buffer.readInt();
        DrawPath op = new DrawPath(id);
        operations.add(op);
    }

    public static String name() {
        return CLASS_NAME;
    }

    public static int id() {
        return Operations.DRAW_PATH;
    }

    public static void apply(WireBuffer buffer, int id) {
        buffer.start(Operations.DRAW_PATH);
        buffer.writeInt(id);
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Draw Operations", OP_CODE, CLASS_NAME)
                .description("Draw a bitmap using integer coordinates")
                .field(DocumentedOperation.INT, "id", "id of path");
    }

    @Override
    public void paint(PaintContext context) {
        context.drawPath(mId, mStart, mEnd);
    }
}
