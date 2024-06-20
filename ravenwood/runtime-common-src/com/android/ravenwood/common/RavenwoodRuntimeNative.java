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
package com.android.ravenwood.common;

import java.io.FileDescriptor;

/**
 * Class to host all the JNI methods used in ravenwood runtime.
 */
public class RavenwoodRuntimeNative {
    private RavenwoodRuntimeNative() {
    }

    static {
        RavenwoodCommonUtils.ensureOnRavenwood();
        RavenwoodCommonUtils.loadRavenwoodNativeRuntime();
    }

    public static native void applyFreeFunction(long freeFunction, long nativePtr);

    public static native long nLseek(int fd, long offset, int whence);

    public static native int[] nPipe2(int flags);

    public static native int nDup(int oldfd);

    public static native int nFcntlInt(int fd, int cmd, int arg);

    public static long lseek(FileDescriptor fd, long offset, int whence) {
        return nLseek(JvmWorkaround.getInstance().getFdInt(fd), offset, whence);
    }

    public static FileDescriptor[] pipe2(int flags) {
        var fds = nPipe2(flags);
        var ret = new FileDescriptor[] {
                new FileDescriptor(),
                new FileDescriptor(),
        };
        JvmWorkaround.getInstance().setFdInt(ret[0], fds[0]);
        JvmWorkaround.getInstance().setFdInt(ret[1], fds[1]);

        return ret;
    }

    public static FileDescriptor dup(FileDescriptor fd) {
        var fdInt = nDup(JvmWorkaround.getInstance().getFdInt(fd));

        var retFd = new java.io.FileDescriptor();
        JvmWorkaround.getInstance().setFdInt(retFd, fdInt);
        return retFd;
    }

    public static int fcntlInt(FileDescriptor fd, int cmd, int arg) {
        var fdInt = JvmWorkaround.getInstance().getFdInt(fd);

        return nFcntlInt(fdInt, cmd, arg);
    }
}
