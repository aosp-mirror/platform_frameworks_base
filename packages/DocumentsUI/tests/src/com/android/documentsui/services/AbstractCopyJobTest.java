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

package com.android.documentsui.services;

import static com.google.common.collect.Lists.newArrayList;

import android.net.Uri;
import android.provider.DocumentsContract;
import android.test.suitebuilder.annotation.MediumTest;

import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;

import java.util.List;

@MediumTest
public abstract class AbstractCopyJobTest<T extends CopyJob> extends AbstractJobTest<T> {

    public void runCopyFilesTest() throws Exception {
        Uri testFile1 = mDocs.createDocument(mSrcRoot, "text/plain", "test1.txt");
        mDocs.writeDocument(testFile1, HAM_BYTES);

        Uri testFile2 = mDocs.createDocument(mSrcRoot, "text/plain", "test2.txt");
        mDocs.writeDocument(testFile2, FRUITY_BYTES);

        createJob(newArrayList(testFile1, testFile2)).run();
        mJobListener.waitForFinished();

        mDocs.assertChildCount(mDestRoot, 2);
        mDocs.assertHasFile(mDestRoot, "test1.txt");
        mDocs.assertHasFile(mDestRoot, "test2.txt");
        mDocs.assertFileContents(mDestRoot.documentId, "test1.txt", HAM_BYTES);
        mDocs.assertFileContents(mDestRoot.documentId, "test2.txt", FRUITY_BYTES);
    }

    public void runCopyVirtualTypedFileTest() throws Exception {
        Uri testFile = mDocs.createVirtualFile(
                mSrcRoot, "/virtual.sth", "virtual/mime-type",
                FRUITY_BYTES, "application/pdf", "text/html");

        createJob(newArrayList(testFile)).run();

        mJobListener.waitForFinished();

        mDocs.assertChildCount(mDestRoot, 1);
        mDocs.assertHasFile(mDestRoot, "virtual.sth.pdf");  // copy should convert file to PDF.
        mDocs.assertFileContents(mDestRoot.documentId, "virtual.sth.pdf", FRUITY_BYTES);
    }

    public void runCopyVirtualNonTypedFileTest() throws Exception {
        Uri testFile = mDocs.createVirtualFile(
                mSrcRoot, "/virtual.sth", "virtual/mime-type",
                FRUITY_BYTES);

        createJob(newArrayList(testFile)).run();

        mJobListener.waitForFinished();
        mJobListener.assertFailed();
        mJobListener.assertFilesFailed(newArrayList("virtual.sth"));

        mDocs.assertChildCount(mDestRoot, 0);
    }

    public void runCopyEmptyDirTest() throws Exception {
        Uri testDir = mDocs.createFolder(mSrcRoot, "emptyDir");

        createJob(newArrayList(testDir)).run();
        mJobListener.waitForFinished();

        mDocs.assertChildCount(mDestRoot, 1);
        mDocs.assertHasDirectory(mDestRoot, "emptyDir");
    }

    public void runCopyDirRecursivelyTest() throws Exception {

        Uri testDir1 = mDocs.createFolder(mSrcRoot, "dir1");
        mDocs.createDocument(testDir1, "text/plain", "test1.txt");

        Uri testDir2 = mDocs.createFolder(testDir1, "dir2");
        mDocs.createDocument(testDir2, "text/plain", "test2.txt");

        createJob(newArrayList(testDir1)).run();
        mJobListener.waitForFinished();

        DocumentInfo dir1Copy = mDocs.findDocument(mDestRoot.documentId, "dir1");

        mDocs.assertChildCount(dir1Copy.derivedUri, 2);
        mDocs.assertHasDirectory(dir1Copy.derivedUri, "dir2");
        mDocs.assertHasFile(dir1Copy.derivedUri, "test1.txt");

        DocumentInfo dir2Copy = mDocs.findDocument(dir1Copy.documentId, "dir2");
        mDocs.assertChildCount(dir2Copy.derivedUri, 1);
        mDocs.assertHasFile(dir2Copy.derivedUri, "test2.txt");
    }

    public void runNoCopyDirToSelfTest() throws Exception {
        Uri testDir = mDocs.createFolder(mSrcRoot, "someDir");

        createJob(newArrayList(testDir),
                DocumentsContract.buildDocumentUri(AUTHORITY, mSrcRoot.documentId),
                testDir).run();

        mJobListener.waitForFinished();
        mJobListener.assertFailed();
        mJobListener.assertFilesFailed(newArrayList("someDir"));

        mDocs.assertChildCount(mDestRoot, 0);
    }

    public void runNoCopyDirToDescendentTest() throws Exception {
        Uri testDir = mDocs.createFolder(mSrcRoot, "someDir");
        Uri destDir = mDocs.createFolder(testDir, "theDescendent");

        createJob(newArrayList(testDir),
                DocumentsContract.buildDocumentUri(AUTHORITY, mSrcRoot.documentId),
                destDir).run();

        mJobListener.waitForFinished();
        mJobListener.assertFailed();
        mJobListener.assertFilesFailed(newArrayList("someDir"));

        mDocs.assertChildCount(mDestRoot, 0);
    }

    public void runCopyFileWithReadErrorsTest() throws Exception {
        Uri testFile = mDocs.createDocument(mSrcRoot, "text/plain", "test1.txt");
        mDocs.writeDocument(testFile, HAM_BYTES);

        String testId = DocumentsContract.getDocumentId(testFile);
        mClient.call("simulateReadErrorsForFile", testId, null);

        createJob(newArrayList(testFile)).run();

        mJobListener.waitForFinished();
        mJobListener.assertFailed();
        mJobListener.assertFilesFailed(newArrayList("test1.txt"));

        mDocs.assertChildCount(mDestRoot, 0);
    }

    /**
     * Creates a job with a stack consisting to the default source and destination.
     * TODO: Clean up, as mDestRoot.documentInfo may not really be the parent of
     * srcs.
     */
    final T createJob(List<Uri> srcs) throws Exception {
        Uri srcParent = DocumentsContract.buildDocumentUri(AUTHORITY, mSrcRoot.documentId);
        Uri destination = DocumentsContract.buildDocumentUri(AUTHORITY, mDestRoot.documentId);
        return createJob(srcs, srcParent, destination);
    }
}
