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

import static com.android.documentsui.StubProvider.ROOT_0_ID;
import static com.android.documentsui.StubProvider.ROOT_1_ID;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.DocumentsContract;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;

import com.android.documentsui.DocumentsProviderHelper;
import com.android.documentsui.StubProvider;
import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.DocumentStack;
import com.android.documentsui.model.RootInfo;

import com.google.common.collect.Lists;

import java.util.List;

@MediumTest
public abstract class AbstractJobTest<T extends Job> extends AndroidTestCase {

    static String AUTHORITY = StubProvider.DEFAULT_AUTHORITY;
    static final byte[] HAM_BYTES = "ham and cheese".getBytes();
    static final byte[] FRUITY_BYTES = "I love fruit cakes!".getBytes();

    Context mContext;
    ContentResolver mResolver;
    ContentProviderClient mClient;
    DocumentsProviderHelper mDocs;
    TestJobListener mJobListener;
    RootInfo mSrcRoot;
    RootInfo mDestRoot;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mJobListener = new TestJobListener();

        // NOTE: Must be the "target" context, else security checks in content provider will fail.
        mContext = getContext();
        mResolver = mContext.getContentResolver();

        mClient = mResolver.acquireContentProviderClient(AUTHORITY);
        mDocs = new DocumentsProviderHelper(AUTHORITY, mClient);

        initTestFiles();
    }

    @Override
    protected void tearDown() throws Exception {
        resetStorage();
        mClient.release();
        super.tearDown();
    }

    private void resetStorage() throws RemoteException {
        mClient.call("clear", null, null);
    }

    private void initTestFiles() throws RemoteException {
        mSrcRoot = mDocs.getRoot(ROOT_0_ID);
        mDestRoot = mDocs.getRoot(ROOT_1_ID);
    }

    final T createJob(List<Uri> srcs, Uri srcParent, Uri destination) throws Exception {
        DocumentStack stack = new DocumentStack();
        stack.push(DocumentInfo.fromUri(mResolver, destination));

        List<DocumentInfo> srcDocs = Lists.newArrayList();
        for (Uri src : srcs) {
            srcDocs.add(DocumentInfo.fromUri(mResolver, src));
        }

        return createJob(srcDocs, DocumentInfo.fromUri(mResolver, srcParent), stack);
    }

    abstract T createJob(List<DocumentInfo> srcs, DocumentInfo srcParent, DocumentStack destination)
            throws Exception;
}
