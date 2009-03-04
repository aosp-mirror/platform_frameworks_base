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
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import android.test.suitebuilder.annotation.MediumTest;

/**
 * Deflates and inflates some test data with GZipStreams
 */
public class GZIPStreamTest extends TestCase {

    @MediumTest
    public void testGZIPStream() throws Exception {
        ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
        createGZIP(bytesOut);

        byte[] zipData;
        zipData = bytesOut.toByteArray();

        /*
        FileOutputStream outFile = new FileOutputStream("/tmp/foo.gz");
        outFile.write(zipData, 0, zipData.length);
        outFile.close();
        */

        /*
        FileInputStream inFile = new FileInputStream("/tmp/foo.gz");
        int inputLength = inFile.available();
        zipData = new byte[inputLength];
        if (inFile.read(zipData) != inputLength)
            throw new RuntimeException();
        inFile.close();
        */

        ByteArrayInputStream bytesIn = new ByteArrayInputStream(zipData);
        scanGZIP(bytesIn);
    }

    /*
     * stepStep == 0 --> >99% compression
     * stepStep == 1 --> ~30% compression
     * stepStep == 2 --> no compression
     */
    static byte[] makeSampleFile(int stepStep) throws IOException {
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

    static void createGZIP(ByteArrayOutputStream bytesOut) throws IOException {
        GZIPOutputStream out = new GZIPOutputStream(bytesOut);
        try {
            byte[] input = makeSampleFile(1);
            out.write(input, 0, input.length);
            //out.finish();
        } finally {
            out.close();
        }
    }

    static void scanGZIP(ByteArrayInputStream bytesIn) throws IOException {
        GZIPInputStream in = new GZIPInputStream(bytesIn);
        try {
            ByteArrayOutputStream contents = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len, totalLen = 0;

            while ((len = in.read(buf)) > 0) {
                contents.write(buf, 0, len);
                totalLen += len;
            }

            assertEquals(totalLen, 128 * 1024);
        } finally {
            in.close();
        }
    }
}

