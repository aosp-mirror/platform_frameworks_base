/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.os;

import static org.junit.Assert.assertThrows;

import android.compat.testing.PlatformCompatChangeRule;

import androidx.test.runner.AndroidJUnit4;

import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Test SafeZipPathCallback.
 */
@RunWith(AndroidJUnit4.class)
public class SafeZipPathValidatorCallbackTest {
    @Rule
    public TestRule mCompatChangeRule = new PlatformCompatChangeRule();

    @Before
    public void setUp() {
        RuntimeInit.initZipPathValidatorCallback();
    }

    @Test
    @EnableCompatChanges({SafeZipPathValidatorCallback.VALIDATE_ZIP_PATH_FOR_PATH_TRAVERSAL})
    public void testNewZipFile_whenZipFileHasDangerousEntriesAndChangeEnabled_throws()
            throws Exception {
        final String[] dangerousEntryNames = {
            "../foo.bar",
            "foo/../bar.baz",
            "foo/../../bar.baz",
            "foo.bar/..",
            "foo.bar/../",
            "..",
            "../",
            "/foo",
        };
        for (String entryName : dangerousEntryNames) {
            final File tempFile = File.createTempFile("smdc", "zip");
            try {
                writeZipFileOutputStreamWithEmptyEntry(tempFile, entryName);

                assertThrows(
                        "ZipException expected for entry: " + entryName,
                        ZipException.class,
                        () -> {
                            new ZipFile(tempFile);
                        });
            } finally {
                tempFile.delete();
            }
        }
    }

    @Test
    @EnableCompatChanges({SafeZipPathValidatorCallback.VALIDATE_ZIP_PATH_FOR_PATH_TRAVERSAL})
    public void
            testZipInputStreamGetNextEntry_whenZipFileHasDangerousEntriesAndChangeEnabled_throws()
                    throws Exception {
        final String[] dangerousEntryNames = {
            "../foo.bar",
            "foo/../bar.baz",
            "foo/../../bar.baz",
            "foo.bar/..",
            "foo.bar/../",
            "..",
            "../",
            "/foo",
        };
        for (String entryName : dangerousEntryNames) {
            byte[] badZipBytes = getZipBytesFromZipOutputStreamWithEmptyEntry(entryName);
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(badZipBytes))) {
                assertThrows(
                        "ZipException expected for entry: " + entryName,
                        ZipException.class,
                        () -> {
                            zis.getNextEntry();
                        });
            }
        }
    }

    @Test
    @EnableCompatChanges({SafeZipPathValidatorCallback.VALIDATE_ZIP_PATH_FOR_PATH_TRAVERSAL})
    public void testNewZipFile_whenZipFileHasNormalEntriesAndChangeEnabled_doesNotThrow()
            throws Exception {
        final String[] normalEntryNames = {
            "foo", "foo.bar", "foo..bar",
        };
        for (String entryName : normalEntryNames) {
            final File tempFile = File.createTempFile("smdc", "zip");
            try {
                writeZipFileOutputStreamWithEmptyEntry(tempFile, entryName);
                try {
                    new ZipFile((tempFile));
                } catch (ZipException e) {
                    throw new AssertionError("ZipException not expected for entry: " + entryName);
                }
            } finally {
                tempFile.delete();
            }
        }
    }

    @Test
    @DisableCompatChanges({SafeZipPathValidatorCallback.VALIDATE_ZIP_PATH_FOR_PATH_TRAVERSAL})
    public void
            testZipInputStreamGetNextEntry_whenZipFileHasNormalEntriesAndChangeEnabled_doesNotThrow()
                    throws Exception {
        final String[] normalEntryNames = {
            "foo", "foo.bar", "foo..bar",
        };
        for (String entryName : normalEntryNames) {
            byte[] zipBytes = getZipBytesFromZipOutputStreamWithEmptyEntry(entryName);
            try {
                ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes));
                zis.getNextEntry();
            } catch (ZipException e) {
                throw new AssertionError("ZipException not expected for entry: " + entryName);
            }
        }
    }

    @Test
    @DisableCompatChanges({SafeZipPathValidatorCallback.VALIDATE_ZIP_PATH_FOR_PATH_TRAVERSAL})
    public void
            testNewZipFile_whenZipFileHasNormalAndDangerousEntriesAndChangeDisabled_doesNotThrow()
                    throws Exception {
        final String[] entryNames = {
            "../foo.bar",
            "foo/../bar.baz",
            "foo/../../bar.baz",
            "foo.bar/..",
            "foo.bar/../",
            "..",
            "../",
            "/foo",
            "foo",
            "foo.bar",
            "foo..bar",
        };
        for (String entryName : entryNames) {
            final File tempFile = File.createTempFile("smdc", "zip");
            try {
                writeZipFileOutputStreamWithEmptyEntry(tempFile, entryName);
                try {
                    new ZipFile((tempFile));
                } catch (ZipException e) {
                    throw new AssertionError("ZipException not expected for entry: " + entryName);
                }
            } finally {
                tempFile.delete();
            }
        }
    }

    @Test
    @DisableCompatChanges({SafeZipPathValidatorCallback.VALIDATE_ZIP_PATH_FOR_PATH_TRAVERSAL})
    public void
            testZipInputStreamGetNextEntry_whenZipFileHasNormalAndDangerousEntriesAndChangeDisabled_doesNotThrow()
                    throws Exception {
        final String[] entryNames = {
            "../foo.bar",
            "foo/../bar.baz",
            "foo/../../bar.baz",
            "foo.bar/..",
            "foo.bar/../",
            "..",
            "../",
            "/foo",
            "foo",
            "foo.bar",
            "foo..bar",
        };
        for (String entryName : entryNames) {
            byte[] zipBytes = getZipBytesFromZipOutputStreamWithEmptyEntry(entryName);
            try {
                ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes));
                zis.getNextEntry();
            } catch (ZipException e) {
                throw new AssertionError("ZipException not expected for entry: " + entryName);
            }
        }
    }

    private void writeZipFileOutputStreamWithEmptyEntry(File tempFile, String entryName)
            throws IOException {
        FileOutputStream tempFileStream = new FileOutputStream(tempFile);
        writeZipOutputStreamWithEmptyEntry(tempFileStream, entryName);
        tempFileStream.close();
    }

    private byte[] getZipBytesFromZipOutputStreamWithEmptyEntry(String entryName)
            throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        writeZipOutputStreamWithEmptyEntry(bos, entryName);
        return bos.toByteArray();
    }

    private void writeZipOutputStreamWithEmptyEntry(OutputStream os, String entryName)
            throws IOException {
        ZipOutputStream zos = new ZipOutputStream(os);
        ZipEntry entry = new ZipEntry(entryName);
        zos.putNextEntry(entry);
        zos.write(new byte[2]);
        zos.closeEntry();
        zos.close();
    }
}
