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
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.documentsui.ClipStorage.Writer;
import com.android.documentsui.dirlist.TestModel;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ClipStorageTest {
    private static final List<Uri> TEST_URIS = createList(
            "content://ham/fancy",
            "content://poodle/monkey/giraffe");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ClipStorage mStorage;
    private TestModel mModel;

    @Before
    public void setUp() {
        File clipDir = ClipStorage.prepareStorage(folder.getRoot());
        mStorage = new ClipStorage(clipDir);
    }

    @Test
    public void testWritePrimary() throws Exception {
        Writer writer = mStorage.createWriter();
        writeAll(TEST_URIS, writer);
    }

    @Test
    public void testRead() throws Exception {
        Writer writer = mStorage.createWriter();
        writeAll(TEST_URIS, writer);
        long tag = mStorage.savePrimary();
        List<Uri> uris = mStorage.read(tag);
        assertEquals(TEST_URIS, uris);
    }

    @Test
    public void testDelete() throws Exception {
        Writer writer = mStorage.createWriter();
        writeAll(TEST_URIS, writer);
        long tag = mStorage.savePrimary();
        mStorage.delete(tag);
        try {
            mStorage.read(tag);
        } catch (IOException expected) {}
    }

    @Test
    public void testPrepareStorage_CreatesDir() throws Exception {
        File clipDir = ClipStorage.prepareStorage(folder.getRoot());
        assertTrue(clipDir.exists());
        assertTrue(clipDir.isDirectory());
        assertFalse(clipDir.equals(folder.getRoot()));
    }

    @Test
    public void testPrepareStorage_DeletesPreviousClipFiles() throws Exception {
        File clipDir = ClipStorage.prepareStorage(folder.getRoot());
        new File(clipDir, "somefakefile.poodles").createNewFile();
        new File(clipDir, "yodles.yam").createNewFile();

        assertEquals(2, clipDir.listFiles().length);
        clipDir = ClipStorage.prepareStorage(folder.getRoot());
        assertEquals(0, clipDir.listFiles().length);
    }

    private static void writeAll(List<Uri> uris, Writer writer) throws IOException {
        for (Uri uri : uris) {
            writer.write(uri);
        }
    }

    private static List<Uri> createList(String... values) {
        List<Uri> uris = new ArrayList<>(values.length);
        for (int i = 0; i < values.length; i++) {
            uris.add(i, Uri.parse(values[i]));
        }
        return uris;
    }
}
