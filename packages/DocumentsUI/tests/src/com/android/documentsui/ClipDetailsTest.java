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

package com.android.documentsui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.net.Uri;
import android.os.AsyncTask;
import android.provider.DocumentsContract;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.documentsui.services.FileOperationService;
import com.android.documentsui.services.FileOperationService.OpType;
import com.android.documentsui.testing.TestScheduledExecutorService;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class ClipDetailsTest {

    private static final String AUTHORITY = "foo";
    private static final @OpType int OP_TYPE = FileOperationService.OPERATION_COPY;
    private static final Uri SRC_PARENT =
            DocumentsContract.buildDocumentUri(AUTHORITY, Integer.toString(0));
    private static final List<Uri> SHORT_URI_LIST = createList(3);
    private static final List<Uri> LONG_URI_LIST = createList(Shared.MAX_DOCS_IN_INTENT + 5);

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private TestScheduledExecutorService mExecutor;
    private ClipStorage mStorage;

    @Before
    public void setUp() {
        mExecutor = new TestScheduledExecutorService();
        AsyncTask.setDefaultExecutor(mExecutor);

        mStorage = new ClipStorage(folder.getRoot());
    }

    @AfterClass
    public static void tearDownOnce() {
        AsyncTask.setDefaultExecutor(AsyncTask.SERIAL_EXECUTOR);
    }

    @Test
    public void testOpTypeEquals_shortList() {
        ClipDetails details = createDetailsWithShortList();

        assertEquals(OP_TYPE, details.getOpType());
    }

    @Test
    public void testOpTypeEquals_longList() {
        ClipDetails details = createDetailsWithLongList();

        assertEquals(OP_TYPE, details.getOpType());
    }

    @Test
    public void testItemCountEquals_shortList() {
        ClipDetails details = createDetailsWithShortList();

        assertEquals(SHORT_URI_LIST.size(), details.getItemCount());
    }

    @Test
    public void testItemCountEquals_longList() {
        ClipDetails details = createDetailsWithLongList();

        assertEquals(LONG_URI_LIST.size(), details.getItemCount());
    }

    @Test
    public void testGetDocsEquals_shortList() throws Exception {
        ClipDetails details = createDetailsWithShortList();

        assertIterableEquals(SHORT_URI_LIST, details.getDocs(mStorage));
    }

    @Test
    public void testGetDocsEquals_longList() throws Exception {
        ClipDetails details = createDetailsWithLongList();

        assertIterableEquals(LONG_URI_LIST, details.getDocs(mStorage));
    }

    @Test
    public void testDispose_shortList() throws Exception {
        ClipDetails details = createDetailsWithShortList();

        details.dispose(mStorage);
    }

    @Test
    public void testDispose_longList() throws Exception {
        ClipDetails details = createDetailsWithLongList();

        details.dispose(mStorage);
    }

    private ClipDetails createDetailsWithShortList() {
        return ClipDetails.createClipDetails(OP_TYPE, SRC_PARENT, SHORT_URI_LIST, mStorage);
    }

    private ClipDetails createDetailsWithLongList() {
        ClipDetails details =
                ClipDetails.createClipDetails(OP_TYPE, SRC_PARENT, LONG_URI_LIST, mStorage);

        mExecutor.runAll();

        return details;
    }

    private void assertIterableEquals(Iterable<Uri> expected, Iterable<Uri> value) {
        Iterator<Uri> expectedIter = expected.iterator();
        Iterator<Uri> valueIter = value.iterator();

        while (expectedIter.hasNext() && valueIter.hasNext()) {
            assertEquals(expectedIter.next(), valueIter.next());
        }

        assertFalse(expectedIter.hasNext());
        assertFalse(expectedIter.hasNext());
    }

    private static List<Uri> createList(int count) {
        List<Uri> uris = new ArrayList<>(count);

        for (int i = 0; i < count; ++i) {
            uris.add(DocumentsContract.buildDocumentUri(AUTHORITY, Integer.toString(i)));
        }

        return uris;
    }
}
