/*
 * Copyright (C) 2016 The Android Open Source Project
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
public class DeleteJobTest extends AbstractJobTest<DeleteJob> {

    public void testDeleteFiles() throws Exception {
        Uri testFile1 = mDocs.createDocument(mSrcRoot, "text/plain", "test1.txt");
        mDocs.writeDocument(testFile1, HAM_BYTES);

        Uri testFile2 = mDocs.createDocument(mSrcRoot, "text/plain", "test2.txt");
        mDocs.writeDocument(testFile2, FRUITY_BYTES);

        createJob(newArrayList(testFile1, testFile2),
                DocumentsContract.buildDocumentUri(AUTHORITY, mSrcRoot.documentId)).run();
        mJobListener.waitForFinished();

        mDocs.assertChildCount(mSrcRoot, 0);
    }

    /**
     * Creates a job with a stack consisting to the default src directory.
     */
    private final DeleteJob createJob(List<Uri> srcs, Uri srcParent) throws Exception {
        Uri stack = DocumentsContract.buildDocumentUri(AUTHORITY, mSrcRoot.documentId);
        return createJob(srcs, srcParent, stack);
    }

    @Override
    // TODO: Remove inheritance, as stack is not used for deleting, nor srcParent.
    DeleteJob createJob(List<DocumentInfo> srcs, DocumentInfo srcParent, DocumentStack stack)
            throws Exception {
        return new DeleteJob(
                mContext, mContext, mJobListener, FileOperations.createJobId(), stack, srcs,
                srcParent);
    }
}
