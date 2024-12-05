/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT;
import static com.android.internal.widget.remotecompose.core.operations.Utils.floatToString;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;

import java.util.List;

/** Operation to deal with Path data */
public class PathTween extends PaintOperation implements VariableSupport {
    private static final int OP_CODE = Operations.PATH_TWEEN;
    private static final String CLASS_NAME = "PathTween";
    public int mOutId;
    public int mPathId1;
    public int mPathId2;
    public float mTween;
    public float mTweenOut;

    public PathTween(int outId, int pathId1, int pathId2, float tween) {
        this.mOutId = outId;
        this.mPathId1 = pathId1;
        this.mPathId2 = pathId2;
        this.mTween = tween;
        this.mTweenOut = mTween;
    }

    @Override
    public void updateVariables(@NonNull RemoteContext context) {
        mTweenOut = Float.isNaN(mTween) ? context.getFloat(Utils.idFromNan(mTween)) : mTween;
    }

    @Override
    public void registerListening(@NonNull RemoteContext context) {
        if (Float.isNaN(mTween)) {
            context.listensTo(Utils.idFromNan(mTween), this);
        }
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mOutId, mPathId1, mPathId2, mTween);
    }

    @NonNull
    @Override
    public String toString() {
        return "PathTween["
                + mOutId
                + "] = ["
                + mPathId1
                + " ] + [ "
                + mPathId2
                + "], "
                + floatToString(mTween, mTweenOut);
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

    /**
     * Writes out the operation to the buffer
     *
     * @param buffer buffer to write to
     * @param outId id of the path
     * @param pathId1 source path 1
     * @param pathId2 source path 2
     * @param tween interpolate between two paths
     */
    public static void apply(
            @NonNull WireBuffer buffer, int outId, int pathId1, int pathId2, float tween) {
        buffer.start(OP_CODE);
        buffer.writeInt(outId);
        buffer.writeInt(pathId1);
        buffer.writeInt(pathId2);
        buffer.writeFloat(tween);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int outId1 = buffer.readInt();
        int pathId1 = buffer.readInt();
        int pathId2 = buffer.readInt();
        float tween = buffer.readFloat();

        operations.add(new PathTween(outId1, pathId1, pathId2, tween));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Data Operations", OP_CODE, CLASS_NAME)
                .description("Merge two string into one")
                .field(DocumentedOperation.INT, "pathId", "id of the path")
                .field(INT, "srcPathId1", "id of the path")
                .field(INT, "srcPathId1", "x Shift of the path");
    }

    @NonNull
    @Override
    public String deepToString(String indent) {
        return indent + toString();
    }

    @Override
    public void paint(PaintContext context) {
        context.tweenPath(mOutId, mPathId1, mPathId2, mTweenOut);
    }
}
