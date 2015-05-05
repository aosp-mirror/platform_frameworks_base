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

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Parcelable;
import android.os.RemoteException;
import android.provider.DocumentsContract;
import android.test.MoreAsserts;
import android.test.ServiceTestCase;
import android.test.mock.MockContentResolver;
import android.util.Log;

import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.model.RootInfo;
import com.google.common.collect.Lists;

import libcore.io.IoUtils;
import libcore.io.Streams;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class CopyTest extends ServiceTestCase<CopyService> {

    /**
     * A test resolver that enables this test suite to listen for notifications that mark when copy
     * operations are done.
     */
    class TestContentResolver extends MockContentResolver {
        private CountDownLatch mReadySignal;
        private CountDownLatch mNotificationSignal;

        public TestContentResolver() {
            mReadySignal = new CountDownLatch(1);
        }

        /**
         * Wait for the given number of files to be copied to destination. Times out after 1 sec.
         */
        public void waitForChanges(int count) throws Exception {
            // Wait for no more than 1 second by default.
            waitForChanges(count, 1000);
        }

        /**
         * Wait for files to be copied to destination.
         *
         * @param count Number of files to wait for.
         * @param timeOut Timeout in ms. TimeoutException will be thrown if this function times out.
         */
        public void waitForChanges(int count, int timeOut) throws Exception {
            mNotificationSignal = new CountDownLatch(count);
            // Signal that the test is now waiting for files.
            mReadySignal.countDown();
            if (!mNotificationSignal.await(timeOut, TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("Timed out waiting for files to be copied.");
            }
        }

        @Override
        public void notifyChange(Uri uri, ContentObserver observer, boolean syncToNetwork) {
            // Wait until the test is ready to receive file notifications.
            try {
                mReadySignal.await();
            } catch (InterruptedException e) {
                Log.d(TAG, "Interrupted while waiting for file copy readiness");
                Thread.currentThread().interrupt();
            }
            if (DocumentsContract.isDocumentUri(mContext, uri)) {
                Log.d(TAG, "Notification: " + uri);
                // Watch for document URI change notifications - this signifies the end of a copy.
                mNotificationSignal.countDown();
            }
        }
    };

    public CopyTest() {
        super(CopyService.class);
    }

    private static String AUTHORITY = "com.android.documentsui.stubprovider";
    private static String DST = "sd1";
    private static String SRC = "sd0";
    private static String TAG = "CopyTest";
    private List<RootInfo> mRoots;
    private Context mContext;
    private TestContentResolver mResolver;
    private ContentProviderClient mClient;
    private StubProvider mStorage;
    private Context mSystemContext;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        setupTestContext();
        mClient = mResolver.acquireContentProviderClient(AUTHORITY);

        // Reset the stub provider's storage.
        mStorage.clearCacheAndBuildRoots();

        mRoots = Lists.newArrayList();
        Uri queryUri = DocumentsContract.buildRootsUri(AUTHORITY);
        Cursor cursor = null;
        try {
            cursor = mClient.query(queryUri, null, null, null, null);
            while (cursor.moveToNext()) {
                mRoots.add(RootInfo.fromRootsCursor(AUTHORITY, cursor));
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

    /**
     * Test copying a single file.
     */
    public void testCopyFile() throws Exception {
        String srcPath = "/test0.txt";
        Uri testFile = mStorage.createFile(SRC, srcPath, "text/plain",
                "The five boxing wizards jump quickly".getBytes());

        assertDstFileCountEquals(0);

        copyToDestination(Lists.newArrayList(testFile));

        // 2 operations: file creation, then writing data.
        mResolver.waitForChanges(2);

        // Verify that one file was copied; check file contents.
        assertDstFileCountEquals(1);
        assertCopied(srcPath);
    }

    /**
     * Test copying multiple files.
     */
    public void testCopyMultipleFiles() throws Exception {
        String testContent[] = {
                "The five boxing wizards jump quickly",
                "The quick brown fox jumps over the lazy dog",
                "Jackdaws love my big sphinx of quartz"
        };
        String srcPaths[] = {
                "/test0.txt",
                "/test1.txt",
                "/test2.txt"
        };
        List<Uri> testFiles = Lists.newArrayList(
                mStorage.createFile(SRC, srcPaths[0], "text/plain", testContent[0].getBytes()),
                mStorage.createFile(SRC, srcPaths[1], "text/plain", testContent[1].getBytes()),
                mStorage.createFile(SRC, srcPaths[2], "text/plain", testContent[2].getBytes()));

        assertDstFileCountEquals(0);

        // Copy all the test files.
        copyToDestination(testFiles);

        // 3 file creations, 3 file writes.
        mResolver.waitForChanges(6);

        assertDstFileCountEquals(3);
        for (String path : srcPaths) {
            assertCopied(path);
        }
    }

    public void testCopyEmptyDir() throws Exception {
        String srcPath = "/emptyDir";
        Uri testDir = mStorage.createFile(SRC, srcPath, DocumentsContract.Document.MIME_TYPE_DIR,
                null);

        assertDstFileCountEquals(0);

        copyToDestination(Lists.newArrayList(testDir));

        // Just 1 operation: Directory creation.
        mResolver.waitForChanges(1);

        assertDstFileCountEquals(1);

        File dst = mStorage.getFile(DST, srcPath);
        assertTrue(dst.isDirectory());
    }

    public void testReadErrors() throws Exception {
        String srcPath = "/test0.txt";
        Uri testFile = mStorage.createFile(SRC, srcPath, "text/plain",
                "The five boxing wizards jump quickly".getBytes());

        assertDstFileCountEquals(0);

        mStorage.simulateReadErrors(true);

        copyToDestination(Lists.newArrayList(testFile));

        // 3 operations: file creation, writing, then deletion (due to failed copy).
        mResolver.waitForChanges(3);

        assertDstFileCountEquals(0);
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

    private void assertCopied(String path) throws Exception {
        File srcFile = mStorage.getFile(SRC, path);
        File dstFile = mStorage.getFile(DST, path);
        assertNotNull(dstFile);

        FileInputStream src = null;
        FileInputStream dst = null;
        try {
            src = new FileInputStream(srcFile);
            dst = new FileInputStream(dstFile);
            byte[] srcbuf = Streams.readFully(src);
            byte[] dstbuf = Streams.readFully(dst);

            MoreAsserts.assertEquals(srcbuf, dstbuf);
        } finally {
            IoUtils.closeQuietly(src);
            IoUtils.closeQuietly(dst);
        }
    }

    /**
     * Sets up a ContextWrapper that substitutes a stub NotificationManager. This allows the test to
     * listen for notification events, to gauge copy progress.
     *
     * @throws FileNotFoundException
     */
    private void setupTestContext() throws FileNotFoundException {
        mSystemContext = getSystemContext();

        // Set up the context with the test content resolver.
        mResolver = new TestContentResolver();
        mContext = new ContextWrapper(mSystemContext) {
            @Override
            public ContentResolver getContentResolver() {
                return mResolver;
            }
        };
        setContext(mContext);

        // Create a local stub provider and add it to the content resolver.
        ProviderInfo info = new ProviderInfo();
        info.authority = AUTHORITY;
        info.exported = true;
        info.grantUriPermissions = true;
        info.readPermission = android.Manifest.permission.MANAGE_DOCUMENTS;
        info.writePermission = android.Manifest.permission.MANAGE_DOCUMENTS;

        mStorage = new StubProvider();
        mStorage.attachInfo(mContext, info);
        mResolver.addProvider(AUTHORITY, mStorage);
    }
}
