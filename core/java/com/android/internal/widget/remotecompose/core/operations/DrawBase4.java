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

import static com.android.internal.widget.remotecompose.core.operations.Utils.floatToString;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;

import java.util.List;

/** Base class for draw commands that take 4 floats */
public abstract class DrawBase4 extends PaintOperation implements VariableSupport {
    @NonNull protected String mName = "DrawRectBase";
    protected float mX1;
    protected float mY1;
    protected float mX2;
    protected float mY2;
    float mX1Value;
    float mY1Value;
    float mX2Value;
    float mY2Value;

    public DrawBase4(float x1, float y1, float x2, float y2) {
        mX1Value = x1;
        mY1Value = y1;
        mX2Value = x2;
        mY2Value = y2;

        mX1 = x1;
        mY1 = y1;
        mX2 = x2;
        mY2 = y2;
    }

    @Override
    public void updateVariables(@NonNull RemoteContext context) {
        mX1 = Float.isNaN(mX1Value) ? context.getFloat(Utils.idFromNan(mX1Value)) : mX1Value;
        mY1 = Float.isNaN(mY1Value) ? context.getFloat(Utils.idFromNan(mY1Value)) : mY1Value;
        mX2 = Float.isNaN(mX2Value) ? context.getFloat(Utils.idFromNan(mX2Value)) : mX2Value;
        mY2 = Float.isNaN(mY2Value) ? context.getFloat(Utils.idFromNan(mY2Value)) : mY2Value;
    }

    @Override
    public void registerListening(@NonNull RemoteContext context) {
        if (Float.isNaN(mX1Value)) {
            context.listensTo(Utils.idFromNan(mX1Value), this);
        }
        if (Float.isNaN(mY1Value)) {
            context.listensTo(Utils.idFromNan(mY1Value), this);
        }
        if (Float.isNaN(mX2Value)) {
            context.listensTo(Utils.idFromNan(mX2Value), this);
        }
        if (Float.isNaN(mY2Value)) {
            context.listensTo(Utils.idFromNan(mY2Value), this);
        }
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        write(buffer, mX1, mY1, mX2, mY2);
    }

    protected abstract void write(
            @NonNull WireBuffer buffer, float v1, float v2, float v3, float v4);

    protected interface Maker {
        DrawBase4 create(float v1, float v2, float v3, float v4);
    }

    @NonNull
    @Override
    public String toString() {
        return mName
                + " "
                + floatToString(mX1Value, mX1)
                + " "
                + floatToString(mY1Value, mY1)
                + " "
                + floatToString(mX2Value, mX2)
                + " "
                + floatToString(mY2Value, mY2);
    }

    public static void read(
            @NonNull Maker maker, @NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        float v1 = buffer.readFloat();
        float v2 = buffer.readFloat();
        float v3 = buffer.readFloat();
        float v4 = buffer.readFloat();

        Operation op = maker.create(v1, v2, v3, v4);
        operations.add(op);
    }

    /**
     * Construct and Operation from the 3 variables.
     *
     * @param x1
     * @param y1
     * @param x2
     * @param y2
     * @return
     */
    @Nullable
    public Operation construct(float x1, float y1, float x2, float y2) {
        return null;
    }

    /**
     * Writes out the operation to the buffer
     *
     * @param buffer
     * @param opCode
     * @param x1
     * @param y1
     * @param x2
     * @param y2
     */
    protected static void write(
            @NonNull WireBuffer buffer, int opCode, float x1, float y1, float x2, float y2) {
        buffer.start(opCode);
        buffer.writeFloat(x1);
        buffer.writeFloat(y1);
        buffer.writeFloat(x2);
        buffer.writeFloat(y2);
    }
}
