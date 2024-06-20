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
package android.system;

import com.android.ravenwood.common.RavenwoodRuntimeNative;

import java.io.FileDescriptor;

/**
 * OS class replacement used on Ravenwood. For now, we just implement APIs as we need them...
 * TODO(b/340887115): Need a better integration with libcore.
 */
public final class Os {
    private Os() {}

    public static long lseek(FileDescriptor fd, long offset, int whence) throws ErrnoException {
        return RavenwoodRuntimeNative.lseek(fd, offset, whence);
    }


    public static FileDescriptor[] pipe2(int flags) throws ErrnoException {
        return RavenwoodRuntimeNative.pipe2(flags);
    }

    public static FileDescriptor dup(FileDescriptor fd) throws ErrnoException {
        return RavenwoodRuntimeNative.dup(fd);
    }

    public static int fcntlInt(FileDescriptor fd, int cmd, int arg) throws ErrnoException {
        return RavenwoodRuntimeNative.fcntlInt(fd, cmd, arg);
    }
}
