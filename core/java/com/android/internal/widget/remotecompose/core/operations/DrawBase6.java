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

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.serialize.MapSerializer;
import com.android.internal.widget.remotecompose.core.serialize.Serializable;

import java.util.List;

/** Base class for draw commands the take 6 floats */
public abstract class DrawBase6 extends PaintOperation implements VariableSupport, Serializable {
    @NonNull protected String mName = "DrawRectBase";
    float mV1;
    float mV2;
    float mV3;
    float mV4;
    float mV5;
    float mV6;
    float mValue1;
    float mValue2;
    float mValue3;
    float mValue4;
    float mValue5;
    float mValue6;

    public DrawBase6(float v1, float v2, float v3, float v4, float v5, float v6) {
        mValue1 = v1;
        mValue2 = v2;
        mValue3 = v3;
        mValue4 = v4;
        mValue5 = v5;
        mValue6 = v6;

        mV1 = v1;
        mV2 = v2;
        mV3 = v3;
        mV4 = v4;
        mV5 = v5;
        mV6 = v6;
    }

    @Override
    public void updateVariables(@NonNull RemoteContext context) {
        mV1 = Float.isNaN(mValue1) ? context.getFloat(Utils.idFromNan(mValue1)) : mValue1;
        mV2 = Float.isNaN(mValue2) ? context.getFloat(Utils.idFromNan(mValue2)) : mValue2;
        mV3 = Float.isNaN(mValue3) ? context.getFloat(Utils.idFromNan(mValue3)) : mValue3;
        mV4 = Float.isNaN(mValue4) ? context.getFloat(Utils.idFromNan(mValue4)) : mValue4;
        mV5 = Float.isNaN(mValue5) ? context.getFloat(Utils.idFromNan(mValue5)) : mValue5;
        mV6 = Float.isNaN(mValue6) ? context.getFloat(Utils.idFromNan(mValue6)) : mValue6;
    }

    @Override
    public void registerListening(@NonNull RemoteContext context) {
        if (Float.isNaN(mValue1)) {
            context.listensTo(Utils.idFromNan(mValue1), this);
        }
        if (Float.isNaN(mValue2)) {
            context.listensTo(Utils.idFromNan(mValue2), this);
        }
        if (Float.isNaN(mValue3)) {
            context.listensTo(Utils.idFromNan(mValue3), this);
        }
        if (Float.isNaN(mValue4)) {
            context.listensTo(Utils.idFromNan(mValue4), this);
        }
        if (Float.isNaN(mValue5)) {
            context.listensTo(Utils.idFromNan(mValue5), this);
        }
        if (Float.isNaN(mValue6)) {
            context.listensTo(Utils.idFromNan(mValue6), this);
        }
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        write(buffer, mV1, mV2, mV3, mV4, mV5, mV6);
    }

    protected abstract void write(
            @NonNull WireBuffer buffer, float v1, float v2, float v3, float v4, float v5, float v6);

    @NonNull
    @Override
    public String toString() {
        return mName
                + " "
                + Utils.floatToString(mV1)
                + " "
                + Utils.floatToString(mV2)
                + " "
                + Utils.floatToString(mV3)
                + " "
                + Utils.floatToString(mV4);
    }

    interface Maker {
        DrawBase6 create(float v1, float v2, float v3, float v4, float v5, float v6);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param build interface to construct the component
     * @param buffer the buffer to read from
     * @param operations the list of operations to add to
     */
    public static void read(
            @NonNull Maker build, @NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        float sv1 = buffer.readFloat();
        float sv2 = buffer.readFloat();
        float sv3 = buffer.readFloat();
        float sv4 = buffer.readFloat();
        float sv5 = buffer.readFloat();
        float sv6 = buffer.readFloat();

        Operation op = build.create(sv1, sv2, sv3, sv4, sv5, sv6);
        operations.add(op);
    }

    /**
     * writes out a the operation to the buffer.
     *
     * @param v1 the first parameter
     * @param v2 the second parameter
     * @param v3 the third parameter
     * @param v4 the fourth parameter
     * @param v5 the fifth parameter
     * @param v6 the sixth parameter
     * @return the operation
     */
    @Nullable
    public Operation construct(float v1, float v2, float v3, float v4, float v5, float v6) {
        return null;
    }

    /**
     * The name of the class
     *
     * @return the name
     */
    @NonNull
    public static String name() {
        return "DrawBase6";
    }

    protected MapSerializer serialize(
            MapSerializer serializer,
            String v1Name,
            String v2Name,
            String v3Name,
            String v4Name,
            String v5Name,
            String v6Name) {
        return serializer
                .add(v1Name, mV1, mValue1)
                .add(v2Name, mV2, mValue2)
                .add(v3Name, mV3, mValue3)
                .add(v4Name, mV4, mValue4)
                .add(v5Name, mV5, mValue5)
                .add(v6Name, mV6, mValue6);
    }
}
