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

import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.DocumentsContract;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;

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
public class UrisSupplierTest {

    private static final String PREF_NAME = "pref";
    private static final String AUTHORITY = "foo";
    private static final List<Uri> SHORT_URI_LIST = createList(3);
    private static final List<Uri> LONG_URI_LIST = createList(Shared.MAX_DOCS_IN_INTENT + 5);

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private SharedPreferences mPref;
    private TestScheduledExecutorService mExecutor;
    private ClipStorage mStorage;

    @Before
    public void setUp() {
        mExecutor = new TestScheduledExecutorService();
        AsyncTask.setDefaultExecutor(mExecutor);

        mPref = InstrumentationRegistry.getContext().getSharedPreferences(PREF_NAME, 0);
        mStorage = new ClipStorage(folder.getRoot(), mPref);
    }

    @AfterClass
    public static void tearDownOnce() {
        AsyncTask.setDefaultExecutor(AsyncTask.SERIAL_EXECUTOR);
    }

    @Test
    public void testItemCountEquals_shortList() throws Exception {
        UrisSupplier uris = createWithShortList();

        assertEquals(SHORT_URI_LIST.size(), uris.getItemCount());
    }

    @Test
    public void testItemCountEquals_longList() throws Exception {
        UrisSupplier uris = createWithLongList();

        assertEquals(LONG_URI_LIST.size(), uris.getItemCount());
    }

    @Test
    public void testGetDocsEquals_shortList() throws Exception {
        UrisSupplier uris = createWithShortList();

        assertIterableEquals(SHORT_URI_LIST, uris.getUris(mStorage));
    }

    @Test
    public void testGetDocsEquals_longList() throws Exception {
        UrisSupplier uris = createWithLongList();

        assertIterableEquals(LONG_URI_LIST, uris.getUris(mStorage));
    }

    @Test
    public void testDispose_shortList() throws Exception {
        UrisSupplier uris = createWithShortList();

        uris.dispose();
    }

    @Test
    public void testDispose_longList() throws Exception {
        UrisSupplier uris = createWithLongList();

        uris.dispose();
    }

    private UrisSupplier createWithShortList() throws Exception {
        return UrisSupplier.create(SHORT_URI_LIST, mStorage);
    }

    private UrisSupplier createWithLongList() throws Exception {
        UrisSupplier uris =
                UrisSupplier.create(LONG_URI_LIST, mStorage);

        mExecutor.runAll();

        return uris;
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
