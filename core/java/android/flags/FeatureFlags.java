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

import java.util.HashSet;
import java.util.Set;

/**
 * A class for querying constants from the system - primarily booleans.
 *
 * Clients using this class can define their flags and their default values in one place,
 * can override those values on running devices for debugging and testing purposes, and can control
 * what flags are available to be used on release builds.
 *
 * TODO(b/279054964): A lot. This is skeleton code right now.
 * @hide
 */
public class FeatureFlags {
    private static FeatureFlags sInstance;
    private static final Object sInstanceLock = new Object();

    private final Set<ChangeListener> mListeners = new HashSet<>();

    /**
     * Obtain a per-process instance of FeatureFlags.
     * @return
     */
    @NonNull
    public static FeatureFlags getInstance() {
        synchronized (sInstanceLock) {
            if (sInstance == null) {
                sInstance = new FeatureFlags();
            }
        }

        return sInstance;
    }

    FeatureFlags() {
    }

    /**
     * Returns whether the supplied flag is true or not.
     *
     * {@link BooleanFlag} should only be used in debug builds. They do not get optimized out.
     *
     * The first time a flag is read, its value is cached for the lifetime of the process.
     */
    public boolean isEnabled(@NonNull BooleanFlag flag) {
        return flag.getDefault();
    }

    /**
     * Returns whether the supplied flag is true or not.
     *
     * Always returns false.
     */
    public boolean isEnabled(@NonNull FusedOffFlag flag) {
        return false;
    }

    /**
     * Returns whether the supplied flag is true or not.
     *
     * Always returns true;
     */
    public boolean isEnabled(@NonNull FusedOnFlag flag) {
        return true;
    }

    /**
     * Returns whether the supplied flag is true or not.
     *
     * Can return a different value for the flag each time it is called if an override comes in.
     */
    public boolean isCurrentlyEnabled(@NonNull DynamicBooleanFlag flag) {
        return flag.getDefault();
    }

    /**
     * Add a listener to be alerted when a {@link DynamicFlag} changes.
     *
     * See also {@link #removeChangeListener(ChangeListener)}.
     *
     * @param listener The listener to add.
     */
    public void addChangeListener(@NonNull ChangeListener listener) {
        mListeners.add(listener);
    }

    /**
     * Remove a listener that was added earlier.
     *
     * See also {@link #addChangeListener(ChangeListener)}.
     *
     * @param listener The listener to remove.
     */
    public void removeChangeListener(@NonNull ChangeListener listener) {
        mListeners.remove(listener);
    }

    /**
     * A simpler listener that is alerted when a {@link DynamicFlag} changes.
     *
     * See {@link #addChangeListener(ChangeListener)}
     */
    public interface ChangeListener {
        /**
         * Called when a {@link DynamicFlag} changes.
         *
         * @param flag The flag that has changed.
         */
        void onFlagChanged(DynamicFlag<?> flag);
    }
}
