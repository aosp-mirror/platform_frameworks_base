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

import com.android.tools.r8.keepanno.annotations.KeepForApi;

import java.io.PrintWriter;

/**
 * Interface that OEMs should implement in order to inject custom code to system process.
 * Communication between OEM Plugin and Framework is implemented via {@link PluginStorage}.
 * OEM Plugin pushes values to PluginStorage, that are picked up by
 * {@link PluginManager.PluginChangeListener}, implemented on Framework side.
 * Avoid calling heavy operations in constructor - it will be called during boot and will
 * negatively impact boot time. Use onBootComplete and separate thread for long running operations.
 */
@KeepForApi
public interface Plugin {

    /**
     * Called when device boot completed
     */
    void onBootCompleted();

    /**
     * Print the object's state and debug information into the given stream.
     */
    void dump(PrintWriter pw);
}

