/*
 * Copyright (C) 2011 The Android Open Source Project
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
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Slog;

import libcore.io.IoUtils;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Helper for transferring data through a pipe from a client app.
 */
public class TransferPipe implements Runnable, Closeable {
    static final String TAG = "TransferPipe";
    static final boolean DEBUG = false;

    static final long DEFAULT_TIMEOUT = 5000;  // 5 seconds

    final Thread mThread;
    final ParcelFileDescriptor[] mFds;

    FileDescriptor mOutFd;
    long mEndTime;
    String mFailure;
    boolean mComplete;

    String mBufferPrefix;

    interface Caller {
        void go(IInterface iface, FileDescriptor fd, String prefix,
                String[] args) throws RemoteException;
    }

    public TransferPipe() throws IOException {
        this(null);
    }

    public TransferPipe(String bufferPrefix) throws IOException {
        this(bufferPrefix, "TransferPipe");
    }

    protected TransferPipe(String bufferPrefix, String threadName) throws IOException {
        mThread = new Thread(this, threadName);
        mFds = ParcelFileDescriptor.createPipe();
        mBufferPrefix = bufferPrefix;
    }

    ParcelFileDescriptor getReadFd() {
        return mFds[0];
    }

    public ParcelFileDescriptor getWriteFd() {
        return mFds[1];
    }

    public void setBufferPrefix(String prefix) {
        mBufferPrefix = prefix;
    }

    public static void dumpAsync(IBinder binder, FileDescriptor out, String[] args)
            throws IOException, RemoteException {
        goDump(binder, out, args);
    }

    /**
     * Read raw bytes from a service's dump function.
     *
     * <p>This can be used for dumping {@link android.util.proto.ProtoOutputStream protos}.
     *
     * @param binder The service providing the data
     * @param args The arguments passed to the dump function of the service
     */
    public static byte[] dumpAsync(@NonNull IBinder binder, @Nullable String... args)
            throws IOException, RemoteException {
        ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
        try {
            TransferPipe.dumpAsync(binder, pipe[1].getFileDescriptor(), args);

            // Data is written completely when dumpAsync is done
            pipe[1].close();
            pipe[1] = null;

            byte[] buffer = new byte[4096];
            try (ByteArrayOutputStream combinedBuffer = new ByteArrayOutputStream()) {
                try (FileInputStream is = new FileInputStream(pipe[0].getFileDescriptor())) {
                    while (true) {
                        int numRead = is.read(buffer);
                        if (numRead == -1) {
                            break;
                        }

                        combinedBuffer.write(buffer, 0, numRead);
                    }
                }

                return combinedBuffer.toByteArray();
            }
        } finally {
            pipe[0].close();
            IoUtils.closeQuietly(pipe[1]);
        }
    }

    static void go(Caller caller, IInterface iface, FileDescriptor out,
            String prefix, String[] args) throws IOException, RemoteException {
        go(caller, iface, out, prefix, args, DEFAULT_TIMEOUT);
    }

    static void go(Caller caller, IInterface iface, FileDescriptor out,
            String prefix, String[] args, long timeout) throws IOException, RemoteException {
        if ((iface.asBinder()) instanceof Binder) {
            // This is a local object...  just call it directly.
            try {
                caller.go(iface, out, prefix, args);
            } catch (RemoteException e) {
            }
            return;
        }

        try (TransferPipe tp = new TransferPipe()) {
            caller.go(iface, tp.getWriteFd().getFileDescriptor(), prefix, args);
            tp.go(out, timeout);
        }
    }

    static void goDump(IBinder binder, FileDescriptor out,
            String[] args) throws IOException, RemoteException {
        goDump(binder, out, args, DEFAULT_TIMEOUT);
    }

    static void goDump(IBinder binder, FileDescriptor out,
            String[] args, long timeout) throws IOException, RemoteException {
        if (binder instanceof Binder) {
            // This is a local object...  just call it directly.
            try {
                binder.dump(out, args);
            } catch (RemoteException e) {
            }
            return;
        }

        try (TransferPipe tp = new TransferPipe()) {
            binder.dumpAsync(tp.getWriteFd().getFileDescriptor(), args);
            tp.go(out, timeout);
        }
    }

    public void go(FileDescriptor out) throws IOException {
        go(out, DEFAULT_TIMEOUT);
    }

    public void go(FileDescriptor out, long timeout) throws IOException {
        try {
            synchronized (this) {
                mOutFd = out;
                mEndTime = SystemClock.uptimeMillis() + timeout;

                if (DEBUG) Slog.i(TAG, "read=" + getReadFd() + " write=" + getWriteFd()
                        + " out=" + out);

                // Close the write fd, so we know when the other side is done.
                closeFd(1);

                mThread.start();

                while (mFailure == null && !mComplete) {
                    long waitTime = mEndTime - SystemClock.uptimeMillis();
                    if (waitTime <= 0) {
                        if (DEBUG) Slog.i(TAG, "TIMEOUT!");
                        mThread.interrupt();
                        throw new IOException("Timeout");
                    }

                    try {
                        wait(waitTime);
                    } catch (InterruptedException e) {
                    }
                }

                if (DEBUG) Slog.i(TAG, "Finished: " + mFailure);
                if (mFailure != null) {
                    throw new IOException(mFailure);
                }
            }
        } finally {
            kill();
        }
    }

    void closeFd(int num) {
        if (mFds[num] != null) {
            if (DEBUG) Slog.i(TAG, "Closing: " + mFds[num]);
            try {
                mFds[num].close();
            } catch (IOException e) {
            }
            mFds[num] = null;
        }
    }

    @Override
    public void close() {
        kill();
    }

    public void kill() {
        synchronized (this) {
            closeFd(0);
            closeFd(1);
        }
    }

    protected OutputStream getNewOutputStream() {
          return new FileOutputStream(mOutFd);
    }

    @Override
    public void run() {
        final byte[] buffer = new byte[1024];
        final FileInputStream fis;
        final OutputStream fos;

        synchronized (this) {
            ParcelFileDescriptor readFd = getReadFd();
            if (readFd == null) {
                Slog.w(TAG, "Pipe has been closed...");
                return;
            }
            fis = new FileInputStream(readFd.getFileDescriptor());
            fos = getNewOutputStream();
        }

        if (DEBUG) Slog.i(TAG, "Ready to read pipe...");
        byte[] bufferPrefix = null;
        boolean needPrefix = true;
        if (mBufferPrefix != null) {
            bufferPrefix = mBufferPrefix.getBytes();
        }

        int size;
        try {
            while ((size=fis.read(buffer)) > 0) {
                if (DEBUG) Slog.i(TAG, "Got " + size + " bytes");
                if (bufferPrefix == null) {
                    fos.write(buffer, 0, size);
                } else {
                    int start = 0;
                    for (int i=0; i<size; i++) {
                        if (buffer[i] != '\n') {
                            if (i > start) {
                                fos.write(buffer, start, i-start);
                            }
                            start = i;
                            if (needPrefix) {
                                fos.write(bufferPrefix);
                                needPrefix = false;
                            }
                            do {
                                i++;
                            } while (i<size && buffer[i] != '\n');
                            if (i < size) {
                                needPrefix = true;
                            }
                        }
                    }
                    if (size > start) {
                        fos.write(buffer, start, size-start);
                    }
                }
            }
            if (DEBUG) Slog.i(TAG, "End of pipe: size=" + size);
            if (mThread.isInterrupted()) {
                if (DEBUG) Slog.i(TAG, "Interrupted!");
            }
        } catch (IOException e) {
            synchronized (this) {
                mFailure = e.toString();
                notifyAll();
                return;
            }
        }

        synchronized (this) {
            mComplete = true;
            notifyAll();
        }
    }
}
