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

package com.android.documentsui.clipping;

import static com.android.documentsui.clipping.ClipStorage.NUM_OF_SLOTS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.documentsui.testing.TestScheduledExecutorService;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ClipStorageTest {
    private static final String PREF_NAME = "pref";
    private static final List<Uri> TEST_URIS = createList(
            "content://ham/fancy",
            "content://poodle/monkey/giraffe");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private SharedPreferences mPref;
    private TestScheduledExecutorService mExecutor;
    private ClipStorage mStorage;

    private int mTag;

    @Before
    public void setUp() {
        mPref = InstrumentationRegistry.getContext().getSharedPreferences(PREF_NAME, 0);
        File clipDir = ClipStorage.prepareStorage(folder.getRoot());
        mStorage = new ClipStorage(clipDir, mPref);

        mExecutor = new TestScheduledExecutorService();
        AsyncTask.setDefaultExecutor(mExecutor);

        mTag = mStorage.claimStorageSlot();
    }

    @AfterClass
    public static void tearDownOnce() {
        AsyncTask.setDefaultExecutor(AsyncTask.SERIAL_EXECUTOR);
    }

    @Test
    public void testWrite() throws Exception {
        writeAll(mTag, TEST_URIS);
    }

    @Test
    public void testRead() throws Exception {
        writeAll(mTag, TEST_URIS);
        List<Uri> uris = new ArrayList<>();

        File copy = mStorage.getFile(mTag);
        try(ClipStorageReader provider = mStorage.createReader(copy)) {
            for (Uri uri : provider) {
                uris.add(uri);
            }
        }
        assertEquals(TEST_URIS, uris);
    }

    @Test
    public void testClaimStorageSlot_NoAvailableSlot() throws Exception {
        int firstTag = mStorage.claimStorageSlot();
        writeAll(firstTag, TEST_URIS);
        mStorage.getFile(firstTag);
        for (int i = 0; i < NUM_OF_SLOTS - 1; ++i) {
            int tag = mStorage.claimStorageSlot();
            writeAll(tag, TEST_URIS);
            mStorage.getFile(tag);
        }

        assertEquals(firstTag, mStorage.claimStorageSlot());
    }

    @Test
    public void testReadConcurrently() throws Exception {
        writeAll(mTag, TEST_URIS);
        List<Uri> uris = new ArrayList<>();
        List<Uri> uris2 = new ArrayList<>();

        File copy = mStorage.getFile(mTag);
        File copy2 = mStorage.getFile(mTag);
        try(ClipStorageReader reader = mStorage.createReader(copy)) {
            try(ClipStorageReader reader2 = mStorage.createReader(copy2)){
                Iterator<Uri> iter = reader.iterator();
                Iterator<Uri> iter2 = reader2.iterator();

                while (iter.hasNext() && iter2.hasNext()) {
                    uris.add(iter.next());
                    uris2.add(iter2.next());
                }

                assertFalse(iter.hasNext());
                assertFalse(iter2.hasNext());
            }
        }
        assertEquals(TEST_URIS, uris);
        assertEquals(TEST_URIS, uris2);
    }

    @Test
    public void testPrepareStorage_CreatesDir() throws Exception {
        File clipDir = ClipStorage.prepareStorage(folder.getRoot());
        assertTrue(clipDir.exists());
        assertTrue(clipDir.isDirectory());
        assertFalse(clipDir.equals(folder.getRoot()));
    }

    private void writeAll(int tag, List<Uri> uris) {
        new ClipStorage.PersistTask(mStorage, uris, tag).execute();
        mExecutor.runAll();
    }

    private static List<Uri> createList(String... values) {
        List<Uri> uris = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; i++) {
            uris.add(i, Uri.parse(values[i]));
        }
        return uris;
    }
}
