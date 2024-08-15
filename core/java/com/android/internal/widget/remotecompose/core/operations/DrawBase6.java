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

import com.android.internal.widget.remotecompose.core.CompanionOperation;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;

import java.util.List;

/**
 * Base class for draw commands the take 6 floats
 */
public abstract class DrawBase6 extends PaintOperation
        implements VariableSupport {
    public static final Companion COMPANION =
            new Companion(Operations.DRAW_RECT) {
                public Operation construct(float x1, float y1, float x2, float y2) {
                    //   return new DrawRectBase(x1, y1, x2, y2);
                    return null;
                }
            };
    protected String mName = "DrawRectBase";
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

    public DrawBase6(
            float v1,
            float v2,
            float v3,
            float v4,
            float v5,
            float v6) {
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
    public void updateVariables(RemoteContext context) {
        mV1 = (Float.isNaN(mValue1))
                ? context.getFloat(Utils.idFromNan(mValue1)) : mValue1;
        mV2 = (Float.isNaN(mValue2))
                ? context.getFloat(Utils.idFromNan(mValue2)) : mValue2;
        mV3 = (Float.isNaN(mValue3))
                ? context.getFloat(Utils.idFromNan(mValue3)) : mValue3;
        mV4 = (Float.isNaN(mValue4))
                ? context.getFloat(Utils.idFromNan(mValue4)) : mValue4;
        mV5 = (Float.isNaN(mValue5))
                ? context.getFloat(Utils.idFromNan(mValue5)) : mValue5;
        mV6 = (Float.isNaN(mValue6))
                ? context.getFloat(Utils.idFromNan(mValue6)) : mValue6;
    }

    @Override
    public void registerListening(RemoteContext context) {
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
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mV1, mV2, mV3, mV4, mV5, mV6);
    }

    @Override
    public String toString() {
        return mName + " " + floatToString(mV1) + " " + floatToString(mV2)
                + " " + floatToString(mV3) + " " + floatToString(mV4);
    }

    public static class Companion implements CompanionOperation {
        public final int OP_CODE;

        protected Companion(int code) {
            OP_CODE = code;
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            float sv1 = buffer.readFloat();
            float sv2 = buffer.readFloat();
            float sv3 = buffer.readFloat();
            float sv4 = buffer.readFloat();
            float sv5 = buffer.readFloat();
            float sv6 = buffer.readFloat();

            Operation op = construct(sv1, sv2, sv3, sv4, sv5, sv6);
            operations.add(op);
        }

        /**
         * writes out a the operation to the buffer.
         * @param v1
         * @param v2
         * @param v3
         * @param v4
         * @param v5
         * @param v6
         * @return
         */
        public Operation construct(float v1,
                                   float v2,
                                   float v3,
                                   float v4,
                                   float v5,
                                   float v6) {
            return null;
        }

        @Override
        public String name() {
            return "DrawRect";
        }

        @Override
        public int id() {
            return OP_CODE;
        }

        /**
         * Writes out the operation to the buffer
         * @param buffer
         * @param v1
         * @param v2
         * @param v3
         * @param v4
         * @param v5
         * @param v6
         */
        public void apply(WireBuffer buffer,
                          float v1,
                          float v2,
                          float v3,
                          float v4,
                          float v5,
                          float v6) {
            buffer.start(OP_CODE);
            buffer.writeFloat(v1);
            buffer.writeFloat(v2);
            buffer.writeFloat(v3);
            buffer.writeFloat(v4);
            buffer.writeFloat(v5);
            buffer.writeFloat(v6);
        }
    }
}
