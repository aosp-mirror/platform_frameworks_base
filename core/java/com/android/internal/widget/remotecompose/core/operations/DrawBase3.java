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

/** Base class for commands that take 3 float */
public abstract class DrawBase3 extends PaintOperation implements VariableSupport {

    @NonNull protected String mName = "DrawRectBase";
    float mV1;
    float mV2;
    float mV3;
    float mValue1;
    float mValue2;
    float mValue3;

    public DrawBase3(float v1, float v2, float v3) {
        mValue1 = v1;
        mValue2 = v2;
        mValue3 = v3;

        mV1 = v1;
        mV2 = v2;
        mV3 = v3;
    }

    @Override
    public void updateVariables(@NonNull RemoteContext context) {
        mV1 = Utils.isVariable(mValue1) ? context.getFloat(Utils.idFromNan(mValue1)) : mValue1;
        mV2 = Utils.isVariable(mValue2) ? context.getFloat(Utils.idFromNan(mValue2)) : mValue2;
        mV3 = Utils.isVariable(mValue3) ? context.getFloat(Utils.idFromNan(mValue3)) : mValue3;
    }

    @Override
    public void registerListening(@NonNull RemoteContext context) {
        if (Utils.isVariable(mValue1)) {
            context.listensTo(Utils.idFromNan(mValue1), this);
        }
        if (Utils.isVariable(mValue2)) {
            context.listensTo(Utils.idFromNan(mValue2), this);
        }
        if (Utils.isVariable(mValue3)) {
            context.listensTo(Utils.idFromNan(mValue3), this);
        }
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        write(buffer, mV1, mV2, mV3);
    }

    protected abstract void write(@NonNull WireBuffer buffer, float v1, float v2, float v3);

    interface Maker {
        DrawBase3 create(float v1, float v2, float v3);
    }

    @NonNull
    @Override
    public String toString() {
        return mName
                + " "
                + floatToString(mV1)
                + " "
                + floatToString(mV2)
                + " "
                + floatToString(mV3);
    }

    public static void read(
            @NonNull Maker maker, @NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        float v1 = buffer.readFloat();
        float v2 = buffer.readFloat();
        float v3 = buffer.readFloat();
        Operation op = maker.create(v1, v2, v3);
        operations.add(op);
    }

    /**
     * Construct and Operation from the 3 variables. This must be overridden by subclasses
     *
     * @param x1
     * @param y1
     * @param x2
     * @return
     */
    @Nullable
    public Operation construct(float x1, float y1, float x2) {
        return null;
    }
}
