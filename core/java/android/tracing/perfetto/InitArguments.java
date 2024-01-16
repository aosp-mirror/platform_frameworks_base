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

package android.tracing.perfetto;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * @hide
 */
public class InitArguments {
    public final @PerfettoBackend int backends;

    /**
     * @hide
     */
    @IntDef(value = {
            PERFETTO_BACKEND_IN_PROCESS,
            PERFETTO_BACKEND_SYSTEM,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PerfettoBackend {}

    // The in-process tracing backend. Keeps trace buffers in the process memory.
    public static final int PERFETTO_BACKEND_IN_PROCESS = (1 << 0);

    // The system tracing backend. Connects to the system tracing service (e.g.
    // on Linux/Android/Mac uses a named UNIX socket).
    public static final int PERFETTO_BACKEND_SYSTEM = (1 << 1);

    public static InitArguments DEFAULTS = new InitArguments(PERFETTO_BACKEND_SYSTEM);

    public static InitArguments TESTING = new InitArguments(PERFETTO_BACKEND_IN_PROCESS);

    public InitArguments(@PerfettoBackend int backends) {
        this.backends = backends;
    }
}
