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

import java.io.UnsupportedEncodingException;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import android.test.suitebuilder.annotation.LargeTest;

public class DeflateTest extends TestCase {

    @LargeTest
    public void testDeflate() throws Exception {
        simpleTest();

        bigTest(0, 1738149618);
        bigTest(1, 934350518);
        bigTest(2, -532869390);
    }

    /*
     * Simple inflate/deflate test, taken from the reference docs for the
     * Inflater/Deflater classes.
     */
    private void simpleTest()
            throws UnsupportedEncodingException, DataFormatException {
        // Encode a String into bytes
        String inputString = "blahblahblah??";
        byte[] input = inputString.getBytes("UTF-8");

        // Compress the bytes
        byte[] output = new byte[100];
        Deflater compresser = new Deflater();
        compresser.setInput(input);
        compresser.finish();
        int compressedDataLength = compresser.deflate(output);

        // Decompress the bytes
        Inflater decompresser = new Inflater();
        decompresser.setInput(output, 0, compressedDataLength);
        byte[] result = new byte[100];
        int resultLength = decompresser.inflate(result);

        // Decode the bytes into a String
        String outputString = new String(result, 0, resultLength, "UTF-8");

        assertEquals(inputString, outputString);
        assertEquals(compresser.getAdler(), decompresser.getAdler());

        decompresser.end();
    }

    /*
     * "step" determines how compressible the data is.
     *
     * Note we must set "nowrap" to false, or the Adler-32 doesn't get
     * computed.
     */
    private void bigTest(int step, int expectedAdler)
            throws UnsupportedEncodingException, DataFormatException {
        byte[] input = new byte[128 * 1024];
        byte[] comp = new byte[128 * 1024 + 512];
        byte[] output = new byte[128 * 1024 + 512];
        Inflater inflater = new Inflater(false);
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, false);

        createSample(input, step);

        compress(deflater, input, comp);
        expand(inflater, comp, (int) deflater.getBytesWritten(), output);

        assertEquals(inflater.getBytesWritten(), input.length);
        assertEquals(deflater.getAdler(), inflater.getAdler());
        assertEquals(deflater.getAdler(), expectedAdler);
    }

    /*
     * Create a large data sample.
     * stepStep = 0 --> >99% compression
     * stepStep = 1 --> ~30% compression
     * stepStep = 2 --> no compression
     */
    private void createSample(byte[] sample, int stepStep) {
        byte val, step;
        int i, j, offset;

        assertTrue(sample.length >= 128 * 1024);

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
    }

    private static final int LOCAL_BUF_SIZE = 256;

    /*
     * Compress all data in "in" to "out".  We use a small window on input
     * and output to exercise that part of the code.
     *
     * It's the caller's responsibility to ensure that "out" has enough
     * space.
     */
    private void compress(Deflater deflater, byte[] inBuf, byte[] outBuf) {
        int inCount = inBuf.length;        // use all
        int inPosn;
        int outPosn;

        inPosn = outPosn = 0;

        //System.out.println("### starting compress");

        while (!deflater.finished()) {
            int want = -1, got;

            // only read if the input buffer is empty
            if (deflater.needsInput() && inCount != 0) {
                want = (inCount < LOCAL_BUF_SIZE) ? inCount : LOCAL_BUF_SIZE;

                deflater.setInput(inBuf, inPosn, want);

                inCount -= want;
                inPosn += want;
                if (inCount == 0) {
                    deflater.finish();
                }
            }

            // deflate to current position in output buffer
            int compCount;

            compCount = deflater.deflate(outBuf, outPosn, LOCAL_BUF_SIZE);
            outPosn += compCount;

            //System.out.println("Compressed " + want + ", output " + compCount);
        }
    }

    /*
     * Expand data from "inBuf" to "outBuf".  Uses a small window to better
     * exercise the code.
     */
    private void expand(Inflater inflater, byte[] inBuf, int inCount,
            byte[] outBuf) throws DataFormatException {
        int inPosn;
        int outPosn;

        inPosn = outPosn = 0;

        //System.out.println("### starting expand, inCount is " + inCount);

        while (!inflater.finished()) {
            int want = -1, got;

            // only read if the input buffer is empty
            if (inflater.needsInput() && inCount != 0) {
                want = (inCount < LOCAL_BUF_SIZE) ? inCount : LOCAL_BUF_SIZE;

                inflater.setInput(inBuf, inPosn, want);

                inCount -= want;
                inPosn += want;
            }

            // inflate to current position in output buffer
            int compCount;

            compCount = inflater.inflate(outBuf, outPosn, LOCAL_BUF_SIZE);
            outPosn += compCount;

            //System.out.println("Expanded " + want + ", output " + compCount);
        }
    }
}

