/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.os;

import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.WEEK_IN_MILLIS;

import android.content.Context;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;

import com.google.android.collect.Sets;

import libcore.io.IoUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.HashSet;

@MediumTest
public class FileUtilsTest extends AndroidTestCase {
    private static final String TEST_DATA =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private File mDir;
    private File mTestFile;
    private File mCopyFile;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mDir = getContext().getDir("testing", Context.MODE_PRIVATE);
        mTestFile = new File(mDir, "test.file");
        mCopyFile = new File(mDir, "copy.file");
    }

    @Override
    protected void tearDown() throws Exception {
        IoUtils.deleteContents(mDir);
    }

    // TODO: test setPermissions(), getPermissions()

    public void testCopyFile() throws Exception {
        stageFile(mTestFile, TEST_DATA);
        assertFalse(mCopyFile.exists());
        FileUtils.copyFile(mTestFile, mCopyFile);
        assertTrue(mCopyFile.exists());
        assertEquals(TEST_DATA, FileUtils.readTextFile(mCopyFile, 0, null));
    }

    public void testCopyToFile() throws Exception {
        final String s = "Foo Bar";
        assertFalse(mCopyFile.exists());
        FileUtils.copyToFile(new ByteArrayInputStream(s.getBytes()), mCopyFile);
        assertTrue(mCopyFile.exists());
        assertEquals(s, FileUtils.readTextFile(mCopyFile, 0, null));
    }

    public void testIsFilenameSafe() throws Exception {
        assertTrue(FileUtils.isFilenameSafe(new File("foobar")));
        assertTrue(FileUtils.isFilenameSafe(new File("a_b-c=d.e/0,1+23")));
        assertFalse(FileUtils.isFilenameSafe(new File("foo*bar")));
        assertFalse(FileUtils.isFilenameSafe(new File("foo\nbar")));
    }

    public void testReadTextFile() throws Exception {
        stageFile(mTestFile, TEST_DATA);

        assertEquals(TEST_DATA, FileUtils.readTextFile(mTestFile, 0, null));

        assertEquals("ABCDE", FileUtils.readTextFile(mTestFile, 5, null));
        assertEquals("ABCDE<>", FileUtils.readTextFile(mTestFile, 5, "<>"));
        assertEquals(TEST_DATA.substring(0, 51) + "<>",
                FileUtils.readTextFile(mTestFile, 51, "<>"));
        assertEquals(TEST_DATA, FileUtils.readTextFile(mTestFile, 52, "<>"));
        assertEquals(TEST_DATA, FileUtils.readTextFile(mTestFile, 100, "<>"));

        assertEquals("vwxyz", FileUtils.readTextFile(mTestFile, -5, null));
        assertEquals("<>vwxyz", FileUtils.readTextFile(mTestFile, -5, "<>"));
        assertEquals("<>" + TEST_DATA.substring(1, 52),
                FileUtils.readTextFile(mTestFile, -51, "<>"));
        assertEquals(TEST_DATA, FileUtils.readTextFile(mTestFile, -52, "<>"));
        assertEquals(TEST_DATA, FileUtils.readTextFile(mTestFile, -100, "<>"));
    }

    public void testReadTextFileWithZeroLengthFile() throws Exception {
        stageFile(mTestFile, TEST_DATA);
        new FileOutputStream(mTestFile).close();  // Zero out the file
        assertEquals("", FileUtils.readTextFile(mTestFile, 0, null));
        assertEquals("", FileUtils.readTextFile(mTestFile, 1, "<>"));
        assertEquals("", FileUtils.readTextFile(mTestFile, 10, "<>"));
        assertEquals("", FileUtils.readTextFile(mTestFile, -1, "<>"));
        assertEquals("", FileUtils.readTextFile(mTestFile, -10, "<>"));
    }

    public void testContains() throws Exception {
        assertTrue(FileUtils.contains(new File("/"), new File("/moo.txt")));
        assertTrue(FileUtils.contains(new File("/"), new File("/")));

        assertTrue(FileUtils.contains(new File("/sdcard"), new File("/sdcard")));
        assertTrue(FileUtils.contains(new File("/sdcard/"), new File("/sdcard/")));

        assertTrue(FileUtils.contains(new File("/sdcard"), new File("/sdcard/moo.txt")));
        assertTrue(FileUtils.contains(new File("/sdcard/"), new File("/sdcard/moo.txt")));

        assertFalse(FileUtils.contains(new File("/sdcard"), new File("/moo.txt")));
        assertFalse(FileUtils.contains(new File("/sdcard/"), new File("/moo.txt")));

        assertFalse(FileUtils.contains(new File("/sdcard"), new File("/sdcard.txt")));
        assertFalse(FileUtils.contains(new File("/sdcard/"), new File("/sdcard.txt")));
    }

    public void testDeleteOlderEmptyDir() throws Exception {
        FileUtils.deleteOlderFiles(mDir, 10, WEEK_IN_MILLIS);
        assertDirContents();
    }

    public void testDeleteOlderTypical() throws Exception {
        touch("file1", HOUR_IN_MILLIS);
        touch("file2", 1 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        touch("file3", 2 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        touch("file4", 3 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        touch("file5", 4 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        assertTrue(FileUtils.deleteOlderFiles(mDir, 3, DAY_IN_MILLIS));
        assertDirContents("file1", "file2", "file3");
    }

    public void testDeleteOlderInFuture() throws Exception {
        touch("file1", -HOUR_IN_MILLIS);
        touch("file2", HOUR_IN_MILLIS);
        touch("file3", WEEK_IN_MILLIS);
        assertTrue(FileUtils.deleteOlderFiles(mDir, 0, DAY_IN_MILLIS));
        assertDirContents("file1", "file2");

        touch("file1", -HOUR_IN_MILLIS);
        touch("file2", HOUR_IN_MILLIS);
        touch("file3", WEEK_IN_MILLIS);
        assertTrue(FileUtils.deleteOlderFiles(mDir, 0, DAY_IN_MILLIS));
        assertDirContents("file1", "file2");
    }

    public void testDeleteOlderOnlyAge() throws Exception {
        touch("file1", HOUR_IN_MILLIS);
        touch("file2", 1 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        touch("file3", 2 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        touch("file4", 3 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        touch("file5", 4 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        assertTrue(FileUtils.deleteOlderFiles(mDir, 0, DAY_IN_MILLIS));
        assertFalse(FileUtils.deleteOlderFiles(mDir, 0, DAY_IN_MILLIS));
        assertDirContents("file1");
    }

    public void testDeleteOlderOnlyCount() throws Exception {
        touch("file1", HOUR_IN_MILLIS);
        touch("file2", 1 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        touch("file3", 2 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        touch("file4", 3 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        touch("file5", 4 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        assertTrue(FileUtils.deleteOlderFiles(mDir, 2, 0));
        assertFalse(FileUtils.deleteOlderFiles(mDir, 2, 0));
        assertDirContents("file1", "file2");
    }

    public void testValidExtFilename() throws Exception {
        assertTrue(FileUtils.isValidExtFilename("a"));
        assertTrue(FileUtils.isValidExtFilename("foo.bar"));
        assertTrue(FileUtils.isValidExtFilename("foo bar.baz"));
        assertTrue(FileUtils.isValidExtFilename("foo.bar.baz"));
        assertTrue(FileUtils.isValidExtFilename(".bar"));
        assertTrue(FileUtils.isValidExtFilename("foo~!@#$%^&*()_[]{}+bar"));

        assertFalse(FileUtils.isValidExtFilename(null));
        assertFalse(FileUtils.isValidExtFilename("."));
        assertFalse(FileUtils.isValidExtFilename("../foo"));
        assertFalse(FileUtils.isValidExtFilename("/foo"));

        assertEquals(".._foo", FileUtils.buildValidExtFilename("../foo"));
        assertEquals("_foo", FileUtils.buildValidExtFilename("/foo"));
        assertEquals("foo_bar", FileUtils.buildValidExtFilename("foo\0bar"));
        assertEquals(".foo", FileUtils.buildValidExtFilename(".foo"));
        assertEquals("foo.bar", FileUtils.buildValidExtFilename("foo.bar"));
    }

    public void testValidFatFilename() throws Exception {
        assertTrue(FileUtils.isValidFatFilename("a"));
        assertTrue(FileUtils.isValidFatFilename("foo bar.baz"));
        assertTrue(FileUtils.isValidFatFilename("foo.bar.baz"));
        assertTrue(FileUtils.isValidFatFilename(".bar"));
        assertTrue(FileUtils.isValidFatFilename("foo.bar"));
        assertTrue(FileUtils.isValidFatFilename("foo bar"));
        assertTrue(FileUtils.isValidFatFilename("foo+bar"));
        assertTrue(FileUtils.isValidFatFilename("foo,bar"));

        assertFalse(FileUtils.isValidFatFilename("foo*bar"));
        assertFalse(FileUtils.isValidFatFilename("foo?bar"));
        assertFalse(FileUtils.isValidFatFilename("foo<bar"));
        assertFalse(FileUtils.isValidFatFilename(null));
        assertFalse(FileUtils.isValidFatFilename("."));
        assertFalse(FileUtils.isValidFatFilename("../foo"));
        assertFalse(FileUtils.isValidFatFilename("/foo"));

        assertEquals(".._foo", FileUtils.buildValidFatFilename("../foo"));
        assertEquals("_foo", FileUtils.buildValidFatFilename("/foo"));
        assertEquals(".foo", FileUtils.buildValidFatFilename(".foo"));
        assertEquals("foo.bar", FileUtils.buildValidFatFilename("foo.bar"));
        assertEquals("foo_bar__baz", FileUtils.buildValidFatFilename("foo?bar**baz"));
    }

    private void touch(String name, long age) throws Exception {
        final File file = new File(mDir, name);
        file.createNewFile();
        file.setLastModified(System.currentTimeMillis() - age);
    }

    private void stageFile(File file, String data) throws Exception {
        FileWriter writer = new FileWriter(file);
        try {
            writer.write(data, 0, data.length());
        } finally {
            writer.close();
        }
    }

    private void assertDirContents(String... expected) {
        final HashSet<String> expectedSet = Sets.newHashSet(expected);
        String[] actual = mDir.list();
        if (actual == null) actual = new String[0];

        assertEquals(
                "Expected " + Arrays.toString(expected) + " but actual " + Arrays.toString(actual),
                expected.length, actual.length);
        for (String actualFile : actual) {
            assertTrue("Unexpected actual file " + actualFile, expectedSet.contains(actualFile));
        }
    }
}
