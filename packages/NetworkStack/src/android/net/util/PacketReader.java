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

package android.net.util;

import static java.lang.Math.max;

import android.net.shared.FdEventsReader;
import android.os.Handler;
import android.system.Os;

import java.io.FileDescriptor;

/**
 * Specialization of {@link FdEventsReader} that reads packets into a byte array.
 *
 * TODO: rename this class to something more correctly descriptive (something
 * like [or less horrible than] FdReadEventsHandler?).
 *
 * @hide
 */
public abstract class PacketReader extends FdEventsReader<byte[]> {

    public static final int DEFAULT_RECV_BUF_SIZE = 2 * 1024;

    protected PacketReader(Handler h) {
        this(h, DEFAULT_RECV_BUF_SIZE);
    }

    protected PacketReader(Handler h, int recvBufSize) {
        super(h, new byte[max(recvBufSize, DEFAULT_RECV_BUF_SIZE)]);
    }

    @Override
    protected final int recvBufSize(byte[] buffer) {
        return buffer.length;
    }

    /**
     * Subclasses MAY override this to change the default read() implementation
     * in favour of, say, recvfrom().
     *
     * Implementations MUST return the bytes read or throw an Exception.
     */
    @Override
    protected int readPacket(FileDescriptor fd, byte[] packetBuffer) throws Exception {
        return Os.read(fd, packetBuffer, 0, packetBuffer.length);
    }
}
