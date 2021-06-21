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

import static android.os.FileUtils.convertToModernFd;
import static android.os.FileUtils.roundStorageSize;
import static android.os.FileUtils.translateModeAccessToPosix;
import static android.os.FileUtils.translateModePfdToPosix;
import static android.os.FileUtils.translateModePosixToPfd;
import static android.os.FileUtils.translateModePosixToString;
import static android.os.FileUtils.translateModeStringToPosix;
import static android.os.ParcelFileDescriptor.MODE_APPEND;
import static android.os.ParcelFileDescriptor.MODE_CREATE;
import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;
import static android.os.ParcelFileDescriptor.MODE_READ_WRITE;
import static android.os.ParcelFileDescriptor.MODE_TRUNCATE;
import static android.os.ParcelFileDescriptor.MODE_WRITE_ONLY;
import static android.system.OsConstants.F_OK;
import static android.system.OsConstants.O_APPEND;
import static android.system.OsConstants.O_CREAT;
import static android.system.OsConstants.O_RDONLY;
import static android.system.OsConstants.O_RDWR;
import static android.system.OsConstants.O_TRUNC;
import static android.system.OsConstants.O_WRONLY;
import static android.system.OsConstants.R_OK;
import static android.system.OsConstants.W_OK;
import static android.system.OsConstants.X_OK;
import static android.text.format.DateUtils.DAY_IN_MILLIS;
import static android.text.format.DateUtils.HOUR_IN_MILLIS;
import static android.text.format.DateUtils.WEEK_IN_MILLIS;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.os.FileUtils.MemoryPipe;
import android.provider.DocumentsContract.Document;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import libcore.io.Streams;

import com.google.android.collect.Sets;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;

@RunWith(AndroidJUnit4.class)
public class FileUtilsTest {
    private static final String TEST_DATA =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private File mDir;
    private File mTestFile;
    private File mCopyFile;
    private File mTarget;

    private final int[] DATA_SIZES = { 32, 32_000, 32_000_000 };

    private Context getContext() {
        return InstrumentationRegistry.getContext();
    }

    @Before
    public void setUp() throws Exception {
        mDir = getContext().getDir("testing", Context.MODE_PRIVATE);
        mTestFile = new File(mDir, "test.file");
        mCopyFile = new File(mDir, "copy.file");

        mTarget = getContext().getFilesDir();
        FileUtils.deleteContents(mTarget);
    }

    @After
    public void tearDown() throws Exception {
        FileUtils.deleteContents(mDir);
        FileUtils.deleteContents(mTarget);
    }

    // TODO: test setPermissions(), getPermissions()

    @Test
    public void testCopyFile() throws Exception {
        writeFile(mTestFile, TEST_DATA);
        assertFalse(mCopyFile.exists());
        FileUtils.copyFile(mTestFile, mCopyFile);
        assertTrue(mCopyFile.exists());
        assertEquals(TEST_DATA, FileUtils.readTextFile(mCopyFile, 0, null));
    }

    @Test
    public void testCopyToFile() throws Exception {
        final String s = "Foo Bar";
        assertFalse(mCopyFile.exists());
        FileUtils.copyToFile(new ByteArrayInputStream(s.getBytes()), mCopyFile);
        assertTrue(mCopyFile.exists());
        assertEquals(s, FileUtils.readTextFile(mCopyFile, 0, null));
    }

    @Test
    public void testCopy_FileToFile() throws Exception {
        for (int size : DATA_SIZES) {
            final File src = new File(mTarget, "src");
            final File dest = new File(mTarget, "dest");

            byte[] expected = new byte[size];
            byte[] actual = new byte[size];
            new Random().nextBytes(expected);
            writeFile(src, expected);

            try (FileInputStream in = new FileInputStream(src);
                    FileOutputStream out = new FileOutputStream(dest)) {
                FileUtils.copy(in, out);
            }

            actual = readFile(dest);
            assertArrayEquals(expected, actual);
        }
    }

    @Test
    public void testCopy_FileToPipe() throws Exception {
        for (int size : DATA_SIZES) {
            final File src = new File(mTarget, "src");

            byte[] expected = new byte[size];
            byte[] actual = new byte[size];
            new Random().nextBytes(expected);
            writeFile(src, expected);

            try (FileInputStream in = new FileInputStream(src);
                    MemoryPipe out = MemoryPipe.createSink(actual)) {
                FileUtils.copy(in.getFD(), out.getFD());
                out.join();
            }

            assertArrayEquals(expected, actual);
        }
    }

    @Test
    public void testCopy_PipeToFile() throws Exception {
        for (int size : DATA_SIZES) {
            final File dest = new File(mTarget, "dest");

            byte[] expected = new byte[size];
            byte[] actual = new byte[size];
            new Random().nextBytes(expected);

            try (MemoryPipe in = MemoryPipe.createSource(expected);
                    FileOutputStream out = new FileOutputStream(dest)) {
                FileUtils.copy(in.getFD(), out.getFD());
            }

            actual = readFile(dest);
            assertArrayEquals(expected, actual);
        }
    }

    @Test
    public void testCopy_PipeToPipe() throws Exception {
        for (int size : DATA_SIZES) {
            byte[] expected = new byte[size];
            byte[] actual = new byte[size];
            new Random().nextBytes(expected);

            try (MemoryPipe in = MemoryPipe.createSource(expected);
                    MemoryPipe out = MemoryPipe.createSink(actual)) {
                FileUtils.copy(in.getFD(), out.getFD());
                out.join();
            }

            assertArrayEquals(expected, actual);
        }
    }

    @Test
    public void testCopy_ShortPipeToFile() throws Exception {
        byte[] source = new byte[33_000_000];
        new Random().nextBytes(source);

        for (int size : DATA_SIZES) {
            final File dest = new File(mTarget, "dest");

            byte[] expected = Arrays.copyOf(source, size);
            byte[] actual = new byte[size];

            try (MemoryPipe in = MemoryPipe.createSource(source);
                    FileOutputStream out = new FileOutputStream(dest)) {
                FileUtils.copy(in.getFD(), out.getFD(), size, null, null, null);
            }

            actual = readFile(dest);
            assertArrayEquals(expected, actual);
        }
    }

    @Test
    public void testCopyFileWithAppend() throws Exception {
        final File src = new File(mTarget, "src");
        final File dest = new File(mTarget, "dest");

        byte[] expected = new byte[10];
        byte[] actual = new byte[10];
        new Random().nextBytes(expected);
        writeFile(src, expected);

        try (FileInputStream in = new FileInputStream(src);
                FileOutputStream out = new FileOutputStream(dest, true /* append */)) {
            // sendfile(2) fails if output fd is opened with O_APPEND, but FileUtils#copy should
            // fallback to userspace copy
            FileUtils.copy(in, out);
        }

        actual = readFile(dest);
        assertArrayEquals(expected, actual);
    }

    @Test
    public void testIsFilenameSafe() throws Exception {
        assertTrue(FileUtils.isFilenameSafe(new File("foobar")));
        assertTrue(FileUtils.isFilenameSafe(new File("a_b-c=d.e/0,1+23")));
        assertFalse(FileUtils.isFilenameSafe(new File("foo*bar")));
        assertFalse(FileUtils.isFilenameSafe(new File("foo\nbar")));
    }

    @Test
    public void testReadTextFile() throws Exception {
        writeFile(mTestFile, TEST_DATA);

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

    @Test
    public void testReadTextFileWithZeroLengthFile() throws Exception {
        writeFile(mTestFile, TEST_DATA);
        new FileOutputStream(mTestFile).close();  // Zero out the file
        assertEquals("", FileUtils.readTextFile(mTestFile, 0, null));
        assertEquals("", FileUtils.readTextFile(mTestFile, 1, "<>"));
        assertEquals("", FileUtils.readTextFile(mTestFile, 10, "<>"));
        assertEquals("", FileUtils.readTextFile(mTestFile, -1, "<>"));
        assertEquals("", FileUtils.readTextFile(mTestFile, -10, "<>"));
    }

    @Test
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

    @Test
    public void testDeleteOlderEmptyDir() throws Exception {
        FileUtils.deleteOlderFiles(mDir, 10, WEEK_IN_MILLIS);
        assertDirContents();
    }

    @Test
    public void testDeleteOlderTypical() throws Exception {
        touch("file1", HOUR_IN_MILLIS);
        touch("file2", 1 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        touch("file3", 2 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        touch("file4", 3 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        touch("file5", 4 * DAY_IN_MILLIS + HOUR_IN_MILLIS);
        assertTrue(FileUtils.deleteOlderFiles(mDir, 3, DAY_IN_MILLIS));
        assertDirContents("file1", "file2", "file3");
    }

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
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

    @Test
    public void testTrimFilename() throws Exception {
        assertEquals("short.txt", FileUtils.trimFilename("short.txt", 16));
        assertEquals("extrem...eme.txt", FileUtils.trimFilename("extremelylongfilename.txt", 16));

        final String unicode = "a\u03C0\u03C0\u03C0\u03C0z";
        assertEquals("a\u03C0\u03C0\u03C0\u03C0z", FileUtils.trimFilename(unicode, 10));
        assertEquals("a\u03C0...\u03C0z", FileUtils.trimFilename(unicode, 9));
        assertEquals("a...\u03C0z", FileUtils.trimFilename(unicode, 8));
        assertEquals("a...\u03C0z", FileUtils.trimFilename(unicode, 7));
        assertEquals("a...z", FileUtils.trimFilename(unicode, 6));
    }

    @Test
    public void testBuildUniqueFile_normal() throws Exception {
        assertNameEquals("test.jpg", FileUtils.buildUniqueFile(mTarget, "image/jpeg", "test"));
        assertNameEquals("test.jpg", FileUtils.buildUniqueFile(mTarget, "image/jpeg", "test.jpg"));
        assertNameEquals("test.jpeg", FileUtils.buildUniqueFile(mTarget, "image/jpeg", "test.jpeg"));
        assertNameEquals("TEst.JPeg", FileUtils.buildUniqueFile(mTarget, "image/jpeg", "TEst.JPeg"));
        assertNameEquals("test.png.jpg",
                FileUtils.buildUniqueFile(mTarget, "image/jpeg", "test.png.jpg"));
        assertNameEquals("test.png.jpg",
                FileUtils.buildUniqueFile(mTarget, "image/jpeg", "test.png"));

        assertNameEquals("test.flac", FileUtils.buildUniqueFile(mTarget, "audio/flac", "test"));
        assertNameEquals("test.flac", FileUtils.buildUniqueFile(mTarget, "audio/flac", "test.flac"));
        assertNameEquals("test.flac",
                FileUtils.buildUniqueFile(mTarget, "application/x-flac", "test"));
        assertNameEquals("test.flac",
                FileUtils.buildUniqueFile(mTarget, "application/x-flac", "test.flac"));
    }

    @Test
    public void testBuildUniqueFile_unknown() throws Exception {
        assertNameEquals("test",
                FileUtils.buildUniqueFile(mTarget, "application/octet-stream", "test"));
        assertNameEquals("test.jpg",
                FileUtils.buildUniqueFile(mTarget, "application/octet-stream", "test.jpg"));
        assertNameEquals(".test",
                FileUtils.buildUniqueFile(mTarget, "application/octet-stream", ".test"));

        assertNameEquals("test", FileUtils.buildUniqueFile(mTarget, "lolz/lolz", "test"));
        assertNameEquals("test.lolz", FileUtils.buildUniqueFile(mTarget, "lolz/lolz", "test.lolz"));
    }

    @Test
    public void testBuildUniqueFile_dir() throws Exception {
        assertNameEquals("test", FileUtils.buildUniqueFile(mTarget, Document.MIME_TYPE_DIR, "test"));
        new File(mTarget, "test").mkdir();
        assertNameEquals("test (1)",
                FileUtils.buildUniqueFile(mTarget, Document.MIME_TYPE_DIR, "test"));

        assertNameEquals("test.jpg",
                FileUtils.buildUniqueFile(mTarget, Document.MIME_TYPE_DIR, "test.jpg"));
        new File(mTarget, "test.jpg").mkdir();
        assertNameEquals("test.jpg (1)",
                FileUtils.buildUniqueFile(mTarget, Document.MIME_TYPE_DIR, "test.jpg"));
    }

    @Test
    public void testBuildUniqueFile_increment() throws Exception {
        assertNameEquals("test.jpg", FileUtils.buildUniqueFile(mTarget, "image/jpeg", "test.jpg"));
        new File(mTarget, "test.jpg").createNewFile();
        assertNameEquals("test (1).jpg",
                FileUtils.buildUniqueFile(mTarget, "image/jpeg", "test.jpg"));
        new File(mTarget, "test (1).jpg").createNewFile();
        assertNameEquals("test (2).jpg",
                FileUtils.buildUniqueFile(mTarget, "image/jpeg", "test.jpg"));
    }

    @Test
    public void testBuildUniqueFile_mimeless() throws Exception {
        assertNameEquals("test.jpg", FileUtils.buildUniqueFile(mTarget, "test.jpg"));
        new File(mTarget, "test.jpg").createNewFile();
        assertNameEquals("test (1).jpg", FileUtils.buildUniqueFile(mTarget, "test.jpg"));

        assertNameEquals("test", FileUtils.buildUniqueFile(mTarget, "test"));
        new File(mTarget, "test").createNewFile();
        assertNameEquals("test (1)", FileUtils.buildUniqueFile(mTarget, "test"));

        assertNameEquals("test.foo.bar", FileUtils.buildUniqueFile(mTarget, "test.foo.bar"));
        new File(mTarget, "test.foo.bar").createNewFile();
        assertNameEquals("test.foo (1).bar", FileUtils.buildUniqueFile(mTarget, "test.foo.bar"));
    }

    @Test
    public void testRoundStorageSize() throws Exception {
        final long M128 = 128000000L;
        final long M256 = 256000000L;
        final long M512 = 512000000L;
        final long G1 = 1000000000L;
        final long G2 = 2000000000L;
        final long G16 = 16000000000L;
        final long G32 = 32000000000L;
        final long G64 = 64000000000L;

        assertEquals(M128, roundStorageSize(M128));
        assertEquals(M256, roundStorageSize(M128 + 1));
        assertEquals(M256, roundStorageSize(M256 - 1));
        assertEquals(M256, roundStorageSize(M256));
        assertEquals(M512, roundStorageSize(M256 + 1));
        assertEquals(M512, roundStorageSize(M512 - 1));
        assertEquals(M512, roundStorageSize(M512));
        assertEquals(G1, roundStorageSize(M512 + 1));
        assertEquals(G1, roundStorageSize(G1));
        assertEquals(G2, roundStorageSize(G1 + 1));

        assertEquals(G16, roundStorageSize(G16));
        assertEquals(G32, roundStorageSize(G16 + 1));
        assertEquals(G32, roundStorageSize(G32 - 1));
        assertEquals(G32, roundStorageSize(G32));
        assertEquals(G64, roundStorageSize(G32 + 1));
    }

    @Test
    public void testTranslateMode() throws Exception {
        assertTranslate("r", O_RDONLY, MODE_READ_ONLY);

        assertTranslate("rw", O_RDWR | O_CREAT,
                MODE_READ_WRITE | MODE_CREATE);
        assertTranslate("rwt", O_RDWR | O_CREAT | O_TRUNC,
                MODE_READ_WRITE | MODE_CREATE | MODE_TRUNCATE);
        assertTranslate("rwa", O_RDWR | O_CREAT | O_APPEND,
                MODE_READ_WRITE | MODE_CREATE | MODE_APPEND);

        assertTranslate("w", O_WRONLY | O_CREAT,
                MODE_WRITE_ONLY | MODE_CREATE | MODE_CREATE);
        assertTranslate("wt", O_WRONLY | O_CREAT | O_TRUNC,
                MODE_WRITE_ONLY | MODE_CREATE | MODE_TRUNCATE);
        assertTranslate("wa", O_WRONLY | O_CREAT | O_APPEND,
                MODE_WRITE_ONLY | MODE_CREATE | MODE_APPEND);
    }

    @Test
    public void testMalformedTransate_int() throws Exception {
        try {
            // The non-standard Linux access mode 3 should throw
            // an IllegalArgumentException.
            translateModePosixToPfd(O_RDWR | O_WRONLY);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testMalformedTransate_string() throws Exception {
        try {
            // The non-standard Linux access mode 3 should throw
            // an IllegalArgumentException.
            translateModePosixToString(O_RDWR | O_WRONLY);
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testTranslateMode_Invalid() throws Exception {
        try {
            translateModeStringToPosix("rwx");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            translateModeStringToPosix("");
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void testTranslateMode_Access() throws Exception {
        assertEquals(O_RDONLY, translateModeAccessToPosix(F_OK));
        assertEquals(O_RDONLY, translateModeAccessToPosix(R_OK));
        assertEquals(O_WRONLY, translateModeAccessToPosix(W_OK));
        assertEquals(O_RDWR, translateModeAccessToPosix(R_OK | W_OK));
        assertEquals(O_RDWR, translateModeAccessToPosix(R_OK | W_OK | X_OK));
    }

    @Test
    public void testConvertToModernFd() throws Exception {
        final String nonce = String.valueOf(System.nanoTime());

        final File cameraDir = new File("/storage/emulated/0/DCIM/Camera");
        final File nonCameraDir = new File("/storage/emulated/0/Pictures");
        cameraDir.mkdirs();
        nonCameraDir.mkdirs();

        final File validVideoCameraDir = new File(cameraDir, "validVideo-" + nonce + ".mp4");
        final File validImageCameraDir = new File(cameraDir, "validImage-" + nonce + ".jpg");

        final File validVideoNonCameraDir = new File(nonCameraDir, "validVideo-" + nonce + ".mp4");
        final File validImageNonCameraDir = new File(nonCameraDir, "validImage-" + nonce + ".jpg");

        try {
            FileDescriptor pfdValidVideoCameraDir =
                    ParcelFileDescriptor.open(validVideoCameraDir,
                            MODE_CREATE | MODE_READ_WRITE).getFileDescriptor();
            FileDescriptor pfdValidImageCameraDir =
                    ParcelFileDescriptor.open(validImageCameraDir,
                            MODE_CREATE | MODE_READ_WRITE).getFileDescriptor();

            FileDescriptor pfdValidVideoNonCameraDir =
                    ParcelFileDescriptor.open(validVideoNonCameraDir,
                            MODE_CREATE | MODE_READ_WRITE).getFileDescriptor();
            FileDescriptor pfdValidImageNonCameraDir =
                    ParcelFileDescriptor.open(validImageNonCameraDir,
                            MODE_CREATE | MODE_READ_WRITE).getFileDescriptor();

            assertNotNull(convertToModernFd(pfdValidVideoCameraDir));

            assertNull(convertToModernFd(pfdValidImageCameraDir));
            assertNull(convertToModernFd(pfdValidVideoNonCameraDir));
            assertNull(convertToModernFd(pfdValidImageNonCameraDir));
        } finally {
            validVideoCameraDir.delete();
            validImageCameraDir.delete();
            validVideoNonCameraDir.delete();
            validImageNonCameraDir.delete();
        }
    }

    private static void assertTranslate(String string, int posix, int pfd) {
        assertEquals(posix, translateModeStringToPosix(string));
        assertEquals(string, translateModePosixToString(posix));
        assertEquals(pfd, translateModePosixToPfd(posix));
        assertEquals(posix, translateModePfdToPosix(pfd));
    }

    private static void assertNameEquals(String expected, File actual) {
        assertEquals(expected, actual.getName());
    }

    private void touch(String name, long age) throws Exception {
        final File file = new File(mDir, name);
        file.createNewFile();
        file.setLastModified(System.currentTimeMillis() - age);
    }

    private void writeFile(File file, String data) throws Exception {
        writeFile(file, data.getBytes());
    }

    private void writeFile(File file, byte[] data) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }

    private byte[] readFile(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Streams.copy(in, out);
            return out.toByteArray();
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
