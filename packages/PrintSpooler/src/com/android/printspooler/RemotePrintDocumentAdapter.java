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

import android.os.AsyncTask;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.print.ILayoutResultCallback;
import android.print.IPrintDocumentAdapter;
import android.print.IWriteResultCallback;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.util.Log;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * This class represents a remote print document adapter instance.
 */
final class RemotePrintDocumentAdapter {
    private static final String LOG_TAG = "RemotePrintDocumentAdapter";

    private static final boolean DEBUG = false;

    private final IPrintDocumentAdapter mRemoteInterface;

    private final File mFile;

    public RemotePrintDocumentAdapter(IPrintDocumentAdapter printAdatper, File file) {
        mRemoteInterface = printAdatper;
        mFile = file;
    }

    public void start()  {
        if (DEBUG) {
            Log.i(LOG_TAG, "start()");
        }
        try {
            mRemoteInterface.start();
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error calling start()", re);
        }
    }

    public void layout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
            ILayoutResultCallback callback, Bundle metadata, int sequence) {
        if (DEBUG) {
            Log.i(LOG_TAG, "layout()");
        }
        try {
            mRemoteInterface.layout(oldAttributes, newAttributes, callback, metadata, sequence);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error calling layout()", re);
        }
    }

    public void write(final PageRange[] pages, final IWriteResultCallback callback,
            final int sequence) {
        if (DEBUG) {
            Log.i(LOG_TAG, "write()");
        }
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                InputStream in = null;
                OutputStream out = null;
                ParcelFileDescriptor source = null;
                ParcelFileDescriptor sink = null;
                try {
                    ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                    source = pipe[0];
                    sink = pipe[1];

                    in = new FileInputStream(source.getFileDescriptor());
                    out = new FileOutputStream(mFile);

                    // Async call to initiate the other process writing the data.
                    mRemoteInterface.write(pages, sink, callback, sequence);

                    // Close the source. It is now held by the client.
                    sink.close();
                    sink = null;

                    // Read the data.
                    final byte[] buffer = new byte[8192];
                    while (true) {
                        final int readByteCount = in.read(buffer);
                        if (readByteCount < 0) {
                            break;
                        }
                        out.write(buffer, 0, readByteCount);
                    }
                } catch (RemoteException re) {
                    Log.e(LOG_TAG, "Error calling write()", re);
                } catch (IOException ioe) {
                    Log.e(LOG_TAG, "Error calling write()", ioe);
                } finally {
                    IoUtils.closeQuietly(in);
                    IoUtils.closeQuietly(out);
                    IoUtils.closeQuietly(sink);
                    IoUtils.closeQuietly(source);
                }
                return null;
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void[]) null);
    }

    public void finish() {
        if (DEBUG) {
            Log.i(LOG_TAG, "finish()");
        }
        try {
            mRemoteInterface.finish();
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error calling finish()", re);
        }
    }

    public void cancel() {
        if (DEBUG) {
            Log.i(LOG_TAG, "cancel()");
        }
        try {
            mRemoteInterface.cancel();
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error calling cancel()", re);
        }
    }
}
