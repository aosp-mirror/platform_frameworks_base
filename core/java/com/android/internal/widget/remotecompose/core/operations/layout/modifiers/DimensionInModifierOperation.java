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
package com.android.internal.widget.remotecompose.core.operations.layout.modifiers;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.operations.Utils;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

/** Helper class to set the min / max dimension on a component */
public class DimensionInModifierOperation extends Operation
        implements ModifierOperation, VariableSupport {
    int mOpCode = -1;

    float mV1;
    float mV2;
    float mValue1;
    float mValue2;

    public DimensionInModifierOperation(int opcode, float min, float max) {
        mOpCode = opcode;
        mValue1 = min;
        mValue2 = max;
        if (!Float.isNaN(mValue1)) {
            mV1 = mValue1;
        }
        if (!Float.isNaN(mValue2)) {
            mV2 = mValue2;
        }
    }

    @Override
    public void updateVariables(@NonNull RemoteContext context) {
        mV1 = Float.isNaN(mValue1) ? context.getFloat(Utils.idFromNan(mValue1)) : mValue1;
        mV2 = Float.isNaN(mValue2) ? context.getFloat(Utils.idFromNan(mValue2)) : mValue2;
        if (mV1 != -1) {
            mV1 = mV1 * context.getDensity();
        }
        if (mV2 != -1) {
            mV2 = mV2 * context.getDensity();
        }
    }

    @Override
    public void registerListening(@NonNull RemoteContext context) {
        if (Float.isNaN(mValue1)) {
            context.listensTo(Utils.idFromNan(mValue1), this);
        }
        if (Float.isNaN(mValue2)) {
            context.listensTo(Utils.idFromNan(mValue2), this);
        }
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        // nothing
    }

    @Override
    public void apply(@NonNull RemoteContext context) {
        // nothing
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }

    /**
     * Returns the min value
     *
     * @return minimum value
     */
    public float getMin() {
        return mV1;
    }

    /**
     * Returns the max value
     *
     * @return maximum value
     */
    public float getMax() {
        return mV2;
    }

    @Override
    public void serializeToString(int indent, @NonNull StringSerializer serializer) {
        serializer.append(indent, "WIDTH_IN = [" + getMin() + ", " + getMax() + "]");
    }
}
