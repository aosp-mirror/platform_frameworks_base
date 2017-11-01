/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.power;

import android.util.proto.ProtoOutputStream;

/**
 * Low-level suspend blocker mechanism equivalent to holding a partial wake lock.
 *
 * This interface is used internally to avoid introducing internal dependencies
 * on the high-level wake lock mechanism.
 */
interface SuspendBlocker {
    /**
     * Acquires the suspend blocker.
     * Prevents the CPU from going to sleep.
     *
     * Calls to acquire() nest and must be matched by the same number
     * of calls to release().
     */
    void acquire();

    /**
     * Releases the suspend blocker.
     * Allows the CPU to go to sleep if no other suspend blockers are held.
     *
     * It is an error to call release() if the suspend blocker has not been acquired.
     * The system may crash.
     */
    void release();

    void writeToProto(ProtoOutputStream proto, long fieldId);
}
