/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.externalstorage;

import static com.android.externalstorage.ExternalStorageProvider.buildUniqueFile;

import android.os.FileUtils;
import android.provider.DocumentsContract.Document;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;

import java.io.File;

@MediumTest
public class ExternalStorageProviderTest extends AndroidTestCase {

    private File mTarget;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mTarget = getContext().getFilesDir();
        FileUtils.deleteContents(mTarget);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        FileUtils.deleteContents(mTarget);
    }

    public void testBuildUniqueFile_normal() throws Exception {
        assertNameEquals("test.jpg", buildUniqueFile(mTarget, "image/jpeg", "test"));
        assertNameEquals("test.jpg", buildUniqueFile(mTarget, "image/jpeg", "test.jpg"));
        assertNameEquals("test.jpeg", buildUniqueFile(mTarget, "image/jpeg", "test.jpeg"));
        assertNameEquals("TEst.JPeg", buildUniqueFile(mTarget, "image/jpeg", "TEst.JPeg"));
        assertNameEquals("test.png.jpg", buildUniqueFile(mTarget, "image/jpeg", "test.png.jpg"));
        assertNameEquals("test.png.jpg", buildUniqueFile(mTarget, "image/jpeg", "test.png"));

        assertNameEquals("test.flac", buildUniqueFile(mTarget, "audio/flac", "test"));
        assertNameEquals("test.flac", buildUniqueFile(mTarget, "audio/flac", "test.flac"));
        assertNameEquals("test.flac", buildUniqueFile(mTarget, "application/x-flac", "test"));
        assertNameEquals("test.flac", buildUniqueFile(mTarget, "application/x-flac", "test.flac"));
    }

    public void testBuildUniqueFile_unknown() throws Exception {
        assertNameEquals("test", buildUniqueFile(mTarget, "application/octet-stream", "test"));
        assertNameEquals("test.jpg", buildUniqueFile(mTarget, "application/octet-stream", "test.jpg"));
        assertNameEquals(".test", buildUniqueFile(mTarget, "application/octet-stream", ".test"));

        assertNameEquals("test", buildUniqueFile(mTarget, "lolz/lolz", "test"));
        assertNameEquals("test.lolz", buildUniqueFile(mTarget, "lolz/lolz", "test.lolz"));
    }

    public void testBuildUniqueFile_dir() throws Exception {
        assertNameEquals("test", buildUniqueFile(mTarget, Document.MIME_TYPE_DIR, "test"));
        new File(mTarget, "test").mkdir();
        assertNameEquals("test (1)", buildUniqueFile(mTarget, Document.MIME_TYPE_DIR, "test"));

        assertNameEquals("test.jpg", buildUniqueFile(mTarget, Document.MIME_TYPE_DIR, "test.jpg"));
        new File(mTarget, "test.jpg").mkdir();
        assertNameEquals("test.jpg (1)", buildUniqueFile(mTarget, Document.MIME_TYPE_DIR, "test.jpg"));
    }

    public void testBuildUniqueFile_increment() throws Exception {
        assertNameEquals("test.jpg", buildUniqueFile(mTarget, "image/jpeg", "test.jpg"));
        new File(mTarget, "test.jpg").createNewFile();
        assertNameEquals("test (1).jpg", buildUniqueFile(mTarget, "image/jpeg", "test.jpg"));
        new File(mTarget, "test (1).jpg").createNewFile();
        assertNameEquals("test (2).jpg", buildUniqueFile(mTarget, "image/jpeg", "test.jpg"));
    }

    private static void assertNameEquals(String expected, File actual) {
        assertEquals(expected, actual.getName());
    }
}
