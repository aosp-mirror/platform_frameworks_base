/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static android.system.OsConstants.AF_UNIX;
import static android.system.OsConstants.SOCK_STREAM;

import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import libcore.io.IoBridge;
import libcore.io.IoUtils;
import libcore.io.Memory;
import libcore.io.Streams;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.io.SyncFailedException;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Simple bridge that allows file access across process boundaries without
 * returning the underlying {@link FileDescriptor}. This is useful when the
 * server side needs to strongly assert that a client side is completely
 * hands-off.
 *
 * @hide
 */
public class FileBridge extends Thread {
    private static final String TAG = "FileBridge";

    // TODO: consider extending to support bidirectional IO

    private static final int MSG_LENGTH = 8;

    /** CMD_WRITE [len] [data] */
    private static final int CMD_WRITE = 1;
    /** CMD_FSYNC */
    private static final int CMD_FSYNC = 2;

    private FileDescriptor mTarget;

    private final FileDescriptor mServer = new FileDescriptor();
    private final FileDescriptor mClient = new FileDescriptor();

    private volatile boolean mClosed;

    public FileBridge() {
        try {
            Os.socketpair(AF_UNIX, SOCK_STREAM, 0, mServer, mClient);
        } catch (ErrnoException e) {
            throw new RuntimeException("Failed to create bridge");
        }
    }

    public boolean isClosed() {
        return mClosed;
    }

    public void setTargetFile(FileDescriptor target) {
        mTarget = target;
    }

    public FileDescriptor getClientSocket() {
        return mClient;
    }

    @Override
    public void run() {
        final byte[] temp = new byte[8192];
        try {
            while (IoBridge.read(mServer, temp, 0, MSG_LENGTH) == MSG_LENGTH) {
                final int cmd = Memory.peekInt(temp, 0, ByteOrder.BIG_ENDIAN);

                if (cmd == CMD_WRITE) {
                    // Shuttle data into local file
                    int len = Memory.peekInt(temp, 4, ByteOrder.BIG_ENDIAN);
                    while (len > 0) {
                        int n = IoBridge.read(mServer, temp, 0, Math.min(temp.length, len));
                        IoBridge.write(mTarget, temp, 0, n);
                        len -= n;
                    }

                } else if (cmd == CMD_FSYNC) {
                    // Sync and echo back to confirm
                    Os.fsync(mTarget);
                    IoBridge.write(mServer, temp, 0, MSG_LENGTH);
                }
            }

            // Client was closed; one last fsync
            Os.fsync(mTarget);

        } catch (ErrnoException e) {
            Log.e(TAG, "Failed during bridge: ", e);
        } catch (IOException e) {
            Log.e(TAG, "Failed during bridge: ", e);
        } finally {
            IoUtils.closeQuietly(mTarget);
            IoUtils.closeQuietly(mServer);
            IoUtils.closeQuietly(mClient);
            mClosed = true;
        }
    }

    public static class FileBridgeOutputStream extends OutputStream {
        private final FileDescriptor mClient;
        private final byte[] mTemp = new byte[MSG_LENGTH];

        public FileBridgeOutputStream(FileDescriptor client) {
            mClient = client;
        }

        @Override
        public void close() throws IOException {
            IoBridge.closeAndSignalBlockedThreads(mClient);
        }

        @Override
        public void flush() throws IOException {
            Memory.pokeInt(mTemp, 0, CMD_FSYNC, ByteOrder.BIG_ENDIAN);
            IoBridge.write(mClient, mTemp, 0, MSG_LENGTH);

            // Wait for server to ack
            if (IoBridge.read(mClient, mTemp, 0, MSG_LENGTH) == MSG_LENGTH) {
                if (Memory.peekInt(mTemp, 0, ByteOrder.BIG_ENDIAN) == CMD_FSYNC) {
                    return;
                }
            }

            throw new SyncFailedException("Failed to fsync() across bridge");
        }

        @Override
        public void write(byte[] buffer, int byteOffset, int byteCount) throws IOException {
            Arrays.checkOffsetAndCount(buffer.length, byteOffset, byteCount);
            Memory.pokeInt(mTemp, 0, CMD_WRITE, ByteOrder.BIG_ENDIAN);
            Memory.pokeInt(mTemp, 4, byteCount, ByteOrder.BIG_ENDIAN);
            IoBridge.write(mClient, mTemp, 0, MSG_LENGTH);
            IoBridge.write(mClient, buffer, byteOffset, byteCount);
        }

        @Override
        public void write(int oneByte) throws IOException {
            Streams.writeSingleByte(this, oneByte);
        }
    }
}
