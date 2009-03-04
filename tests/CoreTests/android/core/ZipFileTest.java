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

package android.core;

import junit.framework.TestCase;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import android.test.suitebuilder.annotation.MediumTest;


/**
 * Basic tests for ZipFile.
 */
public class ZipFileTest extends TestCase {
    private static final int SAMPLE_SIZE = 128 * 1024;

    @MediumTest
    public void testZipFile() throws Exception {

        File file = File.createTempFile("ZipFileTest", ".zip");
        try {
            // create a test file; assume it's not going to collide w/anything
            FileOutputStream outStream = new FileOutputStream(file);
            createCompressedZip(outStream);
//            System.out.println("CREATED " + file);

            scanZip(file.getPath());
            read2(file.getPath());
        } finally {
            file.delete();
        }
    }

    /*
     * stepStep == 0 --> >99% compression
     * stepStep == 1 --> ~30% compression
     * stepStep == 2 --> no compression
     */
    static byte[] makeSampleFile(int stepStep) throws IOException {
        byte[] sample = new byte[SAMPLE_SIZE];
        byte val, step;
        int i, j, offset;

        val = 0;
        step = 1;
        offset = 0;
        for (i = 0; i < SAMPLE_SIZE / 256; i++) {
            for (j = 0; j < 256; j++) {
                sample[offset++] = val;
                val += step;
            }

            step += stepStep;
        }

        return sample;
    }

    static void createCompressedZip(OutputStream bytesOut) throws IOException {
        ZipOutputStream out = new ZipOutputStream(bytesOut);
        try {
            int i;

            for (i = 0; i < 3; i++) {
                byte[] input = makeSampleFile(i);
                ZipEntry newEntry = new ZipEntry("file-" + i);

                if (i != 1) {
                    newEntry.setComment("this is file " + i);
                }
                out.putNextEntry(newEntry);
                out.write(input, 0, input.length);
                out.closeEntry();
            }

            out.setComment("This is a lovely compressed archive!");
        } finally {
            out.close();
        }
    }

    static void scanZip(String fileName) throws IOException {
        ZipFile zipFile = new ZipFile(fileName);
        Enumeration fileList;
        int idx = 0;

//        System.out.println("Contents of " + zipFile + ":");
        for (fileList = zipFile.entries(); fileList.hasMoreElements();) {
            ZipEntry entry = (ZipEntry) fileList.nextElement();
//            System.out.println("  " + entry.getName());
            assertEquals(entry.getName(), "file-" + idx);
            idx++;
        }

        zipFile.close();
    }

    /*
     * Read compressed data from two different entries at the same time,
     * to verify that the streams aren't getting confused.  If we do
     * something wrong, the inflater will choke and throw a ZipException.
     *
     * This doesn't test synchronization in multi-threaded use.
     */
    static void read2(String fileName) throws IOException {
        ZipFile zipFile;
        ZipEntry entry1, entry2;
        byte buf[] = new byte[16384];
        InputStream stream1, stream2;
        int len, totalLen1, totalLen2;

        /* use file-1 and file-2 because the compressed data is large */
        zipFile = new ZipFile(fileName);
        entry1 = zipFile.getEntry("file-1");
        entry2 = zipFile.getEntry("file-2");

        /* make sure we got the right thing */
        assertEquals("file-1", entry1.getName());
        assertEquals("file-2", entry2.getName());

        /* create streams */
        stream1 = zipFile.getInputStream(entry1);
        stream2 = zipFile.getInputStream(entry2);

        /*
         * Read a piece of file #1.
         */
        totalLen1 = stream1.read(buf);
        assertTrue("initial read failed on #1", totalLen1 >= 0);

        /*
         * Read a piece of file #2.
         */
        totalLen2 = stream2.read(buf);
        assertTrue("initial read failed on #2", totalLen2 >= 0);

        /*
         * Read the rest of file #1, and close the stream.
         *
         * If our streams are crossed up, we'll fail here.
         */
        while ((len = stream1.read(buf)) > 0) {
            totalLen1 += len;
        }
        assertEquals(SAMPLE_SIZE, totalLen1);
        stream1.close();

        /*
         * Read the rest of file #2, and close the stream.
         */
        while ((len = stream2.read(buf)) > 0) {
            totalLen2 += len;
        }
        assertEquals(SAMPLE_SIZE, totalLen2);
        stream2.close();

        /*
         * Open a new one.
         */
        stream1 = zipFile.getInputStream(zipFile.getEntry("file-0"));

        /*
         * Close the ZipFile. According to the RI, none if its InputStreams can
         * be read after this point.
         */
        zipFile.close();
        
        Exception error = null;
        try {
            stream1.read(buf);
        } catch (Exception ex) {
            error = ex;
        }
        
        assertNotNull("ZipFile shouldn't allow reading of closed files.", error);
    }
}

