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

import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.operations.Utils;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

/**
 * Base class for dimension modifiers
 */
public abstract class DimensionModifierOperation implements ModifierOperation, VariableSupport {

    public enum Type {
        EXACT, FILL, WRAP, WEIGHT, INTRINSIC_MIN, INTRINSIC_MAX;

        static Type fromInt(int value) {
            switch (value) {
                case 0:
                    return EXACT;
                case 1:
                    return FILL;
                case 2:
                    return WRAP;
                case 3:
                    return WEIGHT;
                case 4:
                    return INTRINSIC_MIN;
                case 5:
                    return INTRINSIC_MAX;
            }
            return EXACT;
        }
    }

    Type mType = Type.EXACT;
    float mValue = Float.NaN;
    float mOutValue = Float.NaN;

    public DimensionModifierOperation(Type type, float value) {
        mType = type;
        mOutValue = mValue = value;
    }

    public DimensionModifierOperation(Type type) {
        this(type, Float.NaN);
    }

    public DimensionModifierOperation(float value) {
        this(Type.EXACT, value);
    }

    @Override
    public void updateVariables(RemoteContext context) {
        if (mType == Type.EXACT) {
            mOutValue = (Float.isNaN(mValue))
                    ? context.getFloat(Utils.idFromNan(mValue)) : mValue;
        }

    }

    @Override
    public void registerListening(RemoteContext context) {
        if (mType == Type.EXACT) {
            if (Float.isNaN(mValue)) {
                context.listensTo(Utils.idFromNan(mValue), this);
            }
        }

    }


    public boolean hasWeight() {
        return mType == Type.WEIGHT;
    }

    public boolean isWrap() {
        return mType == Type.WRAP;
    }

    public boolean isFill() {
        return mType == Type.FILL;
    }

    public Type getType() {
        return mType;
    }

    public float getValue() {
        return mOutValue;
    }

    public void setValue(float value) {
        mOutValue = mValue = value;
    }


    public String serializedName() {
        return "DIMENSION";
    }

    @Override
    public void serializeToString(int indent, StringSerializer serializer) {
        if (mType == Type.EXACT) {
            serializer.append(indent, serializedName() + " = " + mValue);
        }
    }

    @Override
    public void apply(RemoteContext context) {
    }

    @Override
    public String deepToString(String indent) {
        return (indent != null ? indent : "") + toString();
    }

    @Override
    public String toString() {
        return "DimensionModifierOperation(" + mValue + ")";
    }
}
