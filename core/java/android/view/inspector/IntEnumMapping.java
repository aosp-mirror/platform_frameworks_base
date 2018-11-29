/*
 * Copyright 2018 The Android Open Source Project
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

package android.view.inspector;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.ArrayList;

/**
 * Maps the values of an {int} property to string names for properties that encode enumerations.
 *
 * An {@link InspectionCompanion} may provide an instance of this class to a {@link PropertyMapper}
 * for enumerations packed into primitive {int} properties.
 *
 * This class is immutable, and must be constructed by a {@link Builder}.
 *
 * @see PropertyMapper#mapIntEnum(String, int, IntEnumMapping)
 */
public final class IntEnumMapping {
    private final Value[] mValues;

    /**
     * Map from a property value to a string name.
     *
     * @param value The value of a property
     * @return The name of the enumeration value, null if the value is not mapped
     */
    @Nullable
    public String nameOf(int value) {
        for (Value valueTuple : mValues) {
            if (valueTuple.mValue == value) {
                return valueTuple.mName;
            }
        }

        return null;
    }

    /**
     * Create a new instance from a builder.
     *
     * This constructor is private, use {@link Builder#build()} instead.
     *
     * @param builder A builder to create from
     */
    private IntEnumMapping(Builder builder) {
        mValues = builder.mValues.toArray(new Value[builder.mValues.size()]);
    }

    /**
     * A builder for {@link IntEnumMapping}
     */
    public static final class Builder {
        private final ArrayList<Value> mValues;

        public Builder() {
            mValues = new ArrayList<>();
        }

        /**
         * Add a new entry to this mapping.
         *
         * @param name Name of the enumeration value
         * @param value Int value of the enumeration value
         * @return This builder
         */
        @NonNull
        public Builder addValue(@NonNull String name, int value) {
            mValues.add(new Value(name, value));
            return this;
        }

        /**
         * Clear the builder, allowing for recycling.
         */
        public void clear() {
            mValues.clear();
        }

        /**
         * Build a new {@link IntEnumMapping} from this builder
         *
         * @return A new mapping
         */
        @NonNull
        public IntEnumMapping build() {
            return new IntEnumMapping(this);
        }
    }

    /**
     * Inner class that holds the name and value of an enumeration value.
     */
    private static final class Value {
        @NonNull private final String mName;
        private final int mValue;

        private Value(@NonNull String name, int value) {
            mName = name;
            mValue = value;
        }
    }
}
