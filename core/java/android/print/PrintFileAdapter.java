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

package android.print;

import android.os.AsyncTask;
import android.os.CancellationSignal;
import android.os.CancellationSignal.OnCancelListener;
import android.util.Log;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapter for printing files.
 */
class PrintFileAdapter extends PrintAdapter {

    private static final String LOG_TAG = "PrintFileAdapter";

    private final File mFile;

    private WriteFileAsyncTask mWriteFileAsyncTask;

    public PrintFileAdapter(File file) {
        if (file == null) {
            throw new IllegalArgumentException("File cannot be null!");
        }
        mFile = file;
    }

    @Override
    public void onPrint(List<PageRange> pages, FileDescriptor destination,
            CancellationSignal cancellationSignal, PrintResultCallback callback) {
        mWriteFileAsyncTask = new WriteFileAsyncTask(mFile, destination, cancellationSignal,
                callback);
        mWriteFileAsyncTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR,
                (Void[]) null);
        
    }

    @Override
    public PrintAdapterInfo getInfo() {
        // TODO: When we have PDF render library we should query the page count.
        return new PrintAdapterInfo.Builder().create();
    }

    private static final class WriteFileAsyncTask extends AsyncTask<Void, Void, Void> {

        private final File mSource;

        private final FileDescriptor mDestination;

        private final PrintResultCallback mResultCallback;

        private final CancellationSignal mCancellationSignal;

        public WriteFileAsyncTask(File source, FileDescriptor destination,
                CancellationSignal cancellationSignal, PrintResultCallback callback) {
            mSource = source;
            mDestination = destination;
            mResultCallback = callback;
            mCancellationSignal = cancellationSignal; 
            mCancellationSignal.setOnCancelListener(new OnCancelListener() {
                @Override
                public void onCancel() {
                    cancel(true);
                }
            });
        }

        @Override
        protected Void doInBackground(Void... params) {
            InputStream in = null;
            OutputStream out = new FileOutputStream(mDestination);
            final byte[] buffer = new byte[8192];
            try {
                in = new FileInputStream(mSource);
                while (true) {
                    final int readByteCount = in.read(buffer);
                    if (readByteCount < 0) {
                        break;
                    }
                    out.write(buffer, 0, readByteCount);
                }
             } catch (IOException ioe) {
                Log.e(LOG_TAG, "Error writing data!", ioe);
             } finally {
                IoUtils.closeQuietly(in);
                IoUtils.closeQuietly(out);
                if (!isCancelled()) {
                    List<PageRange> pages = new ArrayList<PageRange>();
                    pages.add(PageRange.ALL_PAGES);
                    mResultCallback.onPrintFinished(pages);
                } else {
                    mResultCallback.onPrintFailed("Cancelled");
                }
            }
            return null;
        }
    }
}

