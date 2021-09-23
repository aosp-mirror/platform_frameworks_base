/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.server;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.IndentingPrintWriter;

/**
 * Interface used to dump {@link SystemServer} state that is not associated with any service.
 *
 * <p>See {@link SystemServer.SystemServerDumper} for usage example.
 */
public interface Dumpable {

    /**
     * Dumps the state.
     */
    void dump(@NonNull IndentingPrintWriter pw, @Nullable String[] args);

    /**
     * Gets the name of the dumpable.
     *
     * <p>If not overridden, will return the simple class name.
     */
    default String getDumpableName() {
        return Dumpable.this.getClass().getSimpleName();
    }
}
