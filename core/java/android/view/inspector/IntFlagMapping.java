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

import java.util.ArrayList;

/**
 * Maps the values of an {int} property to arrays of string for properties that encode flags.
 *
 * An {@link InspectionCompanion} may provide an instance of this class to a {@link PropertyMapper}
 * for flag values packed into primitive {int} properties.
 *
 * Each flag has a
 *
 * This class is immutable, and must be constructed by a {@link Builder}.
 *
 * @see PropertyMapper#mapIntFlag(String, int, IntFlagMapping)
 */
public final class IntFlagMapping {
    private final Flag[] mFlags;

    /**
     * Get an array of the names of enabled flags for a given property value.
     *
     * @param value The value of the property
     * @return The names of the enabled flags
     */
    @NonNull
    public String[] namesOf(int value) {
        ArrayList<String> enabledFlagNames = new ArrayList<>(mFlags.length);

        for (Flag flag : mFlags) {
            if (flag.isEnabledFor(value)) {
                enabledFlagNames.add(flag.mName);
            }
        }

        return enabledFlagNames.toArray(new String[enabledFlagNames.size()]);
    }

    /**
     * Create a new instance from a builder.
     *
     * This constructor is private, use {@link Builder#build()} instead.
     *
     * @param builder A builder to create from
     */
    private IntFlagMapping(Builder builder) {
        mFlags = builder.mFlags.toArray(new Flag[builder.mFlags.size()]);
    }

    /**
     * A builder for {@link IntFlagMapping}.
     */
    public static final class Builder {
        private ArrayList<Flag> mFlags;

        public Builder() {
            mFlags = new ArrayList<>();
        }

        /**
         * Add a new flag without a mask.
         *
         * The target value will be used as a mask, to handle the common case where flag values
         * are not mutually exclusive. The flag will be considered enabled for a property value if
         * the result of bitwise anding the target and the value equals the target, that is:
         * {(value & target) == target}.
         *
         * @param name The name of the flag
         * @param target The value to compare against
         * @return This builder
         */
        @NonNull
        public Builder addFlag(@NonNull String name, int target) {
            mFlags.add(new Flag(name, target, target));
            return this;
        }

        /**
         * Add a new flag with a mask.
         *
         * The flag will be considered enabled for a property value if the result of bitwise anding
         * the value and the mask equals the target, that is: {(value & mask) == target}.
         *
         * @param name The name of the flag
         * @param target The value to compare against
         * @param mask A bit mask
         * @return This builder
         */
        @NonNull
        public Builder addFlag(@NonNull String name, int target, int mask) {
            mFlags.add(new Flag(name, target, mask));
            return this;
        }

        /**
         * Clear the builder, allowing for recycling.
         */
        public void clear() {
            mFlags.clear();
        }

        /**
         * Build a new {@link IntFlagMapping} from this builder.
         *
         * @return A new mapping
         */
        @NonNull
        public IntFlagMapping build() {
            return new IntFlagMapping(this);
        }
    }

    /**
     * Inner class that holds the name, mask, and target value of a flag
     */
    private static final class Flag {
        @NonNull private final String mName;
        private final int mTarget;
        private final int mMask;

        private Flag(@NonNull String name, int target, int mask) {
            mName = name;
            mTarget = target;
            mMask = mask;
        }

        /**
         * Compare the supplied property value against the mask and taget.
         *
         * @param value The value to check
         * @return True if this flag is enabled
         */
        private boolean isEnabledFor(int value) {
            return (value & mMask) == mTarget;
        }
    }
}
