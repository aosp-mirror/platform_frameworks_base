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
import android.os.ICancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.print.ILayoutResultCallback;
import android.print.IPrintDocumentAdapter;
import android.print.IWriteResultCallback;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter.LayoutResultCallback;
import android.print.PrintDocumentAdapter.WriteResultCallback;
import android.print.PrintDocumentInfo;
import android.util.Log;
import android.util.Slog;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * This class represents a remote print document adapter instance.
 */
final class RemotePrintDocumentAdapter {
    private static final String LOG_TAG = "RemotePrintDocumentAdapter";

    private static final boolean DEBUG = true;

    public static final int STATE_INITIALIZED = 0;
    public static final int STATE_START_COMPLETED = 1;
    public static final int STATE_LAYOUT_COMPLETED = 2;
    public static final int STATE_WRITE_COMPLETED = 3;
    public static final int STATE_FINISH_COMPLETED = 4;

    private final Object mLock = new Object();

    private final List<QueuedAsyncTask> mTaskQueue = new ArrayList<QueuedAsyncTask>();

    private final IPrintDocumentAdapter mRemoteInterface;

    private final File mFile;

    private int mState = STATE_INITIALIZED;

    public RemotePrintDocumentAdapter(IPrintDocumentAdapter printAdatper, File file) {
        mRemoteInterface = printAdatper;
        mFile = file;
    }

    public File getFile() {
        if (DEBUG) {
            Log.i(LOG_TAG, "getFile()");
        }
        synchronized (mLock) {
            if (mState < STATE_WRITE_COMPLETED) {
                throw new IllegalStateException("Write not completed");
            }
            return mFile;
        }
    }

    public void cancel() {
        synchronized (mLock) {
            final int taskCount = mTaskQueue.size();
            for (int i = 0; i < taskCount; i++) {
                mTaskQueue.remove(i).cancel();
            }
        }
    }

    public void start() {
        QueuedAsyncTask task = new QueuedAsyncTask() {
            @Override
            protected Void doInBackground(Void... params) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "start()");
                }
                synchronized (mLock) {
                    if (mState != STATE_INITIALIZED) {
                        throw new IllegalStateException("Invalid state: " + mState);
                    }
                }
                try {
                    mRemoteInterface.start();
                    synchronized (mLock) {
                        mState = STATE_START_COMPLETED;
                    }
                } catch (RemoteException re) {
                    Log.e(LOG_TAG, "Error reading file", re);
                }
                return null;
            }
        };
        synchronized (mLock) {
            mTaskQueue.add(task);
            task.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, (Void[]) null);
        }
    }

    public void layout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
            LayoutResultCallback callback) {
        LayoutAsyncTask task = new LayoutAsyncTask(oldAttributes, newAttributes, callback);
        synchronized (mLock) {
            mTaskQueue.add(task);
            task.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, (Void[]) null);
        }
    }

    public void write(List<PageRange> pages, WriteResultCallback callback) {
        WriteAsyncTask task = new WriteAsyncTask(pages, callback);
        mTaskQueue.add(task);
        task.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, (Void[]) null);
    }

    public void finish() {
        QueuedAsyncTask task = new QueuedAsyncTask() {
            @Override
            protected Void doInBackground(Void... params) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "finish");
                }
                synchronized (mLock) {
                    if (mState != STATE_LAYOUT_COMPLETED
                            && mState != STATE_WRITE_COMPLETED) {
                        throw new IllegalStateException("Invalid state: " + mState);
                    }
                }
                try {
                    mRemoteInterface.finish();
                    synchronized (mLock) {
                        mState = STATE_FINISH_COMPLETED;
                    }
                } catch (RemoteException re) {
                    Log.e(LOG_TAG, "Error reading file", re);
                    mState = STATE_INITIALIZED;
                }
                return null;
            }
        };
        synchronized (mLock) {
            mTaskQueue.add(task);
            task.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, (Void[]) null);
        }
    }

    private abstract class QueuedAsyncTask extends AsyncTask<Void, Void, Void> {
        public void cancel() {
            super.cancel(true);
        }
    }

    private final class LayoutAsyncTask extends QueuedAsyncTask {

        private final PrintAttributes mOldAttributes;

        private final PrintAttributes mNewAttributes;

        private final LayoutResultCallback mCallback;

        private final ILayoutResultCallback mILayoutResultCallback =
                new ILayoutResultCallback.Stub() {
            @Override
            public void onLayoutStarted(ICancellationSignal cancellationSignal) {
                synchronized (mLock) {
                    mCancellationSignal = cancellationSignal;
                    if (isCancelled()) {
                        cancelSignalQuietlyLocked();
                    }
                }
            }

            @Override
            public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
                synchronized (mLock) {
                    mCancellationSignal = null;
                    mCompleted = true;
                    mLock.notifyAll();
                }
                mCallback.onLayoutFinished(info, changed);
            }

            @Override
            public void onLayoutFailed(CharSequence error) {
                synchronized (mLock) {
                    mCancellationSignal = null;
                    mCompleted = true;
                    mLock.notifyAll();
                }
                Slog.e(LOG_TAG, "Error laying out print document: " + error);
                mCallback.onLayoutFailed(error);
            }
        };

        private ICancellationSignal mCancellationSignal;

        private boolean mCompleted;

        public LayoutAsyncTask(PrintAttributes oldAttributes,
                PrintAttributes newAttributes, LayoutResultCallback callback) {
            mOldAttributes = oldAttributes;
            mNewAttributes = newAttributes;
            mCallback = callback;
        }

        @Override
        public void cancel() {
            synchronized (mLock) {
                throwIfCancelledLocked();
                cancelSignalQuietlyLocked();
            }
            super.cancel();
        }

        @Override
        protected Void doInBackground(Void... params) {
            synchronized (mLock) {
                if (mState != STATE_START_COMPLETED
                        && mState != STATE_LAYOUT_COMPLETED
                        && mState != STATE_WRITE_COMPLETED) {
                    throw new IllegalStateException("Invalid state: " + mState);
                }
            }
            try {
                mRemoteInterface.layout(mOldAttributes, mNewAttributes,
                        mILayoutResultCallback);
                synchronized (mLock) {
                    while (true) {
                        if (isCancelled()) {
                            mState = STATE_INITIALIZED;
                            mTaskQueue.remove(this);
                            break;
                        }
                        if (mCompleted) {
                            mState = STATE_LAYOUT_COMPLETED;
                            mTaskQueue.remove(this);
                            break;
                        }
                        try {
                            mLock.wait();
                        } catch (InterruptedException ie) {
                            /* ignore */
                        }
                    }
                }
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error calling layout", re);
                mState = STATE_INITIALIZED;
            }
            return null;
        }

        private void cancelSignalQuietlyLocked() {
            if (mCancellationSignal != null) {
                try {
                    mCancellationSignal.cancel();
                } catch (RemoteException re) {
                    Slog.e(LOG_TAG, "Error cancelling layout", re);
                }
            }
        }

        private void throwIfCancelledLocked() {
            if (isCancelled()) {
                throw new IllegalStateException("Already cancelled");
            }
        }
    }

    private final class WriteAsyncTask extends QueuedAsyncTask {

        private final List<PageRange> mPages;

        private final WriteResultCallback mCallback;

        private final IWriteResultCallback mIWriteResultCallback =
                new IWriteResultCallback.Stub() {
            @Override
            public void onWriteStarted(ICancellationSignal cancellationSignal) {
                synchronized (mLock) {
                    mCancellationSignal = cancellationSignal;
                    if (isCancelled()) {
                        cancelSignalQuietlyLocked();
                    }
                }
            }

            @Override
            public void onWriteFinished(List<PageRange> pages) {
                synchronized (mLock) {
                    mCancellationSignal = null;
                    mCompleted = true;
                    mLock.notifyAll();
                }
                mCallback.onWriteFinished(pages);
            }

            @Override
            public void onWriteFailed(CharSequence error) {
                synchronized (mLock) {
                    mCancellationSignal = null;
                    mCompleted = true;
                    mLock.notifyAll();
                }
                Slog.e(LOG_TAG, "Error writing print document: " + error);
                mCallback.onWriteFailed(error);
            }
        };

        private ICancellationSignal mCancellationSignal;

        private boolean mCompleted;

        private Thread mWriteThread;

        public WriteAsyncTask(List<PageRange> pages, WriteResultCallback callback) {
            mPages = pages;
            mCallback = callback;
        }

        @Override
        public void cancel() {
            synchronized (mLock) {
                throwIfCancelledLocked();
                cancelSignalQuietlyLocked();
                mWriteThread.interrupt();
            }
            super.cancel();
        }

        @Override
        protected Void doInBackground(Void... params) {
            if (DEBUG) {
                Log.i(LOG_TAG, "print()");
            }
            synchronized (mLock) {
                if (mState != STATE_LAYOUT_COMPLETED) {
                    throw new IllegalStateException("Invalid state: " + mState);
                }
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
                mRemoteInterface.write(mPages, sink, mIWriteResultCallback);

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
                synchronized (mLock) {
                    while (true) {
                        if (isCancelled()) {
                            mState = STATE_INITIALIZED;
                            mTaskQueue.remove(this);
                            break;
                        }
                        if (mCompleted) {
                            mState = STATE_WRITE_COMPLETED;
                            mTaskQueue.remove(this);
                            break;
                        }
                        try {
                            mLock.wait();
                        } catch (InterruptedException ie) {
                            /* ignore */
                        }
                    }
                }
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error writing print document", re);
                mState = STATE_INITIALIZED;
            } catch (IOException ioe) {
                Slog.e(LOG_TAG, "Error writing print document", ioe);
                mState = STATE_INITIALIZED;
            } finally {
                IoUtils.closeQuietly(in);
                IoUtils.closeQuietly(out);
                IoUtils.closeQuietly(sink);
                IoUtils.closeQuietly(source);
            }
            return null;
        }

        private void cancelSignalQuietlyLocked() {
            if (mCancellationSignal != null) {
                try {
                    mCancellationSignal.cancel();
                } catch (RemoteException re) {
                    Slog.e(LOG_TAG, "Error cancelling layout", re);
                }
            }
        }

        private void throwIfCancelledLocked() {
            if (isCancelled()) {
                throw new IllegalStateException("Already cancelled");
            }
        }
    }
}
