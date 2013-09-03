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
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.util.Log;

import java.io.FileNotFoundException;
import java.lang.ref.WeakReference;

public class TestDocumentsProvider extends DocumentsProvider {
    private static final String TAG = "TestDocuments";

    private static final boolean CRASH_ROOTS = false;
    private static final boolean CRASH_DOCUMENT = false;

    private static final String MY_ROOT_ID = "myRoot";
    private static final String MY_DOC_ID = "myDoc";
    private static final String MY_DOC_NULL = "myNull";

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[] {
            Root.COLUMN_ROOT_ID, Root.COLUMN_ROOT_TYPE, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
            Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES,
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[] {
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
    };

    private static String[] resolveRootProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_ROOT_PROJECTION;
    }

    private static String[] resolveDocumentProjection(String[] projection) {
        return projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION;
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        if (CRASH_ROOTS) System.exit(12);

        final MatrixCursor result = new MatrixCursor(resolveRootProjection(projection));
        final RowBuilder row = result.newRow();
        row.offer(Root.COLUMN_ROOT_ID, MY_ROOT_ID);
        row.offer(Root.COLUMN_ROOT_TYPE, Root.ROOT_TYPE_SERVICE);
        row.offer(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_RECENTS);
        row.offer(Root.COLUMN_TITLE, "_Test title which is really long");
        row.offer(Root.COLUMN_SUMMARY, "_Summary which is also super long text");
        row.offer(Root.COLUMN_DOCUMENT_ID, MY_DOC_ID);
        row.offer(Root.COLUMN_AVAILABLE_BYTES, 1024);
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        if (CRASH_DOCUMENT) System.exit(12);

        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        includeFile(result, documentId);
        return result;
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
                includeFile(result, "_networkfile1");
                includeFile(result, "_networkfile2");
                includeFile(result, "_networkfile3");
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

        final ContentResolver resolver = getContext().getContentResolver();
        final Uri notifyUri = DocumentsContract.buildDocumentUri(
                "com.example.documents", parentDocumentId);

        CloudCursor result = new CloudCursor(resolveDocumentProjection(projection));
        result.setNotificationUri(resolver, notifyUri);

        // Always include local results
        includeFile(result, MY_DOC_NULL);
        includeFile(result, "localfile1");
        includeFile(result, "localfile2");

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
        // Pretend to take a super long time to respond
        SystemClock.sleep(3000);

        final MatrixCursor result = new MatrixCursor(resolveDocumentProjection(projection));
        includeFile(result, "It was /worth/ the_wait for?the file:with the&incredibly long name");
        return result;
    }

    @Override
    public ParcelFileDescriptor openDocument(String docId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        throw new FileNotFoundException();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private static void includeFile(MatrixCursor result, String docId) {
        final RowBuilder row = result.newRow();
        row.offer(Document.COLUMN_DOCUMENT_ID, docId);
        row.offer(Document.COLUMN_DISPLAY_NAME, docId);
        row.offer(Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis());

        if (MY_DOC_ID.equals(docId)) {
            row.offer(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
        } else if (MY_DOC_NULL.equals(docId)) {
            // No MIME type
        } else {
            row.offer(Document.COLUMN_MIME_TYPE, "application/octet-stream");
        }
    }
}
