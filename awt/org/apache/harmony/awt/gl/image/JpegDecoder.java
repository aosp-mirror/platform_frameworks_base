/*
*  Licensed to the Apache Software Foundation (ASF) under one or more
*  contributor license agreements.  See the NOTICE file distributed with
*  this work for additional information regarding copyright ownership.
*  The ASF licenses this file to You under the Apache License, Version 2.0
*  (the "License"); you may not use this file except in compliance with
*  the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
*  Unless required by applicable law or agreed to in writing, software
*  distributed under the License is distributed on an "AS IS" BASIS,
*  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
*  See the License for the specific language governing permissions and
*  limitations under the License.
*/
/**
 * @author Oleg V. Khaschansky
 * @version $Revision$
 */
package org.apache.harmony.awt.gl.image;

import java.awt.image.*;
import java.awt.color.ColorSpace;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.util.Hashtable;

import org.apache.harmony.awt.internal.nls.Messages;

public class JpegDecoder extends ImageDecoder {
    // Only 2 output colorspaces expected. Others are converted into
    // these ones.
    // 1. Grayscale
    public static final int JCS_GRAYSCALE = 1;
    // 2. RGB
    public static final int JCS_RGB = 2;

    // Flags for the consumer, progressive JPEG
    private static final int hintflagsProgressive =
            ImageConsumer.SINGLEFRAME | // JPEG is a static image
            ImageConsumer.TOPDOWNLEFTRIGHT | // This order is only one possible
            ImageConsumer.COMPLETESCANLINES; // Don't deliver incomplete scanlines
    // Flags for the consumer, singlepass JPEG
    private static final int hintflagsSingle =
            ImageConsumer.SINGLEPASS |
            hintflagsProgressive;

    // Buffer for the stream
    private static final int BUFFER_SIZE = 1024;
    private byte buffer[] = new byte[BUFFER_SIZE];

    // 3 possible color models only
    private static ColorModel cmRGB;
    private static ColorModel cmGray;

    // initializes proper field IDs
    private static native void initIDs();

    // Pointer to native structure which store decoding state
    // between subsequent decoding/IO-suspension cycles
    private long hNativeDecoder = 0; // NULL initially

    private boolean headerDone = false;

    // Next 4 members are filled by the native method (decompress).
    // We can simply check if imageWidth is still negative to find
    // out if they are already filled.
    private int imageWidth = -1;
    private int imageHeight = -1;
    private boolean progressive = false;
    private int jpegColorSpace = 0;

    // Stores number of bytes consumed by the native decoder
    private int bytesConsumed = 0;
    // Stores current scanline returned by the decoder
    private int currScanline = 0;

    private ColorModel cm = null;

    static {
        System.loadLibrary("jpegdecoder"); //$NON-NLS-1$

        cmGray = new ComponentColorModel(
                ColorSpace.getInstance(ColorSpace.CS_GRAY),
                false, false,
                Transparency.OPAQUE, DataBuffer.TYPE_BYTE
        );

        // Create RGB color model
        cmRGB = new DirectColorModel(24, 0xFF0000, 0xFF00, 0xFF);

        initIDs();
    }

    public JpegDecoder(DecodingImageSource src, InputStream is) {
        super(src, is);
    }

    /*
    public JpegDecoder(InputStream iStream, ImageConsumer iConsumer) {
    inputStream = iStream;
    consumer = iConsumer;
    }
    */

    /**
     * @return - not NULL if call is successful
     */
    private native Object decode(
            byte[] input,
            int bytesInBuffer,
            long hDecoder);

    private static native void releaseNativeDecoder(long hDecoder);

    @Override
    public void decodeImage() throws IOException {
        try {
            int bytesRead = 0, dataLength = 0;
            boolean eosReached = false;
            int needBytes, offset, bytesInBuffer = 0;
            byte byteOut[] = null;
            int intOut[] = null;
            // Read from the input stream
            for (;;) {
                needBytes = BUFFER_SIZE - bytesInBuffer;
                offset = bytesInBuffer;

                bytesRead = inputStream.read(buffer, offset, needBytes);

                if (bytesRead < 0) {
                    bytesRead = 0;//break;
                    eosReached = true;
                } // Don't break, maybe something left in buffer

                // Keep track on how much bytes left in buffer
                bytesInBuffer += bytesRead;

                // Here we pass overall number of bytes left in the java buffer
                // (bytesInBuffer) since jpeg decoder has its own buffer and consumes
                // as many bytes as it can. If there are any unconsumed bytes
                // it didn't add them to its buffer...
                Object arr = decode(
                        buffer,
                        bytesInBuffer,
                        hNativeDecoder);

                // Keep track on how much bytes left in buffer
                bytesInBuffer -= bytesConsumed;

                if (!headerDone && imageWidth != -1) {
                    returnHeader();
                    headerDone = true;
                }

                if (bytesConsumed < 0) {
                    break; // Error exit
                }

                if (arr instanceof byte[]) {
                    byteOut = (byte[]) arr;
                    dataLength = byteOut.length;
                    returnData(byteOut, currScanline);
                } else if (arr instanceof int[]) {
                    intOut = (int[]) arr;
                    dataLength = intOut.length;
                    returnData(intOut, currScanline);
                } else {
                    dataLength = 0;
                }

                if (hNativeDecoder == 0) {
                    break;
                }

                if (dataLength == 0 && eosReached) {
                    releaseNativeDecoder(hNativeDecoder);
                    break; // Probably image is truncated
                }
            }
            imageComplete(ImageConsumer.STATICIMAGEDONE);
        } catch (IOException e) {
            throw e;
        } finally {
            closeStream();
        }
    }

    public void returnHeader() {
        setDimensions(imageWidth, imageHeight);

        switch (jpegColorSpace) {
            case JCS_GRAYSCALE: cm = cmGray; break;
            case JCS_RGB: cm = cmRGB; break;
            default: 
                // awt.3D=Unknown colorspace
                throw new IllegalArgumentException(Messages.getString("awt.3D")); //$NON-NLS-1$
        }
        setColorModel(cm);

        setHints(progressive ? hintflagsProgressive : hintflagsSingle);

        setProperties(new Hashtable<Object, Object>()); // Empty
    }

    // Send the data to the consumer
    public void returnData(int data[], int currScanLine) {
        // Send 1 or more scanlines to the consumer.
        int numScanlines = data.length / imageWidth;
        if (numScanlines > 0) {
            setPixels(
                    0, currScanLine - numScanlines,
                    imageWidth, numScanlines,
                    cm, data, 0, imageWidth
            );
        }
    }

    public void returnData(byte data[], int currScanLine) {
        int numScanlines = data.length / imageWidth;
        if (numScanlines > 0) {
            setPixels(
                    0, currScanLine - numScanlines,
                    imageWidth, numScanlines,
                    cm, data, 0, imageWidth
            );
        }
    }
}
