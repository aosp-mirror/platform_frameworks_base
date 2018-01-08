/*
 * Copyright (C) 2007 The Android Open Source Project
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

import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

/**
 * Constants for common byte-related units. Note that both SI and IEC units are
 * supported, and you'll need to pick the correct one for your use-case.
 * <p>
 * This design is mirrored after {@link TimeUnit} and {@link ChronoUnit}.
 *
 * @hide
 */
public enum DataUnit {
    KILOBYTES { @Override public long toBytes(long v) { return v * 1_000; } },
    MEGABYTES { @Override public long toBytes(long v) { return v * 1_000_000; } },
    GIGABYTES { @Override public long toBytes(long v) { return v * 1_000_000_000; } },
    KIBIBYTES { @Override public long toBytes(long v) { return v * 1_024; } },
    MEBIBYTES { @Override public long toBytes(long v) { return v * 1_048_576; } },
    GIBIBYTES { @Override public long toBytes(long v) { return v * 1_073_741_824; } };

    public long toBytes(long v) {
        throw new AbstractMethodError();
    }
}
