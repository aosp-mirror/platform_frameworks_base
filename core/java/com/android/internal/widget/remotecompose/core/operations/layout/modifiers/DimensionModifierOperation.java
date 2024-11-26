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
import com.android.internal.widget.remotecompose.core.operations.Utils;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

/** Base class for dimension modifiers */
public abstract class DimensionModifierOperation extends Operation
        implements ModifierOperation, VariableSupport {

    public enum Type {
        EXACT,
        FILL,
        WRAP,
        WEIGHT,
        INTRINSIC_MIN,
        INTRINSIC_MAX,
        EXACT_DP;

        @NonNull
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
                case 6:
                    return EXACT_DP;
            }
            return EXACT;
        }
    }

    @NonNull Type mType = Type.EXACT;
    float mValue = Float.NaN;
    float mOutValue = Float.NaN;

    public DimensionModifierOperation(@NonNull Type type, float value) {
        mType = type;
        mOutValue = mValue = value;
    }

    public DimensionModifierOperation(@NonNull Type type) {
        this(type, Float.NaN);
    }

    public DimensionModifierOperation(float value) {
        this(Type.EXACT, value);
    }

    @Override
    public void updateVariables(@NonNull RemoteContext context) {
        if (mType == Type.EXACT) {
            mOutValue = Float.isNaN(mValue) ? context.getFloat(Utils.idFromNan(mValue)) : mValue;
        }
        if (mType == Type.EXACT_DP) {
            float pre = mOutValue;
            mOutValue = Float.isNaN(mValue) ? context.getFloat(Utils.idFromNan(mValue)) : mValue;
            mOutValue *= context.getDensity();
            if (pre != mOutValue) {
                context.getDocument().getRootLayoutComponent().invalidateMeasure();
            }
        }
    }

    @Override
    public void registerListening(@NonNull RemoteContext context) {
        if (mType == Type.EXACT) {
            if (Float.isNaN(mValue)) {
                context.listensTo(Utils.idFromNan(mValue), this);
            }
        }
        if (mType == Type.EXACT_DP) {
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

    public boolean isIntrinsicMin() {
        return mType == Type.INTRINSIC_MIN;
    }

    public boolean isIntrinsicMax() {
        return mType == Type.INTRINSIC_MAX;
    }

    public @NonNull Type getType() {
        return mType;
    }

    public float getValue() {
        return mOutValue;
    }

    public void setValue(float value) {
        mOutValue = mValue = value;
    }

    @NonNull
    public String serializedName() {
        return "DIMENSION";
    }

    @Override
    public void serializeToString(int indent, @NonNull StringSerializer serializer) {
        if (mType == Type.EXACT) {
            serializer.append(indent, serializedName() + " = " + mValue);
        }
        if (mType == Type.EXACT_DP) {
            serializer.append(indent, serializedName() + " = " + mValue + " dp");
        }
    }

    @Override
    public void apply(@NonNull RemoteContext context) {}

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return (indent != null ? indent : "") + toString();
    }

    @NonNull
    @Override
    public String toString() {
        return "DimensionModifierOperation(" + mValue + ")";
    }
}
