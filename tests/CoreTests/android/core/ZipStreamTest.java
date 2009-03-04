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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import android.test.suitebuilder.annotation.LargeTest;

/**
 * Basic tests for ZipStream
 */
public class ZipStreamTest extends TestCase {

    @LargeTest
    public void testZipStream() throws Exception {
        ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
        createCompressedZip(bytesOut);

        byte[] zipData = bytesOut.toByteArray();

        /*
        FileOutputStream outFile = new FileOutputStream("/tmp/foo.zip");
        outFile.write(zipData, 0, zipData.length);
        outFile.close();
        */

        /*
        FileInputStream inFile = new FileInputStream("/tmp/foo.zip");
        int inputLength = inFile.available();
        zipData = new byte[inputLength];
        if (inFile.read(zipData) != inputLength)
            throw new RuntimeException();
        inFile.close();
        */

        ByteArrayInputStream bytesIn = new ByteArrayInputStream(zipData);
        scanZip(bytesIn);

        bytesOut = new ByteArrayOutputStream();
        createUncompressedZip(bytesOut);

        zipData = bytesOut.toByteArray();

        bytesIn = new ByteArrayInputStream(zipData);
        scanZip(bytesIn);
    }

    /*
     * stepStep == 0 --> >99% compression
     * stepStep == 1 --> ~30% compression
     * stepStep == 2 --> no compression
     */
    private static byte[] makeSampleFile(int stepStep) throws IOException {
        byte[] sample = new byte[128 * 1024];
        byte val, step;
        int i, j, offset;

        val = 0;
        step = 1;
        offset = 0;
        for (i = 0; i < (128 * 1024) / 256; i++) {
            for (j = 0; j < 256; j++) {
                sample[offset++] = val;
                val += step;
            }

            step += stepStep;
        }

        return sample;
    }

    private static void createCompressedZip(ByteArrayOutputStream bytesOut) throws IOException {
        ZipOutputStream out = new ZipOutputStream(bytesOut);
        try {
            int i;

            for (i = 0; i < 3; i++) {
                byte[] input = makeSampleFile(i);
                ZipEntry newEntry = new ZipEntry("file-" + i);

                if (i != 1)
                    newEntry.setComment("this is file " + i);
                out.putNextEntry(newEntry);
                out.write(input, 0, input.length);
                out.closeEntry();
            }

            out.setComment("This is a lovely compressed archive!");
        } finally {
            out.close();
        }
    }

    private static void createUncompressedZip(ByteArrayOutputStream bytesOut) throws IOException {
        ZipOutputStream out = new ZipOutputStream(bytesOut);
        try {
            long[] crcs = {0x205fbff3, 0x906fae57L, 0x2c235131};
            int i;

            for (i = 0; i < 3; i++) {
                byte[] input = makeSampleFile(i);
                ZipEntry newEntry = new ZipEntry("file-" + i);

                if (i != 1)
                    newEntry.setComment("this is file " + i);
                newEntry.setMethod(ZipEntry.STORED);
                newEntry.setSize(128 * 1024);
                newEntry.setCrc(crcs[i]);
                out.putNextEntry(newEntry);
                out.write(input, 0, input.length);
                out.closeEntry();
            }

            out.setComment("This is a lovely, but uncompressed, archive!");
        } finally {
            out.close();
        }
    }

    private static void scanZip(ByteArrayInputStream bytesIn) throws IOException {
        ZipInputStream in = new ZipInputStream(bytesIn);
        try {
            int i;

            for (i = 0; i < 3; i++) {
                ZipEntry entry = in.getNextEntry();
                ByteArrayOutputStream contents = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int len, totalLen = 0;

                while ((len = in.read(buf)) > 0) {
                    contents.write(buf, 0, len);
                    totalLen += len;
                }

                assertEquals(128 * 1024, totalLen);

//                System.out.println("ZipStreamTest: name='" + entry.getName()
//                        + "', zero=" + contents.toByteArray()[0]
//                        + ", tfs=" + contents.toByteArray()[257]
//                        + ", crc=" + Long.toHexString(entry.getCrc()));
            }

            assertNull("should only be three entries", in.getNextEntry());
        } finally {
            in.close();
        }
    }
}

