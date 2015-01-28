/*
 * Copyright 2015 The Android Open Source Project
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

package android.os;

/** {@hide} */
interface IProcessInfoService
{
    /**
     * For each PID in the given input array, write the current process state
     * for that process into the output array, or ActivityManager.PROCESS_STATE_NONEXISTENT
     * to indicate that no process with the given PID exists.
     */
    void getProcessStatesFromPids(in int[] pids, out int[] states);
}

