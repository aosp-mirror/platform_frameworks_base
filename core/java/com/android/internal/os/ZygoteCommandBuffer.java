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

package com.android.internal.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.LocalSocket;

import java.io.FileDescriptor;
import java.lang.ref.Reference;  // For reachabilityFence.

/**
 * A native-accessible buffer for Zygote commands. Designed to support repeated forking
 * of applications without intervening memory allocation, thus keeping zygote memory
 * as stable as possible.
 * A ZygoteCommandBuffer may have an associated socket from which it can be refilled.
 * Otherwise the contents are explicitly set by getInstance().
 *
 * NOT THREAD-SAFE. No methods may be called concurrently from multiple threads.
 *
 * Only one ZygoteCommandBuffer can exist at a time.
 * Must be explicitly closed before being dropped.
 * @hide
 */
class ZygoteCommandBuffer implements AutoCloseable {
    private long mNativeBuffer;  // Not final so that we can clear it in close().

    /**
     * The command socket.
     *
     * mSocket is retained in the child process in "peer wait" mode, so
     * that it closes when the child process terminates. In other cases,
     * it is closed in the peer.
     */
    private final LocalSocket mSocket;
    private final int mNativeSocket;

    /**
     * Constructs instance from file descriptor from which the command will be read.
     * Only a single instance may be live in a given process. The native code checks.
     *
     * @param fd file descriptor to read from. The setCommand() method may be used if and only if
     * fd is null.
     */
    ZygoteCommandBuffer(@Nullable LocalSocket socket) {
        mSocket = socket;
        if (socket == null) {
            mNativeSocket = -1;
        } else {
            mNativeSocket = mSocket.getFileDescriptor().getInt$();
        }
        mNativeBuffer = getNativeBuffer(mNativeSocket);
    }

    /**
     * Constructs an instance with explicitly supplied arguments and an invalid
     * file descriptor. Can only be used for a single command.
     */
    ZygoteCommandBuffer(@NonNull String[] args) {
        this((LocalSocket) null);
        setCommand(args);
    }


    private static native long getNativeBuffer(int fd);

    /**
     * Deallocate native resources associated with the one and only command buffer, and prevent
     * reuse. Subsequent calls to getInstance() will yield a new buffer.
     * We do not close the associated socket, if any.
     */
    @Override
    public void close() {
        freeNativeBuffer(mNativeBuffer);
        mNativeBuffer = 0;
    }

    private static native void freeNativeBuffer(long /* NativeCommandBuffer* */ nbuffer);

    /**
     * Read at least the first line of the next command into the buffer, return the argument count
     * from that line. Assumes we are initially positioned at the beginning of the first line of
     * the command. Leave the buffer positioned at the beginning of the second command line, i.e.
     * the first argument. If the buffer has no associated file descriptor, we just reposition to
     * the beginning of the buffer, and reread existing contents.  Returns zero if we started out
     * at EOF.
     */
    int getCount() {
        try {
            return nativeGetCount(mNativeBuffer);
        } finally {
            // Make sure the mNativeSocket doesn't get closed due to early finalization.
            Reference.reachabilityFence(mSocket);
        }
    }

    private static native int nativeGetCount(long /* NativeCommandBuffer* */ nbuffer);


    /*
     * Set the buffer to contain the supplied sequence of arguments.
     */
    private void setCommand(String[] command) {
        int nArgs = command.length;
        insert(mNativeBuffer, Integer.toString(nArgs));
        for (String s: command) {
            insert(mNativeBuffer, s);
        }
        // Native code checks there is no socket; hence no reachabilityFence.
    }

    private static native void insert(long /* NativeCommandBuffer* */ nbuffer, String s);

    /**
     * Retrieve the next argument/line from the buffer, filling the buffer as necessary.
     */
    String nextArg() {
        try {
            return nativeNextArg(mNativeBuffer);
        } finally {
            Reference.reachabilityFence(mSocket);
        }
    }

    private static native String nativeNextArg(long /* NativeCommandBuffer* */ nbuffer);

    void readFullyAndReset() {
        try {
            nativeReadFullyAndReset(mNativeBuffer);
        } finally {
            Reference.reachabilityFence(mSocket);
        }
    }

    private static native void nativeReadFullyAndReset(long /* NativeCommandBuffer* */ nbuffer);

    /**
     * Fork a child as specified by the current command in the buffer, and repeat this process
     * after refilling the buffer, so long as the buffer clearly contains another fork command.
     *
     * @param zygoteSocket socket from which to obtain new connections when current one is
     *         disconnected
     * @param expectedUid Peer UID for current connection. We refuse to deal with requests from
     *         a different UID.
     * @param minUid the smallest uid that may be request for the child process.
     * @param firstNiceName The name for the initial process to be forked. Used only for error
     *         reporting.
     *
     * @return true in the child, false in the parent. In the parent case, the buffer is positioned
     * at the beginning of a command that still needs to be processed.
     */
    boolean forkRepeatedly(FileDescriptor zygoteSocket, int expectedUid, int minUid,
                       String firstNiceName) {
        try {
            return nativeForkRepeatedly(mNativeBuffer, zygoteSocket.getInt$(),
                    expectedUid, minUid, firstNiceName);
        } finally {
            Reference.reachabilityFence(mSocket);
            Reference.reachabilityFence(zygoteSocket);
        }
    }

    /*
     * Repeatedly fork children as above. It commonly does not return in the parent, but it may.
     * @return true in the chaild, false in the parent if we encounter a command we couldn't handle.
     */
    private static native boolean nativeForkRepeatedly(long /* NativeCommandBuffer* */ nbuffer,
                                                   int zygoteSocketRawFd,
                                                   int expectedUid,
                                                   int minUid,
                                                   String firstNiceName);

}
