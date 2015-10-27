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
import android.provider.DocumentsContract.Document;
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

    public CopyTest() {
        super(CopyService.class);
    }

    private static String AUTHORITY = "com.android.documentsui.stubprovider";
    private static String SRC_ROOT = StubProvider.ROOT_0_ID;
    private static String DST_ROOT = StubProvider.ROOT_1_ID;
    private static String TAG = "CopyTest";

    private Context mContext;
    private TestContentResolver mResolver;
    private ContentProviderClient mClient;
    private DocumentsProviderHelper mDocHelper;
    private StubProvider mStorage;
    private Context mSystemContext;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        setupTestContext();
        mClient = mResolver.acquireContentProviderClient(AUTHORITY);

        // Reset the stub provider's storage.
        mStorage.clearCacheAndBuildRoots();

        mDocHelper = new DocumentsProviderHelper(AUTHORITY, mClient);
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
        Uri testFile = mStorage.createFile(SRC_ROOT, srcPath, "text/plain",
                "The five boxing wizards jump quickly".getBytes());

        assertDstFileCountEquals(0);

        startService(createCopyIntent(Lists.newArrayList(testFile)));

        // 2 operations: file creation, then writing data.
        mResolver.waitForChanges(2);

        // Verify that one file was copied; check file contents.
        assertDstFileCountEquals(1);
        assertCopied(srcPath);
    }

    public void testMoveFile() throws Exception {
        String srcPath = "/test0.txt";
        String testContent = "The five boxing wizards jump quickly";
        Uri testFile = mStorage.createFile(SRC_ROOT, srcPath, "text/plain", testContent.getBytes());

        assertDstFileCountEquals(0);

        Intent moveIntent = createCopyIntent(Lists.newArrayList(testFile));
        moveIntent.putExtra(CopyService.EXTRA_TRANSFER_MODE, CopyService.TRANSFER_MODE_MOVE);
        startService(moveIntent);

        // 3 operations: file creation, writing data, deleting original.
        mResolver.waitForChanges(3);

        // Verify that one file was moved; check file contents.
        assertDstFileCountEquals(1);
        assertDoesNotExist(SRC_ROOT, srcPath);

        byte[] dstContent = readFile(DST_ROOT, srcPath);
        MoreAsserts.assertEquals("Moved file contents differ", testContent.getBytes(), dstContent);
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
                mStorage.createFile(SRC_ROOT, srcPaths[0], "text/plain", testContent[0].getBytes()),
                mStorage.createFile(SRC_ROOT, srcPaths[1], "text/plain", testContent[1].getBytes()),
                mStorage.createFile(SRC_ROOT, srcPaths[2], "text/plain", testContent[2].getBytes()));

        assertDstFileCountEquals(0);

        // Copy all the test files.
        startService(createCopyIntent(testFiles));

        // 3 file creations, 3 file writes.
        mResolver.waitForChanges(6);

        assertDstFileCountEquals(3);
        for (String path : srcPaths) {
            assertCopied(path);
        }
    }

    public void testCopyEmptyDir() throws Exception {
        String srcPath = "/emptyDir";
        Uri testDir = mStorage.createFile(SRC_ROOT, srcPath, DocumentsContract.Document.MIME_TYPE_DIR,
                null);

        assertDstFileCountEquals(0);

        startService(createCopyIntent(Lists.newArrayList(testDir)));

        // Just 1 operation: Directory creation.
        mResolver.waitForChanges(1);

        assertDstFileCountEquals(1);

        // Verify that the dst exists and is a directory.
        File dst = mStorage.getFile(DST_ROOT, srcPath);
        assertTrue(dst.isDirectory());
    }

    public void testMoveEmptyDir() throws Exception {
        String srcPath = "/emptyDir";
        Uri testDir = mStorage.createFile(SRC_ROOT, srcPath, DocumentsContract.Document.MIME_TYPE_DIR,
                null);

        assertDstFileCountEquals(0);

        Intent moveIntent = createCopyIntent(Lists.newArrayList(testDir));
        moveIntent.putExtra(CopyService.EXTRA_TRANSFER_MODE, CopyService.TRANSFER_MODE_MOVE);
        startService(moveIntent);

        // 2 operations: Directory creation, and removal of the original.
        mResolver.waitForChanges(2);

        assertDstFileCountEquals(1);

        // Verify that the dst exists and is a directory.
        File dst = mStorage.getFile(DST_ROOT, srcPath);
        assertTrue(dst.isDirectory());

        // Verify that the src was cleaned up.
        assertDoesNotExist(SRC_ROOT, srcPath);
    }

    public void testMovePopulatedDir() throws Exception {
        String testContent[] = {
                "The five boxing wizards jump quickly",
                "The quick brown fox jumps over the lazy dog",
                "Jackdaws love my big sphinx of quartz"
        };
        String srcDir = "/testdir";
        String srcFiles[] = {
                srcDir + "/test0.txt",
                srcDir + "/test1.txt",
                srcDir + "/test2.txt"
        };
        // Create test dir; put some files in it.
        Uri testDir = mStorage.createFile(SRC_ROOT, srcDir, DocumentsContract.Document.MIME_TYPE_DIR,
                null);
        mStorage.createFile(SRC_ROOT, srcFiles[0], "text/plain", testContent[0].getBytes());
        mStorage.createFile(SRC_ROOT, srcFiles[1], "text/plain", testContent[1].getBytes());
        mStorage.createFile(SRC_ROOT, srcFiles[2], "text/plain", testContent[2].getBytes());

        Intent moveIntent = createCopyIntent(Lists.newArrayList(testDir));
        moveIntent.putExtra(CopyService.EXTRA_TRANSFER_MODE, CopyService.TRANSFER_MODE_MOVE);
        startService(moveIntent);

        // dir creation, then creation and writing of 3 files, then removal of src dir and 3 src
        // files.
        mResolver.waitForChanges(11);

        // Check the content of the moved files.
        File dst = mStorage.getFile(DST_ROOT, srcDir);
        assertTrue(dst.isDirectory());
        for (int i = 0; i < testContent.length; ++i) {
            byte[] dstContent = readFile(DST_ROOT, srcFiles[i]);
            MoreAsserts.assertEquals("Copied file contents differ", testContent[i].getBytes(),
                    dstContent);
        }

        // Check that the src files were removed.
        assertDoesNotExist(SRC_ROOT, srcDir);
        for (String srcFile : srcFiles) {
            assertDoesNotExist(SRC_ROOT, srcFile);
        }
    }

    public void testCopyFileWithReadErrors() throws Exception {
        String srcPath = "/test0.txt";
        Uri testFile = mStorage.createFile(SRC_ROOT, srcPath, "text/plain",
                "The five boxing wizards jump quickly".getBytes());

        assertDstFileCountEquals(0);

        mStorage.simulateReadErrorsForFile(testFile);

        startService(createCopyIntent(Lists.newArrayList(testFile)));

        // 3 operations: file creation, writing, then deletion (due to failed copy).
        mResolver.waitForChanges(3);

        // Verify that the failed copy was cleaned up.
        assertDstFileCountEquals(0);
    }

    public void testMoveFileWithReadErrors() throws Exception {
        String srcPath = "/test0.txt";
        Uri testFile = mStorage.createFile(SRC_ROOT, srcPath, "text/plain",
                "The five boxing wizards jump quickly".getBytes());

        assertDstFileCountEquals(0);

        mStorage.simulateReadErrorsForFile(testFile);

        Intent moveIntent = createCopyIntent(Lists.newArrayList(testFile));
        moveIntent.putExtra(CopyService.EXTRA_TRANSFER_MODE, CopyService.TRANSFER_MODE_MOVE);
        startService(moveIntent);

        try {
            // There should be 3 operations: file creation, writing, then deletion (due to failed
            // copy). Wait for 4, in case the CopyService also attempts to do extra stuff (like
            // delete the src file). This should time out.
            mResolver.waitForChanges(4);
        } catch (TimeoutException e) {
            // Success path
            return;
        } finally {
            // Verify that the failed copy was cleaned up, and the src file wasn't removed.
            assertDstFileCountEquals(0);
            assertExists(SRC_ROOT, srcPath);
        }
        // The asserts above didn't fail, but the CopyService did something unexpected.
        fail("Extra file operations were detected");
    }

    public void testMoveDirectoryWithReadErrors() throws Exception {
        String testContent[] = {
                "The five boxing wizards jump quickly",
                "The quick brown fox jumps over the lazy dog",
                "Jackdaws love my big sphinx of quartz"
        };
        String srcDir = "/testdir";
        String srcFiles[] = {
                srcDir + "/test0.txt",
                srcDir + "/test1.txt",
                srcDir + "/test2.txt"
        };
        // Create test dir; put some files in it.
        Uri testDir = mStorage.createFile(SRC_ROOT, srcDir, DocumentsContract.Document.MIME_TYPE_DIR,
                null);
        mStorage.createFile(SRC_ROOT, srcFiles[0], "text/plain", testContent[0].getBytes());
        Uri errFile = mStorage
                .createFile(SRC_ROOT, srcFiles[1], "text/plain", testContent[1].getBytes());
        mStorage.createFile(SRC_ROOT, srcFiles[2], "text/plain", testContent[2].getBytes());

        mStorage.simulateReadErrorsForFile(errFile);

        Intent moveIntent = createCopyIntent(Lists.newArrayList(testDir));
        moveIntent.putExtra(CopyService.EXTRA_TRANSFER_MODE, CopyService.TRANSFER_MODE_MOVE);
        startService(moveIntent);

        // - dst dir creation,
        // - creation and writing of 2 files, removal of 2 src files
        // - creation and writing of 1 file, then removal of that file (due to error)
        mResolver.waitForChanges(10);

        // Check that both the src and dst dirs exist. The src dir shouldn't have been removed,
        // because it should contain the one errFile.
        assertTrue(mStorage.getFile(SRC_ROOT, srcDir).isDirectory());
        assertTrue(mStorage.getFile(DST_ROOT, srcDir).isDirectory());

        // Check the content of the moved files.
        MoreAsserts.assertEquals("Copied file contents differ", testContent[0].getBytes(),
                readFile(DST_ROOT, srcFiles[0]));
        MoreAsserts.assertEquals("Copied file contents differ", testContent[2].getBytes(),
                readFile(DST_ROOT, srcFiles[2]));

        // Check that the src files were removed.
        assertDoesNotExist(SRC_ROOT, srcFiles[0]);
        assertDoesNotExist(SRC_ROOT, srcFiles[2]);

        // Check that the error file was not copied over.
        assertDoesNotExist(DST_ROOT, srcFiles[1]);
        assertExists(SRC_ROOT, srcFiles[1]);
    }

    /**
     * Copies the given files to a pre-determined destination.
     *
     * @throws FileNotFoundException
     */
    private Intent createCopyIntent(List<Uri> srcs) throws Exception {
        final ArrayList<DocumentInfo> srcDocs = Lists.newArrayList();
        for (Uri src : srcs) {
            srcDocs.add(DocumentInfo.fromUri(mResolver, src));
        }

        RootInfo root = mDocHelper.getRoot(DST_ROOT);
        final Uri dst = DocumentsContract.buildDocumentUri(AUTHORITY, root.documentId);
        DocumentStack stack = new DocumentStack();
        stack.push(DocumentInfo.fromUri(mResolver, dst));
        final Intent copyIntent = new Intent(mContext, CopyService.class);
        copyIntent.putParcelableArrayListExtra(CopyService.EXTRA_SRC_LIST, srcDocs);
        copyIntent.putExtra(Shared.EXTRA_STACK, (Parcelable) stack);

        // startService(copyIntent);
        return copyIntent;
    }

    /**
     * Returns a count of the files in the given directory.
     */
    private void assertDstFileCountEquals(int expected) throws RemoteException {
        RootInfo dest = mDocHelper.getRoot(DST_ROOT);
        final Uri queryUri = DocumentsContract.buildChildDocumentsUri(AUTHORITY,
                dest.documentId);
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

    private void assertExists(String rootId, String path) throws Exception {
        assertNotNull("An expected file was not found: " + path + " on root " + rootId,
                mStorage.getFile(rootId, path));
    }

    private void assertDoesNotExist(String rootId, String path) throws Exception {
        assertNull("Unexpected file found: " + path + " on root " + rootId,
                mStorage.getFile(rootId, path));
    }

    private byte[] readFile(String rootId, String path) throws Exception {
        File file = mStorage.getFile(rootId, path);
        byte[] buf = null;
        assertNotNull(file);

        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            buf = Streams.readFully(in);
        } finally {
            IoUtils.closeQuietly(in);
        }
        return buf;
    }

    private void assertCopied(String path) throws Exception {
        MoreAsserts.assertEquals("Copied file contents differ", readFile(SRC_ROOT, path),
                readFile(DST_ROOT, path));
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
                throw new TimeoutException("Timed out waiting for file operations to complete.");
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
}
