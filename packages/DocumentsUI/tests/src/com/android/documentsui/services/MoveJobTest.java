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

import android.net.Uri;
import android.test.suitebuilder.annotation.MediumTest;

import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;

import com.google.common.collect.Lists;

import java.util.List;

@MediumTest
public class MoveJobTest extends BaseCopyJobTest {

    public void testMoveFiles() throws Exception {
        runCopyFilesTest();

        mDocs.assertChildCount(mSrcRoot, 0);
    }

    public void testMoveVirtualTypedFile() throws Exception {
        runCopyVirtualTypedFileTest();

        mDocs.assertChildCount(mSrcRoot, 0);
    }

    public void testMoveVirtualNonTypedFile() throws Exception {
        runCopyVirtualNonTypedFileTest();

        // should have failed, source not deleted
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

    @Override
    CopyJob createJob(List<Uri> srcs, Uri destination) throws Exception {
        DocumentStack stack = new DocumentStack();
        stack.push(DocumentInfo.fromUri(mResolver, destination));

        List<DocumentInfo> srcDocs = Lists.newArrayList();
        for (Uri src : srcs) {
            srcDocs.add(DocumentInfo.fromUri(mResolver, src));
        }

        return new MoveJob(
                mContext, mContext, mJobListener, FileOperations.createJobId(), stack, srcDocs);
    }
}
