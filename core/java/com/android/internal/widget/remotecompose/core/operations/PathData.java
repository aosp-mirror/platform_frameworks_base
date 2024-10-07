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

import static com.android.internal.widget.remotecompose.core.documentation.Operation.FLOAT_ARRAY;
import static com.android.internal.widget.remotecompose.core.documentation.Operation.INT;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;

import java.util.Arrays;
import java.util.List;

public class PathData implements Operation, VariableSupport {
    private static final int OP_CODE = Operations.DATA_PATH;
    private static final String CLASS_NAME = "PathData";
    int mInstanceId;
    float[] mFloatPath;
    float[] mOutputPath;

    PathData(int instanceId, float[] floatPath) {
        mInstanceId = instanceId;
        mFloatPath = floatPath;
        mOutputPath = Arrays.copyOf(mFloatPath, mFloatPath.length);
    }

    @Override
    public void updateVariables(RemoteContext context) {
        for (int i = 0; i < mFloatPath.length; i++) {
            float v = mFloatPath[i];
            if (Utils.isVariable(v)) {
                mOutputPath[i] = (Float.isNaN(v))
                        ? context.getFloat(Utils.idFromNan(v)) : v;
            } else {
                mOutputPath[i] = v;
            }
        }
    }

    @Override
    public void registerListening(RemoteContext context) {
        for (float v : mFloatPath) {
            if (Float.isNaN(v)) {
                context.listensTo(Utils.idFromNan(v), this);
            }
        }
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer, mInstanceId, mOutputPath);
    }

    @Override
    public String deepToString(String indent) {
        return pathString(mFloatPath);
    }

    @Override
    public String toString() {
        return "PathData[" + mInstanceId + "] = " + "\"" + deepToString(" ") + "\"";
    }

    /**
     * public float[] getFloatPath(PaintContext context) {
     * float[] ret = mRetFloats; // Assume retFloats is declared elsewhere
     * if (ret == null) {
     * return mFloatPath; // Assume floatPath is declared elsewhere
     * }
     * float[] localRef = mRef; // Assume ref is of type Float[]
     * if (localRef == null) {
     * for (int i = 0; i < mFloatPath.length; i++) {
     * ret[i] = mFloatPath[i];
     * }
     * } else {
     * for (int i = 0; i < mFloatPath.length; i++) {
     * float lr = localRef[i];
     * if (Float.isNaN(lr)) {
     * ret[i] = Utils.getActualValue(lr);
     * } else {
     * ret[i] = mFloatPath[i];
     * }
     * }
     * }
     * return ret;
     * }
     */
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

    public static String name() {
        return CLASS_NAME;
    }

    public static int id() {
        return OP_CODE;
    }

    public static void apply(WireBuffer buffer, int id, float[] data) {
        buffer.start(Operations.DATA_PATH);
        buffer.writeInt(id);
        buffer.writeInt(data.length);
        for (float datum : data) {
            buffer.writeFloat(datum);
        }
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        int imageId = buffer.readInt();
        int len = buffer.readInt();
        float[] data = new float[len];
        for (int i = 0; i < data.length; i++) {
            data[i] = buffer.readFloat();
        }
        operations.add(new PathData(imageId, data));
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Data Operations",
                        OP_CODE,
                        CLASS_NAME)
                .description("Encode a Path ")
                .field(INT, "id", "id string")
                .field(INT, "length", "id string")
                .field(FLOAT_ARRAY, "pathData", "length",
                        "path encoded as floats");
    }


    public static String pathString(float[] path) {
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
    public void apply(RemoteContext context) {
        context.loadPathData(mInstanceId, mOutputPath);
    }

}
