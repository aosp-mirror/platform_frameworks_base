/*
 * Copyright 2019 The Android Open Source Project
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
import android.util.SparseArray;

import java.util.Objects;

/**
 * Maps the values of an {@code int} property to strings for properties that encode an enumeration.
 *
 * An {@link InspectionCompanion} may provide an instance of this class to a {@link PropertyMapper}
 * for flag values packed into primitive {@code int} properties.
 *
 * This class is an immutable wrapper for {@link SparseArray}, and must be constructed by a
 * {@link Builder}.
 *
 * @see PropertyMapper#mapIntEnum(String, int, IntEnumMapping)
 */
public final class IntEnumMapping {
    private final SparseArray<String> mValues;

    /**
     * Get the name for the given property value
     *
     * @param value The value of the property
     * @return The name of the value in the enumeration, or null if no value is defined
     */
    @Nullable
    public String get(int value) {
        return mValues.get(value);
    }

    /**
     * Create a new instance from a builder.
     *
     * This constructor is private, use {@link Builder#build()} instead.
     *
     * @param builder A builder to create from
     */
    private IntEnumMapping(Builder builder) {
        mValues = builder.mValues.clone();
    }

    /**
     * A builder for {@link IntEnumMapping}.
     */
    public static final class Builder {
        @NonNull
        private SparseArray<String> mValues;
        private boolean mMustCloneValues = false;

        public Builder() {
            mValues = new SparseArray<>();
        }

        /**
         * Add a new enumerated value.
         *
         * @param name The string name of the enumeration value
         * @param value The {@code int} value of the enumeration value
         * @return This builder
         */
        @NonNull
        public Builder addValue(@NonNull String name, int value) {
            // Save an allocation, only re-clone if the builder is used again after building
            if (mMustCloneValues) {
                mValues = mValues.clone();
            }

            mValues.put(value, Objects.requireNonNull(name));
            return this;
        }

        /**
         * Build a new {@link IntEnumMapping} from this builder.
         *
         * @return A new mapping
         */
        @NonNull
        public IntEnumMapping build() {
            mMustCloneValues = true;
            return new IntEnumMapping(this);
        }
    }
}
