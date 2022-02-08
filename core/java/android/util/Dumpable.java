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
package android.util;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.io.PrintWriter;

/**
 * Represents an object whose state can be dumped into a {@link PrintWriter}.
 */
public interface Dumpable {

    /**
     * Gets the name of the {@link Dumpable}.
     *
     * @return class name, by default.
     */
    @NonNull
    default String getDumpableName() {
        return getClass().getName();
    }

    /**
     * Dumps the internal state into the given {@code writer}.
     *
     * @param writer writer to be written to
     * @param args optional list of arguments
     */
    void dump(@NonNull PrintWriter writer, @Nullable String[] args);
}
