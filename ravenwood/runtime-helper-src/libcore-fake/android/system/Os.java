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

import com.android.ravenwood.RavenwoodRuntimeNative;
import com.android.ravenwood.common.JvmWorkaround;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;

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

    /** Ravenwood version of the OS API. */
    public static FileDescriptor[] pipe() throws ErrnoException {
        return RavenwoodRuntimeNative.pipe2(0);
    }

    public static FileDescriptor dup(FileDescriptor fd) throws ErrnoException {
        return RavenwoodRuntimeNative.dup(fd);
    }

    public static int fcntlInt(FileDescriptor fd, int cmd, int arg) throws ErrnoException {
        return RavenwoodRuntimeNative.fcntlInt(fd, cmd, arg);
    }

    public static StructStat fstat(FileDescriptor fd) throws ErrnoException {
        return RavenwoodRuntimeNative.fstat(fd);
    }

    public static StructStat lstat(String path) throws ErrnoException {
        return RavenwoodRuntimeNative.lstat(path);
    }

    public static StructStat stat(String path) throws ErrnoException {
        return RavenwoodRuntimeNative.stat(path);
    }

    /** Ravenwood version of the OS API. */
    public static void close(FileDescriptor fd) throws ErrnoException {
        try {
            JvmWorkaround.getInstance().closeFd(fd);
        } catch (IOException e) {
            // The only valid error on Linux that can happen is EIO
            throw new ErrnoException("close", OsConstants.EIO);
        }
    }

    public static FileDescriptor open(String path, int flags, int mode) throws ErrnoException {
        return RavenwoodRuntimeNative.open(path, flags, mode);
    }

    /** Ravenwood version of the OS API. */
    public static int pread(FileDescriptor fd, byte[] bytes, int byteOffset, int byteCount,
            long offset) throws ErrnoException, InterruptedIOException {
        var channel = new FileInputStream(fd).getChannel();
        var buf = ByteBuffer.wrap(bytes, byteOffset, byteCount);
        try {
            return channel.read(buf, offset);
        } catch (AsynchronousCloseException e) {
            throw new InterruptedIOException(e.getMessage());
        } catch (IOException e) {
            // Most likely EIO
            throw new ErrnoException("pread", OsConstants.EIO, e);
        }
    }

    public static void setenv(String name, String value, boolean overwrite) throws ErrnoException {
        RavenwoodRuntimeNative.setenv(name, value, overwrite);
    }
}
