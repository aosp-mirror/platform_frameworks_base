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

package com.android.mtp;

import android.content.Context;
import android.database.Cursor;
import android.mtp.MtpObjectInfo;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

@SmallTest
public class DocumentLoaderTest extends AndroidTestCase {
    private BlockableTestMtpManager mManager;
    private TestContentResolver mResolver;
    private DocumentLoader mLoader;
    final private Identifier mParentIdentifier = new Identifier(0, 0, 0);

    @Override
    public void setUp() {
        mManager = new BlockableTestMtpManager(getContext());
        mResolver = new TestContentResolver();
        mLoader = new DocumentLoader(mManager, mResolver);
    }

    public void testBasic() throws Exception {
        final Uri uri = DocumentsContract.buildChildDocumentsUri(
                MtpDocumentsProvider.AUTHORITY, mParentIdentifier.toDocumentId());
        setUpDocument(mManager, 40);
        mManager.blockDocument(0, 15);
        mManager.blockDocument(0, 35);

        {
            final Cursor cursor = mLoader.queryChildDocuments(
                    MtpDocumentsProvider.DEFAULT_DOCUMENT_PROJECTION, mParentIdentifier);
            assertEquals(DocumentLoader.NUM_INITIAL_ENTRIES, cursor.getCount());
        }

        Thread.sleep(DocumentLoader.NOTIFY_PERIOD_MS);
        mManager.unblockDocument(0, 15);
        mResolver.waitForNotification(uri, 1);

        {
            final Cursor cursor = mLoader.queryChildDocuments(
                    MtpDocumentsProvider.DEFAULT_DOCUMENT_PROJECTION, mParentIdentifier);
            assertEquals(
                    DocumentLoader.NUM_INITIAL_ENTRIES + DocumentLoader.NUM_LOADING_ENTRIES,
                    cursor.getCount());
        }

        mManager.unblockDocument(0, 35);
        mResolver.waitForNotification(uri, 2);

        {
            final Cursor cursor = mLoader.queryChildDocuments(
                    MtpDocumentsProvider.DEFAULT_DOCUMENT_PROJECTION, mParentIdentifier);
            assertEquals(40, cursor.getCount());
        }

        assertEquals(2, mResolver.getChangeCount(uri));
    }

    private void setUpDocument(TestMtpManager manager, int count) {
        int[] childDocuments = new int[count];
        for (int i = 0; i < childDocuments.length; i++) {
            final int objectHandle = i + 1;
            childDocuments[i] = objectHandle;
            manager.setObjectInfo(0, new MtpObjectInfo.Builder()
                    .setObjectHandle(objectHandle)
                    .build());
        }
        manager.setObjectHandles(0, 0, MtpManager.OBJECT_HANDLE_ROOT_CHILDREN, childDocuments);
    }

    private static class BlockableTestMtpManager extends TestMtpManager {
        final private Map<String, CountDownLatch> blockedDocuments = new HashMap<>();

        BlockableTestMtpManager(Context context) {
            super(context);
        }

        void blockDocument(int deviceId, int objectHandle) {
            blockedDocuments.put(pack(deviceId, objectHandle), new CountDownLatch(1));
        }

        void unblockDocument(int deviceId, int objectHandle) {
            blockedDocuments.get(pack(deviceId, objectHandle)).countDown();
        }

        @Override
        MtpObjectInfo getObjectInfo(int deviceId, int objectHandle) throws IOException {
            final CountDownLatch latch = blockedDocuments.get(pack(deviceId, objectHandle));
            if (latch != null) {
                try {
                    latch.await();
                } catch(InterruptedException e) {
                    fail();
                }
            }
            return super.getObjectInfo(deviceId, objectHandle);
        }
    }
}
