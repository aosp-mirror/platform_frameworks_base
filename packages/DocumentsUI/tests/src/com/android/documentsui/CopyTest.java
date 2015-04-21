/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.documentsui;

import static com.android.documentsui.model.DocumentInfo.getCursorString;

import android.app.NotificationManager;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.RemoteException;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.test.MoreAsserts;
import android.test.ServiceTestCase;
import android.util.Log;

import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.model.RootInfo;
import com.google.common.collect.Lists;

import libcore.io.IoUtils;
import libcore.io.Streams;

import org.mockito.Mockito;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CopyTest extends ServiceTestCase<CopyService> {

    public CopyTest() {
        super(CopyService.class);
    }

    private static String TAG = "CopyTest";
    // This must match the authority for the StubProvider.
    private static String AUTHORITY = "com.android.documentsui.stubprovider";
    private List<RootInfo> mRoots;
    private Context mContext;
    private ContentResolver mResolver;
    private ContentProviderClient mClient;
    private NotificationManager mNotificationManager;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        setupTestContext();

        mResolver = mContext.getContentResolver();
        mClient = mResolver.acquireContentProviderClient(AUTHORITY);

        // Reset the stub provider's storage.
        mClient.call("clear", "", null);

        mRoots = Lists.newArrayList();
        Uri queryUri = DocumentsContract.buildRootsUri(AUTHORITY);
        Cursor cursor = null;
        try {
            cursor = mClient.query(queryUri, null, null, null, null);
            while (cursor.moveToNext()) {
                final RootInfo root = RootInfo.fromRootsCursor(AUTHORITY, cursor);
                final String id = root.rootId;
                mRoots.add(root);
            }
        } finally {
            IoUtils.closeQuietly(cursor);
        }

    }

    @Override
    protected void tearDown() throws Exception {
        mClient.release();
        super.tearDown();
    }

    public List<Uri> setupTestFiles() throws Exception {
        Uri rootUri = DocumentsContract.buildDocumentUri(AUTHORITY, mRoots.get(0).documentId);
        List<Uri> testFiles = Lists.newArrayList(
                DocumentsContract.createDocument(mClient, rootUri, "text/plain", "test0.txt"),
                DocumentsContract.createDocument(mClient, rootUri, "text/plain", "test1.txt"),
                DocumentsContract.createDocument(mClient, rootUri, "text/plain", "test2.txt")
        );
        String testContent[] = {
                "The five boxing wizards jump quickly",
                "The quick brown fox jumps over the lazy dog",
                "Jackdaws love my big sphinx of quartz"
        };
        for (int i = 0; i < testFiles.size(); ++i) {
            ParcelFileDescriptor pfd = null;
            OutputStream out = null;
            try {
                pfd = mClient.openFile(testFiles.get(i), "w");
                out = new ParcelFileDescriptor.AutoCloseOutputStream(pfd);
                out.write(testContent[i].getBytes());
            } finally {
                IoUtils.closeQuietly(out);
            }
        }
        return testFiles;
    }

    /**
     * Test copying a single file.
     */
    public void testCopyFile() throws Exception {
        Uri testFile = setupTestFiles().get(0);

        // Just copy one file.
        copyToDestination(Lists.newArrayList(testFile));

        // A call to NotificationManager.cancel marks the end of the copy operation.
        Mockito.verify(mNotificationManager, Mockito.timeout(1000)).cancel(Mockito.anyString(),
                Mockito.anyInt());

        // Verify that one file was copied; check file contents.
        assertDstFileCountEquals(1);
        assertCopied(testFile);
    }

    /**
     * Test copying multiple files.
     */
    public void testCopyMultipleFiles() throws Exception {
        List<Uri> testFiles = setupTestFiles();
        // Copy all the test files.
        copyToDestination(testFiles);

        // A call to NotificationManager.cancel marks the end of the copy operation.
        Mockito.verify(mNotificationManager, Mockito.timeout(1000)).cancel(Mockito.anyString(),
                Mockito.anyInt());

        assertDstFileCountEquals(3);
        for (Uri testFile : testFiles) {
            assertCopied(testFile);
        }
    }

    /**
     * Copies the given files to a pre-determined destination.
     *
     * @throws FileNotFoundException
     */
    private void copyToDestination(List<Uri> srcs) throws FileNotFoundException {
        final ArrayList<DocumentInfo> srcDocs = Lists.newArrayList();
        for (Uri src : srcs) {
            srcDocs.add(DocumentInfo.fromUri(mResolver, src));
        }

        final Uri dst = DocumentsContract.buildDocumentUri(AUTHORITY, mRoots.get(1).documentId);
        DocumentStack stack = new DocumentStack();
        stack.push(DocumentInfo.fromUri(mResolver, dst));
        final Intent copyIntent = new Intent(mContext, CopyService.class);
        copyIntent.putParcelableArrayListExtra(CopyService.EXTRA_SRC_LIST, srcDocs);
        copyIntent.putExtra(CopyService.EXTRA_STACK, (Parcelable) stack);

        startService(copyIntent);
    }

    /**
     * Returns a count of the files in the given directory.
     */
    private void assertDstFileCountEquals(int expected) throws RemoteException {
        final Uri queryUri = DocumentsContract.buildChildDocumentsUri(AUTHORITY,
                mRoots.get(1).documentId);
        Cursor c = null;
        int count = 0;
        try {
            c = mClient.query(queryUri, null, null, null, null);
            count = c.getCount();
        } finally {
            IoUtils.closeQuietly(c);
        }
        assertEquals("Incorrect file count after copy", expected, count);
    }

    /**
     * Verifies that the file pointed to by the given URI was correctly copied to the destination.
     */
    private void assertCopied(Uri src) throws Exception {
        Cursor cursor = null;
        String srcName = null;
        try {
            cursor = mClient.query(src, null, null, null, null);
            if (cursor.moveToFirst()) {
                srcName = getCursorString(cursor, Document.COLUMN_DISPLAY_NAME);
            }
        } finally {
            IoUtils.closeQuietly(cursor);
        }
        Uri dst = getDstFileUri(srcName);

        InputStream in0 = null;
        InputStream in1 = null;
        try {
            in0 = new ParcelFileDescriptor.AutoCloseInputStream(mClient.openFile(src, "r"));
            in1 = new ParcelFileDescriptor.AutoCloseInputStream(mClient.openFile(dst, "r"));

            byte[] buffer0 = Streams.readFully(in0);
            byte[] buffer1 = Streams.readFully(in1);

            MoreAsserts.assertEquals(buffer0, buffer1);
        } finally {
            IoUtils.closeQuietly(in0);
            IoUtils.closeQuietly(in1);
        }
    }

    /**
     * Generates a file URI from a given filename. This assumes the file already exists in the
     * destination root.
     */
    private Uri getDstFileUri(String filename) throws RemoteException {
        final Uri dstFileQuery = DocumentsContract.buildChildDocumentsUri(AUTHORITY,
                mRoots.get(1).documentId);
        Cursor cursor = null;
        try {
            // StubProvider doesn't seem to support query strings; filter the results manually.
            cursor = mClient.query(dstFileQuery, null, null, null, null);
            while (cursor.moveToNext()) {
                if (filename.equals(getCursorString(cursor, Document.COLUMN_DISPLAY_NAME))) {
                    return DocumentsContract.buildDocumentUri(AUTHORITY,
                            getCursorString(cursor, Document.COLUMN_DOCUMENT_ID));
                }
            }
        } finally {
            IoUtils.closeQuietly(cursor);
        }
        return null;
    }

    /**
     * Sets up a ContextWrapper that substitutes a stub NotificationManager. This allows the test to
     * listen for notification events, to gauge copy progress.
     */
    private void setupTestContext() {
        mContext = getSystemContext();
        System.setProperty("dexmaker.dexcache", mContext.getCacheDir().getPath());

        mNotificationManager = Mockito.spy((NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE));

        // Insert a stub NotificationManager that enables us to listen for when copying is complete.
        setContext(new ContextWrapper(mContext) {
            @Override
            public Object getSystemService(String name) {
                if (Context.NOTIFICATION_SERVICE.equals(name)) {
                    return mNotificationManager;
                } else {
                    return super.getSystemService(name);
                }
            }
        });
    }
}
