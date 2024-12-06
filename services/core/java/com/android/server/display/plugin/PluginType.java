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

package com.android.server.display.plugin;

import com.android.internal.annotations.Keep;
import com.android.internal.annotations.VisibleForTesting;

/**
 * Represent customisation entry point to Framework. OEM and Framework team should define
 * new PluginTypes together, after that, Framework team can integrate listener and OEM team
 * create Plugin implementation
 *
 * @param <T> type of plugin value
 */
@Keep
public class PluginType<T> {

    final Class<T> mType;
    final String mName;

    @VisibleForTesting
    PluginType(Class<T> type, String name) {
        mType = type;
        mName = name;
    }

    @Override
    public String toString() {
        return "PluginType{"
                + "mType=" + mType
                + ", mName=" + mName
                + '}';
    }
}
