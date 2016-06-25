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
import static org.junit.Assert.assertTrue;

import android.net.Uri;
import android.os.AsyncTask;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.documentsui.ClipStorage.Reader;
import com.android.documentsui.dirlist.TestModel;
import com.android.documentsui.testing.TestScheduledExecutorService;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ClipStorageTest {
    private static final List<Uri> TEST_URIS = createList(
            "content://ham/fancy",
            "content://poodle/monkey/giraffe");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private TestScheduledExecutorService mExecutor;

    private ClipStorage mStorage;
    private TestModel mModel;

    private long mTag;

    @Before
    public void setUp() {
        File clipDir = ClipStorage.prepareStorage(folder.getRoot());
        mStorage = new ClipStorage(clipDir);

        mExecutor = new TestScheduledExecutorService();
        AsyncTask.setDefaultExecutor(mExecutor);

        mTag = mStorage.createTag();
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
        try(Reader provider = mStorage.createReader(mTag)) {
            for (Uri uri : provider) {
                uris.add(uri);
            }
        }
        assertEquals(TEST_URIS, uris);
    }

    @Test
    public void testDelete() throws Exception {
        writeAll(mTag, TEST_URIS);
        mStorage.delete(mTag);
        try {
            mStorage.createReader(mTag);
        } catch (IOException expected) {}
    }

    @Test
    public void testPrepareStorage_CreatesDir() throws Exception {
        File clipDir = ClipStorage.prepareStorage(folder.getRoot());
        assertTrue(clipDir.exists());
        assertTrue(clipDir.isDirectory());
        assertFalse(clipDir.equals(folder.getRoot()));
    }

    private void writeAll(long tag, List<Uri> uris) {
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
