/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.flags;


/**
 * Plugin for loading flag values
 */
public interface FlagReader {
    /** Returns a boolean value for the given flag. */
    default boolean isEnabled(int id, boolean def) {
        return def;
    }

    /** Add a listener to be alerted when any flag changes. */
    default void addListener(Listener listener) {}

    /** Remove a listener to be alerted when any flag changes. */
    default void removeListener(Listener listener) {}

    /** A simple listener to be alerted when a flag changes. */
    interface Listener {
        /** */
        void onFlagChanged(int id);
    }
}
