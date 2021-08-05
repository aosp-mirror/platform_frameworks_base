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

package com.android.systemui.plugins;

import com.android.systemui.plugins.annotations.ProvidesInterface;


/**
 * Plugin for loading flag values from an alternate source of truth.
 */
@ProvidesInterface(action = FlagReaderPlugin.ACTION, version = FlagReaderPlugin.VERSION)
public interface FlagReaderPlugin extends Plugin {
    int VERSION = 1;
    String ACTION = "com.android.systemui.flags.FLAG_READER_PLUGIN";

    /** Returns a boolean value for the given flag. */
    default boolean isEnabled(int id, boolean def) {
        return def;
    }

    /** Returns a string value for the given flag id. */
    default String getValue(int id, String def) {
        return def;
    }

    /** Returns a int value for the given flag. */
    default int getValue(int id, int def) {
        return def;
    }

    /** Returns a long value for the given flag. */
    default long getValue(int id, long def) {
        return def;
    }

    /** Returns a float value for the given flag. */
    default float getValue(int id, float def) {
        return def;
    }

    /** Returns a double value for the given flag. */
    default double getValue(int id, double def) {
        return def;
    }
}
