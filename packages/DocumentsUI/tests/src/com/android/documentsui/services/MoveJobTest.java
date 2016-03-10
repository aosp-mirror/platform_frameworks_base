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
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract;
import android.test.suitebuilder.annotation.MediumTest;

import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;

import java.util.List;

@MediumTest
public class MoveJobTest extends AbstractCopyJobTest<MoveJob> {

    public void testMoveFiles() throws Exception {
        runCopyFilesTest();

        mDocs.assertChildCount(mSrcRoot, 0);
    }

    public void testMoveVirtualTypedFile() throws Exception {
        mDocs.createFolder(mSrcRoot, "hello");
        Uri testFile = mDocs.createVirtualFile(
                mSrcRoot, "/hello/virtual.sth", "virtual/mime-type",
                FRUITY_BYTES, "application/pdf", "text/html");
        createJob(newArrayList(testFile)).run();

        mJobListener.waitForFinished();

        // Should have failed, source not deleted. Moving by bytes for virtual files
        // is not supported.
        mDocs.assertChildCount(mDestRoot, 0);
        mDocs.assertChildCount(mSrcRoot, 1);
    }

    public void testMoveVirtualNonTypedFile() throws Exception {
        runCopyVirtualNonTypedFileTest();

        // Should have failed, source not deleted.
        mDocs.assertChildCount(mSrcRoot, 1);
    }

    public void testMove_BackendSideVirtualTypedFile_Fallback() throws Exception {
        Uri testFile = mDocs.createDocumentWithFlags(
                mSrcRoot.documentId, "virtual/mime-type", "tokyo.sth",
                Document.FLAG_VIRTUAL_DOCUMENT | Document.FLAG_SUPPORTS_COPY
                        | Document.FLAG_SUPPORTS_MOVE, "application/pdf");

        createJob(newArrayList(testFile)).run();
        mJobListener.waitForFinished();

        // Should have failed, source not deleted. Moving by bytes for virtual files
        // is not supported.
        mDocs.assertChildCount(mDestRoot, 0);
        mDocs.assertChildCount(mSrcRoot, 1);
    }

    public void testMoveEmptyDir() throws Exception {
        runCopyEmptyDirTest();

        mDocs.assertChildCount(mSrcRoot, 0);
    }

    public void testMoveDirRecursively() throws Exception {
        runCopyDirRecursivelyTest();

        mDocs.assertChildCount(mSrcRoot, 0);
    }

    public void testNoMoveDirToSelf() throws Exception {
        runNoCopyDirToSelfTest();

        // should have failed, source not deleted
        mDocs.assertChildCount(mSrcRoot, 1);
    }

    public void testNoMoveDirToDescendent() throws Exception {
        runNoCopyDirToDescendentTest();

        // should have failed, source not deleted
        mDocs.assertChildCount(mSrcRoot, 1);
    }

    public void testMoveFileWithReadErrors() throws Exception {
        runCopyFileWithReadErrorsTest();

        // should have failed, source not deleted
        mDocs.assertChildCount(mSrcRoot, 1);
    }

    // TODO: Add test cases for moving when multi-parented.

    @Override
    MoveJob createJob(List<DocumentInfo> srcs, DocumentInfo srcParent, DocumentStack stack)
            throws Exception {
        return new MoveJob(
                mContext, mContext, mJobListener, FileOperations.createJobId(), stack, srcs,
                srcParent);
    }
}
