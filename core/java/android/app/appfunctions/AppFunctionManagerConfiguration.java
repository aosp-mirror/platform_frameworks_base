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

package android.app.appfunctions;

import static android.app.appfunctions.flags.Flags.enableAppFunctionManager;

import android.annotation.NonNull;
import android.content.Context;

/**
 * Represents the system configuration of support for the {@code AppFunctionManager} and associated
 * systems.
 *
 * @hide
 */
public class AppFunctionManagerConfiguration {
    /**
     * Constructs a new instance of {@code AppFunctionManagerConfiguration}.
     *
     * @param context context
     */
    public AppFunctionManagerConfiguration(@NonNull final Context context) {
        // Context can be used to access system features, etc.
    }

    /**
     * Indicates whether the current target is intended to support {@code AppFunctionManager}.
     *
     * @return {@code true} if supported; otherwise {@code false}
     */
    public boolean isSupported() {
        return enableAppFunctionManager();
    }

    /**
     * Indicates whether the current target is intended to support {@code AppFunctionManager}.
     *
     * @param context context
     * @return {@code true} if supported; otherwise {@code false}
     */
    public static boolean isSupported(@NonNull final Context context) {
        return new AppFunctionManagerConfiguration(context).isSupported();
    }
}
