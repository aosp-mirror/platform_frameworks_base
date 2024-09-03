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

package com.android.server.am;

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;

import android.os.Process;

/**
 * A collection of interfaces to manage the freezer.  All access to the freezer goes through an
 * instance of this class.  The class can be overridden for testing.
 *
 * Methods may be called without external synchronization.  Multiple instances of this class can be
 * used concurrently.
 */
class Freezer {

    /**
     * Freeze or unfreeze the specified process.
     *
     * @param pid Identifier of the process to freeze or unfreeze.
     * @param uid Identifier of the user the process is running under.
     * @param frozen Specify whether to free (true) or unfreeze (false).
     */
    public void setProcessFrozen(int pid, int uid, boolean frozen) {
        Process.setProcessFrozen(pid, uid, frozen);
    }

    /**
     * Informs binder that a process is about to be frozen. If freezer is enabled on a process via
     * this method, this method will synchronously dispatch all pending transactions to the
     * specified pid. This method will not add significant latencies when unfreezing.
     * After freezing binder calls, binder will block all transaction to the frozen pid, and return
     * an error to the sending process.
     *
     * @param pid the target pid for which binder transactions are to be frozen
     * @param freeze specifies whether to flush transactions and then freeze (true) or unfreeze
     * binder for the specified pid.
     * @param timeoutMs the timeout in milliseconds to wait for the binder interface to freeze
     * before giving up.
     *
     * @throws RuntimeException in case a flush/freeze operation could not complete successfully.
     * @return 0 if success, or -EAGAIN indicating there's pending transaction.
     */
    public int freezeBinder(int pid, boolean freeze, int timeoutMs) {
        return nativeFreezeBinder(pid, freeze, timeoutMs);
    }

    /**
     * Retrieves binder freeze info about a process.
     * @param pid the pid for which binder freeze info is to be retrieved.
     *
     * @throws RuntimeException if the operation could not complete successfully.
     * @return a bit field reporting the binder freeze info for the process.
     */
    public int getBinderFreezeInfo(int pid) {
        return nativeGetBinderFreezeInfo(pid);
    }

    /**
     * Determines whether the freezer is supported by this system.
     * @return true if the freezer is supported.
     */
    public boolean isFreezerSupported() {
        return nativeIsFreezerSupported();
    }

    // Native methods

    /**
     * Informs binder that a process is about to be frozen. If freezer is enabled on a process via
     * this method, this method will synchronously dispatch all pending transactions to the
     * specified pid. This method will not add significant latencies when unfreezing.
     * After freezing binder calls, binder will block all transaction to the frozen pid, and return
     * an error to the sending process.
     *
     * @param pid the target pid for which binder transactions are to be frozen
     * @param freeze specifies whether to flush transactions and then freeze (true) or unfreeze
     * binder for the specified pid.
     * @param timeoutMs the timeout in milliseconds to wait for the binder interface to freeze
     * before giving up.
     *
     * @throws RuntimeException in case a flush/freeze operation could not complete successfully.
     * @return 0 if success, or -EAGAIN indicating there's pending transaction.
     */
    private static native int nativeFreezeBinder(int pid, boolean freeze, int timeoutMs);

    /**
     * Retrieves binder freeze info about a process.
     * @param pid the pid for which binder freeze info is to be retrieved.
     *
     * @throws RuntimeException if the operation could not complete successfully.
     * @return a bit field reporting the binder freeze info for the process.
     */
    private static native int nativeGetBinderFreezeInfo(int pid);

    /**
     * Return 0 if the freezer is supported on this platform and -1 otherwise.
     */
    private static native boolean nativeIsFreezerSupported();
}
