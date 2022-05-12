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

package com.android.server.clipboard;

import android.annotation.Nullable;
import android.content.ClipData;
import android.os.PersistableBundle;
import android.os.SystemProperties;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.VmSocketAddress;
import android.util.Slog;

import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.function.Consumer;

// The following class is Android Emulator specific. It is used to read and
// write contents of the host system's clipboard.
class EmulatorClipboardMonitor implements Consumer<ClipData> {
    private static final String TAG = "EmulatorClipboardMonitor";
    private static final String PIPE_NAME = "pipe:clipboard";
    private static final int HOST_PORT = 5000;
    private final Thread mHostMonitorThread;
    private static final boolean LOG_CLIBOARD_ACCESS =
            SystemProperties.getBoolean("ro.boot.qemu.log_clipboard_access", false);
    private FileDescriptor mPipe = null;

    private static byte[] createOpenHandshake() {
        // String.getBytes doesn't include the null terminator,
        // but the QEMU pipe device requires the pipe service name
        // to be null-terminated.

        final byte[] bits = Arrays.copyOf(PIPE_NAME.getBytes(), PIPE_NAME.length() + 1);
        bits[PIPE_NAME.length()] = 0;
        return bits;
    }

    private synchronized FileDescriptor getPipeFD() {
        return mPipe;
    }

    private synchronized void setPipeFD(final FileDescriptor fd) {
        mPipe = fd;
    }

    private static FileDescriptor openPipeImpl() {
        try {
            final FileDescriptor fd = Os.socket(OsConstants.AF_VSOCK, OsConstants.SOCK_STREAM, 0);

            try {
                Os.connect(fd, new VmSocketAddress(HOST_PORT, OsConstants.VMADDR_CID_HOST));

                final byte[] handshake = createOpenHandshake();
                writeFully(fd, handshake, 0, handshake.length);
                return fd;
            } catch (ErrnoException | SocketException | InterruptedIOException e) {
                Os.close(fd);
            }
        } catch (ErrnoException e) {
        }

        return null;
    }

    private static FileDescriptor openPipe() throws InterruptedException {
        FileDescriptor fd = openPipeImpl();

        // There's no guarantee that QEMU pipes will be ready at the moment
        // this method is invoked. We simply try to get the pipe open and
        // retry on failure indefinitely.
        while (fd == null) {
            Thread.sleep(100);
            fd = openPipeImpl();
        }

        return fd;
    }

    private static byte[] receiveMessage(final FileDescriptor fd) throws ErrnoException,
            InterruptedIOException, EOFException {
        final byte[] lengthBits = new byte[4];
        readFully(fd, lengthBits, 0, lengthBits.length);

        final ByteBuffer bb = ByteBuffer.wrap(lengthBits);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        final int msgLen = bb.getInt();

        final byte[] msg = new byte[msgLen];
        readFully(fd, msg, 0, msg.length);

        return msg;
    }

    private static void sendMessage(
            final FileDescriptor fd,
            final byte[] msg) throws ErrnoException, InterruptedIOException {
        final byte[] lengthBits = new byte[4];
        final ByteBuffer bb = ByteBuffer.wrap(lengthBits);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(msg.length);

        writeFully(fd, lengthBits, 0, lengthBits.length);
        writeFully(fd, msg, 0, msg.length);
    }

    EmulatorClipboardMonitor(final Consumer<ClipData> setAndroidClipboard) {
        this.mHostMonitorThread = new Thread(() -> {
            FileDescriptor fd = null;

            while (!Thread.interrupted()) {
                try {
                    if (fd == null) {
                        fd = openPipe();
                        setPipeFD(fd);
                    }

                    final byte[] receivedData = receiveMessage(fd);

                    final String str = new String(receivedData);
                    final ClipData clip = new ClipData("host clipboard",
                                                       new String[]{"text/plain"},
                                                       new ClipData.Item(str));
                    final PersistableBundle bundle = new PersistableBundle();
                    bundle.putBoolean("com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY", true);
                    clip.getDescription().setExtras(bundle);

                    if (LOG_CLIBOARD_ACCESS) {
                        Slog.i(TAG, "Setting the guest clipboard to '" + str + "'");
                    }
                    setAndroidClipboard.accept(clip);
                } catch (ErrnoException | EOFException | InterruptedIOException
                         | InterruptedException e) {
                    setPipeFD(null);

                    try {
                        Os.close(fd);
                    } catch (ErrnoException e2) {
                        // ignore
                    }

                    fd = null;
                }
            }
        });

        this.mHostMonitorThread.start();
    }

    @Override
    public void accept(final @Nullable ClipData clip) {
        final FileDescriptor fd = getPipeFD();
        if (fd != null) {
            setHostClipboard(fd, getClipString(clip));
        }
    }

    private String getClipString(final @Nullable ClipData clip) {
        if (clip == null) {
            return "";
        }

        if (clip.getItemCount() == 0) {
            return "";
        }

        final CharSequence text = clip.getItemAt(0).getText();
        if (text == null) {
            return "";
        }

        return text.toString();
    }

    private static void setHostClipboard(final FileDescriptor fd, final String value) {
        Thread t = new Thread(() -> {
            if (LOG_CLIBOARD_ACCESS) {
                Slog.i(TAG, "Setting the host clipboard to '" + value + "'");
            }

            try {
                sendMessage(fd, value.getBytes());
            } catch (ErrnoException | InterruptedIOException e) {
                Slog.e(TAG, "Failed to set host clipboard " + e.getMessage());
            } catch (IllegalArgumentException e) {
            }
        });
        t.start();
    }

    private static void readFully(final FileDescriptor fd,
                                  final byte[] buf, int offset, int size)
                                  throws ErrnoException, InterruptedIOException, EOFException {
        while (size > 0) {
            final int r = Os.read(fd, buf, offset, size);
            if (r > 0) {
                offset += r;
                size -= r;
            } else {
                throw new EOFException();
            }
        }
    }

    private static void writeFully(final FileDescriptor fd,
                                   final byte[] buf, int offset, int size)
                                   throws ErrnoException, InterruptedIOException {
        while (size > 0) {
            final int r = Os.write(fd, buf, offset, size);
            if (r > 0) {
                offset += r;
                size -= r;
            } else {
                throw new ErrnoException("write", OsConstants.EIO);
            }
        }
    }
}
