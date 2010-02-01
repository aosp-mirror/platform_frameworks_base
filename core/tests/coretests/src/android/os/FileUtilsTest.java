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

import android.content.Context;
import android.os.FileUtils;
import android.os.FileUtils.FileStatus;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

import junit.framework.Assert;

public class FileUtilsTest extends AndroidTestCase {
    private static final String TEST_DATA =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private File mTestFile;
    private File mCopyFile;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        File testDir = getContext().getDir("testing", Context.MODE_PRIVATE);
        mTestFile = new File(testDir, "test.file");
        mCopyFile = new File(testDir, "copy.file");
        FileWriter writer = new FileWriter(mTestFile);
        try {
            writer.write(TEST_DATA, 0, TEST_DATA.length());
        } finally {
            writer.close();
        }
    }

    @Override
    protected void tearDown() throws Exception {
        if (mTestFile.exists()) mTestFile.delete();
        if (mCopyFile.exists()) mCopyFile.delete();
    }

    @LargeTest
    public void testGetFileStatus() {
        final byte[] MAGIC = { 0xB, 0xE, 0x0, 0x5 };

        try {
            // truncate test file and write MAGIC (4 bytes) to it.
            FileOutputStream os = new FileOutputStream(mTestFile, false);
            os.write(MAGIC, 0, 4);
            os.flush();
            os.close();
        } catch (FileNotFoundException e) {
            Assert.fail("File was removed durning test" + e);
        } catch (IOException e) {
            Assert.fail("Unexpected IOException: " + e);
        }
        
        Assert.assertTrue(mTestFile.exists());
        Assert.assertTrue(FileUtils.getFileStatus(mTestFile.getPath(), null));
        
        FileStatus status1 = new FileStatus();
        FileUtils.getFileStatus(mTestFile.getPath(), status1);
        
        Assert.assertEquals(4, status1.size);
        
        // Sleep for at least one second so that the modification time will be different.
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
        }

        try {
            // append so we don't change the creation time.
            FileOutputStream os = new FileOutputStream(mTestFile, true);
            os.write(MAGIC, 0, 4);
            os.flush();
            os.close();
        } catch (FileNotFoundException e) {
            Assert.fail("File was removed durning test" + e);
        } catch (IOException e) {
            Assert.fail("Unexpected IOException: " + e);
        }
        
        FileStatus status2 = new FileStatus();
        FileUtils.getFileStatus(mTestFile.getPath(), status2);
        
        Assert.assertEquals(8, status2.size);
        Assert.assertTrue(status2.mtime > status1.mtime);
        
        mTestFile.delete();
        
        Assert.assertFalse(mTestFile.exists());
        Assert.assertFalse(FileUtils.getFileStatus(mTestFile.getPath(), null));
    }

    // TODO: test setPermissions(), getPermissions()

    @MediumTest
    public void testCopyFile() throws Exception {
        assertFalse(mCopyFile.exists());
        FileUtils.copyFile(mTestFile, mCopyFile);
        assertTrue(mCopyFile.exists());
        assertEquals(TEST_DATA, FileUtils.readTextFile(mCopyFile, 0, null));
    }

    @MediumTest
    public void testCopyToFile() throws Exception {
        final String s = "Foo Bar";
        assertFalse(mCopyFile.exists());
        FileUtils.copyToFile(new ByteArrayInputStream(s.getBytes()), mCopyFile);        assertTrue(mCopyFile.exists());
        assertEquals(s, FileUtils.readTextFile(mCopyFile, 0, null));
    }

    @MediumTest
    public void testIsFilenameSafe() throws Exception {
        assertTrue(FileUtils.isFilenameSafe(new File("foobar")));
        assertTrue(FileUtils.isFilenameSafe(new File("a_b-c=d.e/0,1+23")));
        assertFalse(FileUtils.isFilenameSafe(new File("foo*bar")));
        assertFalse(FileUtils.isFilenameSafe(new File("foo\nbar")));
    }

    @MediumTest
    public void testReadTextFile() throws Exception {
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

    @MediumTest
    public void testReadTextFileWithZeroLengthFile() throws Exception {
        new FileOutputStream(mTestFile).close();  // Zero out the file
        assertEquals("", FileUtils.readTextFile(mTestFile, 0, null));
        assertEquals("", FileUtils.readTextFile(mTestFile, 1, "<>"));
        assertEquals("", FileUtils.readTextFile(mTestFile, 10, "<>"));
        assertEquals("", FileUtils.readTextFile(mTestFile, -1, "<>"));
        assertEquals("", FileUtils.readTextFile(mTestFile, -10, "<>"));
    }
}
