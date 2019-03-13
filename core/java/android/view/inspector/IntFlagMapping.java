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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Maps the values of an {@code int} property to sets of string for properties that encode flags.
 *
 * An {@link InspectionCompanion} may provide an instance of this class to a {@link PropertyMapper}
 * for flag values packed into primitive {@code int} properties.
 *
 * Each flag has a mask and a target value, for non-exclusive flags, the target can also be used as
 * the mask. A given integer value is compared against each flag to find what flags are active for
 * it by bitwise anding it with the mask and comparing the result against the target, that is,
 * {@code (value & mask) == target}.
 *
 * @see PropertyMapper#mapIntFlag(String, int, java.util.function.IntFunction)
 */
public final class IntFlagMapping {
    private final List<Flag> mFlags = new ArrayList<>();

    /**
     * Get a set of the names of enabled flags for a given property value.
     *
     * @param value The value of the property
     * @return The names of the enabled flags, empty if no flags enabled
     */
    @NonNull
    public Set<String> get(int value) {
        final Set<String> enabledFlagNames = new HashSet<>(mFlags.size());

        for (Flag flag : mFlags) {
            if (flag.isEnabledFor(value)) {
                enabledFlagNames.add(flag.mName);
            }
        }

        return Collections.unmodifiableSet(enabledFlagNames);
    }

    /**
     * Add a mutually exclusive flag to the map.
     *
     * @param mask The bit mask to compare to and with a value
     * @param target The target value to compare the masked value with
     * @param name The name of the flag to include if enabled
     */
    public void add(int mask, int target, @NonNull String name) {
        mFlags.add(new Flag(mask, target, name));
    }

    /**
     * Inner class that holds the name, mask, and target value of a flag
     */
    private static final class Flag {
        @NonNull private final String mName;
        private final int mTarget;
        private final int mMask;

        private Flag(int mask, int target, @NonNull String name) {
            mTarget = target;
            mMask = mask;
            mName = Objects.requireNonNull(name);
        }

        /**
         * Compare the supplied property value against the mask and target.
         *
         * @param value The value to check
         * @return True if this flag is enabled
         */
        private boolean isEnabledFor(int value) {
            return (value & mMask) == mTarget;
        }
    }
}
