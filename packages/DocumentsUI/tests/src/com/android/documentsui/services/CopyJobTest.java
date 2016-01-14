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
public class CopyJobTest extends BaseCopyJobTest {

    public void testCopyFiles() throws Exception {
        runCopyFilesTest();
    }

    public void testCopyVirtualTypedFile() throws Exception {
        runCopyVirtualTypedFileTest();
    }

    public void testCopyVirtualNonTypedFile() throws Exception {
        runCopyVirtualNonTypedFileTest();
    }

    public void testCopyEmptyDir() throws Exception {
        runCopyEmptyDirTest();
    }

    public void testCopyDirRecursively() throws Exception {
        runCopyDirRecursivelyTest();
    }

    public void testNoCopyDirToSelf() throws Exception {
        runNoCopyDirToSelfTest();
    }

    public void testNoCopyDirToDescendent() throws Exception {
        runNoCopyDirToDescendentTest();
    }

    public void testCopyFileWithReadErrors() throws Exception {
        runCopyFileWithReadErrorsTest();
    }

    @Override
    CopyJob createJob(List<Uri> srcs, Uri destination) throws Exception {
        DocumentStack stack = new DocumentStack();
        stack.push(DocumentInfo.fromUri(mResolver, destination));

        List<DocumentInfo> srcDocs = Lists.newArrayList();
        for (Uri src : srcs) {
            srcDocs.add(DocumentInfo.fromUri(mResolver, src));
        }

        return new CopyJob(
                mContext, mContext, mJobListener, FileOperations.createJobId(), stack, srcDocs);
    }
}
