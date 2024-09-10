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

import com.android.internal.widget.remotecompose.core.CompanionOperation;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.List;

/**
 * Base class for dimension modifiers
 */
public class DimensionModifierOperation implements ModifierOperation {

    public static final DimensionModifierOperation.Companion COMPANION =
            new DimensionModifierOperation.Companion(0, "DIMENSION");

    public enum Type {
        EXACT, FILL, WRAP, WEIGHT, INTRINSIC_MIN, INTRINSIC_MAX;

        static Type fromInt(int value) {
            switch (value) {
                case 0: return EXACT;
                case 1: return FILL;
                case 2: return WRAP;
                case 3: return WEIGHT;
                case 4: return INTRINSIC_MIN;
                case 5: return INTRINSIC_MAX;
            }
            return EXACT;
        }
    }

    Type mType = Type.EXACT;
    float mValue = Float.NaN;

    public DimensionModifierOperation(Type type, float value) {
        mType = type;
        mValue = value;
    }

    public DimensionModifierOperation(Type type) {
        this(type, Float.NaN);
    }

    public DimensionModifierOperation(float value) {
        this(Type.EXACT, value);
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
        return mValue;
    }

    public void setValue(float value) {
        this.mValue = value;
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mType.ordinal(), mValue);
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

    public static class Companion implements CompanionOperation {

        int mOperation;
        String mName;

        public Companion(int operation, String name) {
            mOperation = operation;
            mName = name;
        }

        @Override
        public String name() {
            return mName;
        }

        @Override
        public int id() {
            return mOperation;
        }

        public void apply(WireBuffer buffer, int type, float value) {
            buffer.start(mOperation);
            buffer.writeInt(type);
            buffer.writeFloat(value);
        }

        public Operation construct(Type type, float value) {
            return null;
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            Type type = Type.fromInt(buffer.readInt());
            float value = buffer.readFloat();
            Operation op = construct(type, value);
            operations.add(op);
        }
    }
}
