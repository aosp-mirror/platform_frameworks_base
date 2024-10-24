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

public class DrawTweenPath extends PaintOperation implements VariableSupport {
    private static final int OP_CODE = Operations.DRAW_TWEEN_PATH;
    private static final String CLASS_NAME = "DrawTweenPath";
    float mTween;
    float mStart;
    float mStop;
    float mOutTween;
    float mOutStart;
    float mOutStop;
    int mPath1Id;
    int mPath2Id;

    public DrawTweenPath(
            int path1Id,
            int path2Id,
            float tween,
            float start,
            float stop) {
        mOutTween = mTween = tween;
        mOutStart = mStart = start;
        mOutStop = mStop = stop;
        mPath1Id = path1Id;
        mPath2Id = path2Id;
    }

    @Override
    public void updateVariables(RemoteContext context) {
        mOutTween = (Float.isNaN(mTween))
                ? context.getFloat(Utils.idFromNan(mTween)) : mTween;
        mOutStart = (Float.isNaN(mStart))
                ? context.getFloat(Utils.idFromNan(mStart)) : mStart;
        mOutStop = (Float.isNaN(mStop))
                ? context.getFloat(Utils.idFromNan(mStop)) : mStop;
    }

    @Override
    public void registerListening(RemoteContext context) {
        if (Float.isNaN(mTween)) {
            context.listensTo(Utils.idFromNan(mTween), this);
        }
        if (Float.isNaN(mStart)) {
            context.listensTo(Utils.idFromNan(mStart), this);
        }
        if (Float.isNaN(mStop)) {
            context.listensTo(Utils.idFromNan(mStop), this);
        }
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer, mPath1Id,
                mPath2Id,
                mTween,
                mStart,
                mStop);
    }

    @Override
    public String toString() {
        return "DrawTweenPath " + mPath1Id + " " + mPath2Id
                + " " + floatToString(mTween, mOutTween)  + " "
                + floatToString(mStart, mOutStart) + " "
                + "- " + floatToString(mStop, mOutStop);
    }


    public static void read(WireBuffer buffer, List<Operation> operations) {
        int path1Id = buffer.readInt();
        int path2Id = buffer.readInt();
        float tween = buffer.readFloat();
        float start = buffer.readFloat();
        float stop = buffer.readFloat();
        DrawTweenPath op = new DrawTweenPath(path1Id, path2Id,
                tween, start, stop);
        operations.add(op);
    }


    public static String name() {
        return "DrawTweenPath";
    }


    public static int id() {
        return Operations.DRAW_TWEEN_PATH;
    }

    public static void apply(WireBuffer buffer,
                      int path1Id,
                      int path2Id,
                      float tween,
                      float start,
                      float stop) {
        buffer.start(OP_CODE);
        buffer.writeInt(path1Id);
        buffer.writeInt(path2Id);
        buffer.writeFloat(tween);
        buffer.writeFloat(start);
        buffer.writeFloat(stop);
    }


    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Draw Operations",
                        OP_CODE,
                        CLASS_NAME)
                .description("Draw text along path object")
                .field(INT, "pathId1",
                        "id of path 1")
                .field(INT, "pathId2",
                        "id of path 2")
                .field(FLOAT, "tween",
                        "interpolate between the two paths")
                .field(FLOAT, "start",
                        "trim the start of the path")
                .field(FLOAT, "yOffset",
                        "trim the end of the path");

    }


    @Override
    public void paint(PaintContext context) {
        context.drawTweenPath(mPath1Id,
                mPath2Id,
                mOutTween,
                mOutStart,
                mOutStop);
    }

}
