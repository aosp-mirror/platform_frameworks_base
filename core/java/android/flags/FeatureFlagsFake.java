/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.flags;

import android.annotation.NonNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * An implementation of {@link FeatureFlags} for testing.
 *
 * Before you read a flag from using this Fake, you must set that flag using
 * {@link #setFlagValue(BooleanFlagBase, boolean)}. This ensures that your tests are deterministic.
 *
 * If you are relying on {@link FeatureFlags#getInstance()} to access FeatureFlags in your code
 * under test, (instead of dependency injection), you can pass an instance of this fake to
 * {@link FeatureFlags#setInstance(FeatureFlags)}. Be sure to call that method again, passing null,
 * to ensure hermetic testing - you don't want static state persisting between your test methods.
 *
 * @hide
 */
public class FeatureFlagsFake extends FeatureFlags {
    private final Map<BooleanFlagBase, Boolean> mFlagValues = new HashMap<>();
    private final Set<BooleanFlagBase> mReadFlags = new HashSet<>();

    public FeatureFlagsFake(IFeatureFlags iFeatureFlags) {
        super(iFeatureFlags);
    }

    @Override
    public boolean isEnabled(@NonNull BooleanFlag flag) {
        return requireFlag(flag);
    }

    @Override
    public boolean isEnabled(@NonNull FusedOffFlag flag) {
        return requireFlag(flag);
    }

    @Override
    public boolean isEnabled(@NonNull FusedOnFlag flag) {
        return requireFlag(flag);
    }

    @Override
    public boolean isCurrentlyEnabled(@NonNull DynamicBooleanFlag flag) {
        return requireFlag(flag);
    }

    @Override
    protected void syncInternal(Set<Flag<?>> dirtyFlags) {
    }

    /**
     * Explicitly set a flag's value for reading in tests.
     *
     * You _must_ call this for every flag your code-under-test will read. Otherwise, an
     * {@link IllegalStateException} will be thrown.
     *
     * You are able to set values for {@link FusedOffFlag} and {@link FusedOnFlag}, despite those
     * flags having a fixed value at compile time, since unit tests should still test the state of
     * those flags as both true and false. I.e. a flag that is off might be turned on in a future
     * build or vice versa.
     *
     * You can not call this method _after_ a non-dynamic flag has been read. Non-dynamic flags
     * are held stable in the system, so changing a value after reading would not match
     * real-implementation behavior.
     *
     * Calling this method will trigger any {@link android.flags.FeatureFlags.ChangeListener}s that
     * are registered for the supplied flag if the flag is a {@link DynamicFlag}.
     *
     * @param flag  The BooleanFlag that you want to set a value for.
     * @param value The value that the flag should return when accessed.
     */
    public void setFlagValue(@NonNull BooleanFlagBase flag, boolean value) {
        if (!(flag instanceof DynamicBooleanFlag) && mReadFlags.contains(flag)) {
            throw new RuntimeException(
                    "You can not set the value of a flag after it has been read. Tried to set "
                            + flag + " to " + value + " but it already " + mFlagValues.get(flag));
        }
        mFlagValues.put(flag, value);
        if (flag instanceof DynamicBooleanFlag) {
            onFlagChange((DynamicFlag<?>) flag);
        }
    }

    private boolean requireFlag(BooleanFlagBase flag) {
        if (!mFlagValues.containsKey(flag)) {
            throw new IllegalStateException(
                    "Tried to access " + flag + " in test but no overrided specified. You must "
                            + "call #setFlagValue for each flag read in a test.");
        }
        mReadFlags.add(flag);

        return mFlagValues.get(flag);
    }

}
