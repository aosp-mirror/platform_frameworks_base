/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.printspooler;

import android.os.ICancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.print.IPrintAdapter;
import android.print.IPrintResultCallback;
import android.print.PageRange;
import android.print.PrintAdapterInfo;
import android.print.PrintAttributes;
import android.util.Log;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * This class represents a remote print adapter instance.
 */
final class RemotePrintAdapter {
    private static final String LOG_TAG = "RemotePrintAdapter";

    private static final boolean DEBUG = true;

    private final Object mLock = new Object();

    private final IPrintAdapter mRemoteInterface;

    private final File mFile;

    private final IPrintResultCallback mIPrintProgressListener;

    private PrintAdapterInfo mInfo;

    private ICancellationSignal mCancellationSignal;

    private Thread mWriteThread;

    public RemotePrintAdapter(IPrintAdapter printAdatper, File file) {
        mRemoteInterface = printAdatper;
        mFile = file;
        mIPrintProgressListener = new IPrintResultCallback.Stub() {
            @Override
            public void onPrintStarted(PrintAdapterInfo info,
                    ICancellationSignal cancellationSignal) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "IPrintProgressListener#onPrintStarted()");
                }
                synchronized (mLock) {
                    mInfo = info;
                    mCancellationSignal = cancellationSignal;
                }
            }

            @Override
            public void onPrintFinished(List<PageRange> pages) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "IPrintProgressListener#onPrintFinished(" + pages + ")");
                }
                synchronized (mLock) {
                    if (isPrintingLocked()) {
                        mWriteThread.interrupt();
                        mCancellationSignal = null;
                    }
                }
            }

            @Override
            public void onPrintFailed(CharSequence error) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "IPrintProgressListener#onPrintFailed(" + error + ")");
                }
                synchronized (mLock) {
                    if (isPrintingLocked()) {
                        mWriteThread.interrupt();
                        mCancellationSignal = null;
                    }
                }
            }
        };
    }

    public File getFile() {
        if (DEBUG) {
            Log.i(LOG_TAG, "getFile()");
        }
        return mFile;
    }

    public void start() throws IOException {
        if (DEBUG) {
            Log.i(LOG_TAG, "start()");
        }
        try {
            mRemoteInterface.start();
        } catch (RemoteException re) {
            throw new IOException("Error reading file", re);
        }
    }

    public void printAttributesChanged(PrintAttributes attributes) throws IOException {
        if (DEBUG) {
            Log.i(LOG_TAG, "printAttributesChanged(" + attributes +")");
        }
        try {
            mRemoteInterface.printAttributesChanged(attributes);
        } catch (RemoteException re) {
            throw new IOException("Error reading file", re);
        }
    }

    public void print(List<PageRange> pages) throws IOException {
        if (DEBUG) {
            Log.i(LOG_TAG, "print(" + pages +")");
        }
        InputStream in = null;
        OutputStream out = null;
        ParcelFileDescriptor source = null;
        ParcelFileDescriptor sink = null;
        synchronized (mLock) {
            mWriteThread = Thread.currentThread();
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            source = pipe[0];
            sink = pipe[1];

            in = new FileInputStream(source.getFileDescriptor());
            out = new FileOutputStream(mFile);

            // Async call to initiate the other process writing the data.
            mRemoteInterface.print(pages, sink, mIPrintProgressListener);

            // Close the source. It is now held by the client.
            sink.close();
            sink = null;

            final byte[] buffer = new byte[8192];
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                    break;
                }
                final int readByteCount = in.read(buffer);
                if (readByteCount < 0) {
                    break;
                }
                out.write(buffer, 0, readByteCount);
            }
        } catch (RemoteException re) {
            throw new IOException("Error reading file", re);
        } catch (IOException ioe) {
            throw new IOException("Error reading file", ioe);
        } finally {
            IoUtils.closeQuietly(in);
            IoUtils.closeQuietly(out);
            IoUtils.closeQuietly(sink);
            IoUtils.closeQuietly(source);
        }
    }

    public void cancelPrint() throws IOException {
        if (DEBUG) {
            Log.i(LOG_TAG, "cancelPrint()");
        }
        synchronized (mLock) {
            if (isPrintingLocked()) {
                try {
                    mCancellationSignal.cancel();
                } catch (RemoteException re) {
                    throw new IOException("Error cancelling print", re);
                }
            }
        }
    }

    public void finish() throws IOException {
        if (DEBUG) {
            Log.i(LOG_TAG, "finish()");
        }
        try {
            mRemoteInterface.finish();
        } catch (RemoteException re) {
            throw new IOException("Error reading file", re);
        }
    }

    public PrintAdapterInfo getInfo() {
        synchronized (mLock) {
            return mInfo;
        }
    }

    private boolean isPrintingLocked() {
        return mCancellationSignal != null;
    }
}
