/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.util;

import static android.os.Binder.getCallingPid;
import static android.os.Binder.getCallingUid;

import android.os.Process;
import android.os.UserHandle;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Utility class to check calling permissions on the network stack.
 */
public final class PermissionUtil {
    private static final AtomicInteger sSystemPid = new AtomicInteger(-1);

    /**
     * Check that the caller is allowed to communicate with the network stack.
     * @throws SecurityException The caller is not allowed to communicate with the network stack.
     */
    public static void checkNetworkStackCallingPermission() {
        final int caller = getCallingUid();
        if (caller == Process.SYSTEM_UID) {
            checkConsistentSystemPid();
            return;
        }

        if (UserHandle.getAppId(caller) != Process.BLUETOOTH_UID) {
            throw new SecurityException("Invalid caller: " + caller);
        }
    }

    private static void checkConsistentSystemPid() {
        // Apart from the system server process, no process with a system UID should try to
        // communicate with the network stack. This is to ensure that the network stack does not
        // need to maintain behavior for clients it was not designed to work with.
        // Checking that all calls from a system UID originate from the same PID loosely enforces
        // this restriction as if another system process calls the network stack first, the system
        // server would lose access to the network stack and cause obvious failures. If the system
        // server calls the network stack first, other clients would lose access as expected.
        final int systemPid = getCallingPid();
        if (sSystemPid.compareAndSet(-1, systemPid)) {
            // sSystemPid was unset (-1): this was the first call
            return;
        }

        if (sSystemPid.get() != systemPid) {
            throw new SecurityException("Invalid PID for the system server, expected "
                    + sSystemPid.get() + " but was called from " + systemPid);
        }
    }

    /**
     * Check that the caller is allowed to dump the network stack, e.g. dumpsys.
     * @throws SecurityException The caller is not allowed to dump the network stack.
     */
    public static void checkDumpPermission() {
        final int caller = getCallingUid();
        if (caller != Process.SYSTEM_UID && caller != Process.ROOT_UID
                && caller != Process.SHELL_UID) {
            throw new SecurityException("No dump permissions for caller: " + caller);
        }
    }

    private PermissionUtil() {
        throw new UnsupportedOperationException("This class is not to be instantiated");
    }
}
