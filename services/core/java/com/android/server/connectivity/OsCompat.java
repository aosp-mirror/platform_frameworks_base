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

package com.android.server.connectivity;

import android.system.ErrnoException;
import android.system.Os;

import java.io.FileDescriptor;

/**
 * Compatibility utility for android.system.Os core platform APIs.
 *
 * Connectivity has access to such APIs, but they are not part of the module_current stubs yet
 * (only core_current). Most stable core platform APIs are included manually in the connectivity
 * build rules, but because Os is also part of the base java SDK that is earlier on the
 * classpath, the extra core platform APIs are not seen.
 *
 * TODO (b/157639992, b/183097033): remove as soon as core_current is part of system_server_current
 * @hide
 */
public class OsCompat {
    // This value should be correct on all architectures supported by Android, but hardcoding ioctl
    // numbers should be avoided.
    /**
     * @see android.system.OsConstants#TIOCOUTQ
     */
    public static final int TIOCOUTQ = 0x5411;

    /**
     * @see android.system.Os#getsockoptInt(FileDescriptor, int, int)
     */
    public static int getsockoptInt(FileDescriptor fd, int level, int option) throws
            ErrnoException {
        try {
            return (int) Os.class.getMethod(
                    "getsockoptInt", FileDescriptor.class, int.class, int.class)
                    .invoke(null, fd, level, option);
        } catch (ReflectiveOperationException e) {
            if (e.getCause() instanceof ErrnoException) {
                throw (ErrnoException) e.getCause();
            }
            throw new IllegalStateException("Error calling getsockoptInt", e);
        }
    }

    /**
     * @see android.system.Os#ioctlInt(FileDescriptor, int)
     */
    public static int ioctlInt(FileDescriptor fd, int cmd) throws
            ErrnoException {
        try {
            return (int) Os.class.getMethod(
                    "ioctlInt", FileDescriptor.class, int.class).invoke(null, fd, cmd);
        } catch (ReflectiveOperationException e) {
            if (e.getCause() instanceof ErrnoException) {
                throw (ErrnoException) e.getCause();
            }
            throw new IllegalStateException("Error calling ioctlInt", e);
        }
    }
}
