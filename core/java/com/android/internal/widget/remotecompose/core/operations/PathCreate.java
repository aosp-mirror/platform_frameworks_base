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

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.FLOAT;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;

import java.util.Arrays;
import java.util.List;

public class PathCreate extends PaintOperation implements VariableSupport {
    private static final int OP_CODE = Operations.PATH_CREATE;
    private static final String CLASS_NAME = "PathCreate";
    int mInstanceId;
    float[] mFloatPath;
    float[] mOutputPath;

    PathCreate(int instanceId, float startX, float startY) {
        mInstanceId = instanceId;
        mFloatPath = new float[] {PathData.MOVE_NAN, startX, startY};
        mOutputPath = Arrays.copyOf(mFloatPath, mFloatPath.length);
    }

    @Override
    public void updateVariables(@NonNull RemoteContext context) {

        for (int i = 0; i < mFloatPath.length; i++) {
            float v = mFloatPath[i];
            if (Utils.isVariable(v)) {
                mOutputPath[i] = Float.isNaN(v) ? context.getFloat(Utils.idFromNan(v)) : v;
            } else {
                mOutputPath[i] = v;
            }
        }
    }

    @Override
    public void registerListening(@NonNull RemoteContext context) {
        for (float v : mFloatPath) {
            if (Float.isNaN(v)) {
                context.listensTo(Utils.idFromNan(v), this);
            }
        }
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mInstanceId, mFloatPath[1], mFloatPath[2]);
    }

    @NonNull
    @Override
    public String deepToString(String indent) {
        return pathString(mFloatPath);
    }

    @NonNull
    @Override
    public String toString() {
        return "PathCreate["
                + mInstanceId
                + "] = "
                + "\""
                + deepToString(" ")
                + "\"["
                + Utils.idStringFromNan(mFloatPath[1])
                + "] "
                + mOutputPath[1]
                + " ["
                + Utils.idStringFromNan(mFloatPath[2])
                + "] "
                + mOutputPath[2];
    }

    public static final int MOVE = 10;
    public static final int LINE = 11;
    public static final int QUADRATIC = 12;
    public static final int CONIC = 13;
    public static final int CUBIC = 14;
    public static final int CLOSE = 15;
    public static final int DONE = 16;
    public static final float MOVE_NAN = Utils.asNan(MOVE);
    public static final float LINE_NAN = Utils.asNan(LINE);
    public static final float QUADRATIC_NAN = Utils.asNan(QUADRATIC);
    public static final float CONIC_NAN = Utils.asNan(CONIC);
    public static final float CUBIC_NAN = Utils.asNan(CUBIC);
    public static final float CLOSE_NAN = Utils.asNan(CLOSE);
    public static final float DONE_NAN = Utils.asNan(DONE);

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

    public static void apply(@NonNull WireBuffer buffer, int id, float startX, float startY) {
        buffer.start(OP_CODE);
        buffer.writeInt(id);
        buffer.writeFloat(startX);
        buffer.writeFloat(startY);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {

        int id = buffer.readInt();
        float startX = buffer.readFloat();
        float startY = buffer.readFloat();
        operations.add(new PathCreate(id, startX, startY));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Data Operations", OP_CODE, CLASS_NAME)
                .description("Encode a Path ")
                .field(DocumentedOperation.INT, "id", "id of path")
                .field(FLOAT, "startX", "initial start x")
                .field(FLOAT, "startX", "initial start y");
    }

    @NonNull
    public static String pathString(@Nullable float[] path) {
        if (path == null) {
            return "null";
        }
        StringBuilder str = new StringBuilder();
        for (int i = 0; i < path.length; i++) {
            if (i != 0) {
                str.append(" ");
            }
            if (Float.isNaN(path[i])) {
                int id = Utils.idFromNan(path[i]); // Assume idFromNan is defined elsewhere
                if (id <= DONE) { // Assume DONE is a constant
                    switch (id) {
                        case MOVE:
                            str.append("M");
                            break;
                        case LINE:
                            str.append("L");
                            break;
                        case QUADRATIC:
                            str.append("Q");
                            break;
                        case CONIC:
                            str.append("R");
                            break;
                        case CUBIC:
                            str.append("C");
                            break;
                        case CLOSE:
                            str.append("Z");
                            break;
                        case DONE:
                            str.append(".");
                            break;
                        default:
                            str.append("[" + id + "]");
                            break;
                    }
                } else {
                    str.append("(" + id + ")");
                }
            } else {
                str.append(path[i]);
            }
        }
        return str.toString();
    }

    @Override
    public void paint(PaintContext context) {
        apply(context.getContext());
    }

    @Override
    public void apply(@NonNull RemoteContext context) {
        context.loadPathData(mInstanceId, mOutputPath);
    }
}
