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

package android.app;

import android.os.ParcelFileDescriptor;

/**
 * An interface that's to be used by {@link ApplicationExitInfo#getTraceFile()}
 * to retrieve the actual file descriptor to its trace file.
 *
 * @hide
 */
interface IAppTraceRetriever {
    /**
     * Retrieve the trace file with given packageName/uid/pid.
     *
     * @param packagename The target package name of the trace
     * @param uid The target UID of the trace
     * @param pid The target PID of the trace
     * @return The file descriptor to the trace file, or null if it's not found.
     */
    ParcelFileDescriptor getTraceFileDescriptor(in String packageName,
            int uid, int pid);
}
