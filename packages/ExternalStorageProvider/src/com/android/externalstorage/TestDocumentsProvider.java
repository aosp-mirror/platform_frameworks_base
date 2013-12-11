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

package com.android.externalstorage;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.CancellationSignal.OnCancelListener;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.util.Log;

import libcore.io.IoUtils;
import libcore.io.Streams;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;

public class TestDocumentsProvider extends DocumentsProvider {
    private static final String TAG = "TestDocuments";

    private static final boolean LAG = false;

    private static final boolean ROOT_LAME_PROJECTION = false;
    private static final boolean DOCUMENT_LAME_PROJECTION = false;

    private static final boolean ROOTS_WEDGE = false;
    private static final boolean ROOTS_CRASH = false;
    private static final boolean ROOTS_REFRESH = false;

    private static final boolean DOCUMENT_CRASH = false;

    private static final boolean RECENT_WEDGE = false;

    private static final boolean CHILD_WEDGE = false;
    private static final boolean CHILD_CRASH = false;

    private static final boolean THUMB_HUNDREDS = false;
    private static final boolean THUMB_WEDGE = false;
    private static final boolean THUMB_CRASH = false;

    private static final String MY_ROOT_ID = "myRoot";
    private static final String MY_DOC_ID = "myDoc";
    private static final String MY_DOC_NULL = "myNull";

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
            Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES,
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
    };

    private static String[] resolveRootProjection(String[] projection) {
        if (ROOT_LAME_PROJECTION) return new String[0];
        return projection != null ? projection : DEFAULT_ROOT_PROJECTION;
    }

    private static String[] resolveDocumentProjection(String[] projection) {
        if (DOCUMENT_LAME_PROJECTION) return new String[0];
        return projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION;
    }

    private String mAuthority;

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        mAuthority = info.authority;
        super.attachInfo(context, info);
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        Log.d(TAG, "Someone asked for our roots!");

        if (LAG) lagUntilCanceled(null);
        if (ROOTS_WEDGE) wedgeUntilCanceled(null);
        if (ROOTS_CRASH) System.exit(12);

        if (ROOTS_REFRESH) {
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                    SystemClock.sleep(3000);
                    Log.d(TAG, "Notifying that something changed!!");
                    final Uri uri = DocumentsContract.buildRootsUri(mAuthority);
                    getContext().getContentResolver().notifyChange(uri, null, false);
                    return null;
                }
            }.execute();
        }

        final MatrixCursor result = new MatrixCursor(resolveRootProjection(projection));
        final RowBuilder row = result.newRow();
        row.add(Root.COLUMN_ROOT_ID, MY_ROOT_ID);
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_RECENTS | Root.FLAG_SUPPORTS_CREATE);
        row.add(Root.COLUMN_TITLE, "_Test title which is really long");
        row.add(Root.COLUMN_SUMMARY,
                SystemClock.elapsedRealtime() + " summary which is also super long text");
        row.add(Root.COLUMN_DOCUMENT_ID, MY_DOC_ID);
        row.add(Root.COLUMN_AVAILABLE_BYTES, 1024);
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        if (LAG) lagUntilCanceled(null);
        if (DOCUMENT_CRASH) System.exit(12);

        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        includeFile(result, documentId, 0);
        return result;
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        if (LAG) lagUntilCanceled(null);

        return super.createDocument(parentDocumentId, mimeType, displayName);
    }

    /**
     * Holds any outstanding or finished "network" fetching.
     */
    private WeakReference<CloudTask> mTask;

    private static class CloudTask implements Runnable {

        private final ContentResolver mResolver;
        private final Uri mNotifyUri;

        private volatile boolean mFinished;

        public CloudTask(ContentResolver resolver, Uri notifyUri) {
            mResolver = resolver;
            mNotifyUri = notifyUri;
        }

        @Override
        public void run() {
            // Pretend to do some network
            Log.d(TAG, hashCode() + ": pretending to do some network!");
            SystemClock.sleep(2000);
            Log.d(TAG, hashCode() + ": network done!");

            mFinished = true;

            // Tell anyone remotely they should requery
            mResolver.notifyChange(mNotifyUri, null, false);
        }

        public boolean includeIfFinished(MatrixCursor result) {
            Log.d(TAG, hashCode() + ": includeIfFinished() found " + mFinished);
            if (mFinished) {
                includeFile(result, "_networkfile1", 0);
                includeFile(result, "_networkfile2", 0);
                includeFile(result, "_networkfile3", 0);
                includeFile(result, "_networkfile4", 0);
                includeFile(result, "_networkfile5", 0);
                includeFile(result, "_networkfile6", 0);
                return true;
            } else {
                return false;
            }
        }
    }

    private static class CloudCursor extends MatrixCursor {
        public Object keepAlive;
        public final Bundle extras = new Bundle();

        public CloudCursor(String[] columnNames) {
            super(columnNames);
        }

        @Override
        public Bundle getExtras() {
            return extras;
        }
    }

    @Override
    public Cursor queryChildDocuments(
            String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {

        if (LAG) lagUntilCanceled(null);
        if (CHILD_WEDGE) SystemClock.sleep(Integer.MAX_VALUE);
        if (CHILD_CRASH) System.exit(12);

        final ContentResolver resolver = getContext().getContentResolver();
        final Uri notifyUri = DocumentsContract.buildDocumentUri(
                "com.example.documents", parentDocumentId);

        CloudCursor result = new CloudCursor(resolveDocumentProjection(projection));
        result.setNotificationUri(resolver, notifyUri);

        // Always include local results
        includeFile(result, MY_DOC_NULL, 0);
        includeFile(result, "localfile1", 0);
        includeFile(result, "localfile2", Document.FLAG_SUPPORTS_THUMBNAIL);
        includeFile(result, "localfile3", 0);
        includeFile(result, "localfile4", 0);

        if (THUMB_HUNDREDS) {
            for (int i = 0; i < 256; i++) {
                includeFile(result, "i maded u an picshure" + i, Document.FLAG_SUPPORTS_THUMBNAIL);
            }
        }

        synchronized (this) {
            // Try picking up an existing network fetch
            CloudTask task = mTask != null ? mTask.get() : null;
            if (task == null) {
                Log.d(TAG, "No network task found; starting!");
                task = new CloudTask(resolver, notifyUri);
                mTask = new WeakReference<CloudTask>(task);
                new Thread(task).start();

                // Aggressively try freeing weak reference above
                new Thread() {
                    @Override
                    public void run() {
                        while (mTask.get() != null) {
                            SystemClock.sleep(200);
                            System.gc();
                            System.runFinalization();
                        }
                        Log.d(TAG, "AHA! THE CLOUD TASK WAS GC'ED!");
                    }
                }.start();
            }

            // Blend in cloud results if ready
            if (task.includeIfFinished(result)) {
                result.extras.putString(DocumentsContract.EXTRA_INFO,
                        "Everything Went Better Than Expected and this message is quite "
                                + "long and verbose and maybe even too long");
                result.extras.putString(DocumentsContract.EXTRA_ERROR,
                        "But then again, maybe our server ran into an error, which means "
                                + "we're going to have a bad time");
            } else {
                result.extras.putBoolean(DocumentsContract.EXTRA_LOADING, true);
            }

            // Tie the network fetch to the cursor GC lifetime
            result.keepAlive = task;

            return result;
        }
    }

    @Override
    public Cursor queryRecentDocuments(String rootId, String[] projection)
            throws FileNotFoundException {

        if (LAG) lagUntilCanceled(null);
        if (RECENT_WEDGE) wedgeUntilCanceled(null);

        // Pretend to take a super long time to respond
        SystemClock.sleep(3000);

        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        includeFile(
                result, "It was /worth/ the_wait for?the file:with the&incredibly long name", 0);
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String docId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        if (LAG) lagUntilCanceled(null);
        throw new FileNotFoundException();
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(
            String docId, Point sizeHint, CancellationSignal signal) throws FileNotFoundException {

        if (LAG) lagUntilCanceled(signal);
        if (THUMB_WEDGE) wedgeUntilCanceled(signal);
        if (THUMB_CRASH) System.exit(12);

        final Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final Paint paint = new Paint();
        paint.setColor(Color.BLUE);
        canvas.drawColor(Color.RED);
        canvas.drawLine(0, 0, 32, 32, paint);

        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bitmap.compress(CompressFormat.JPEG, 50, bos);

        final ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
        try {
            final ParcelFileDescriptor[] fds = ParcelFileDescriptor.createReliablePipe();
            new AsyncTask<Object, Object, Object>() {
                @Override
                protected Object doInBackground(Object... params) {
                    final FileOutputStream fos = new FileOutputStream(fds[1].getFileDescriptor());
                    try {
                        Streams.copy(bis, fos);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    IoUtils.closeQuietly(fds[1]);
                    return null;
                }
            }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            return new AssetFileDescriptor(fds[0], 0, AssetFileDescriptor.UNKNOWN_LENGTH);
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private static void lagUntilCanceled(CancellationSignal signal) {
        waitForCancelOrTimeout(signal, 1500);
    }

    private static void wedgeUntilCanceled(CancellationSignal signal) {
        waitForCancelOrTimeout(signal, Integer.MAX_VALUE);
    }

    private static void waitForCancelOrTimeout(
            final CancellationSignal signal, long timeoutMillis) {
        if (signal != null) {
            final Thread blocked = Thread.currentThread();
            signal.setOnCancelListener(new OnCancelListener() {
                @Override
                public void onCancel() {
                    blocked.interrupt();
                }
            });
            signal.throwIfCanceled();
        }

        try {
            Thread.sleep(timeoutMillis);
        } catch (InterruptedException e) {
        }

        if (signal != null) {
            signal.throwIfCanceled();
        }
    }

    private static void includeFile(MatrixCursor result, String docId, int flags) {
        final RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        row.add(Document.COLUMN_DISPLAY_NAME, docId);
        row.add(Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis());
        row.add(Document.COLUMN_FLAGS, flags);

        if (MY_DOC_ID.equals(docId)) {
            row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
            row.add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE);
        } else if (MY_DOC_NULL.equals(docId)) {
            // No MIME type
        } else {
            row.add(Document.COLUMN_MIME_TYPE, "application/octet-stream");
        }
    }
}
