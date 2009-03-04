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
/*
* Created on 27.01.2005
*/
package org.apache.harmony.awt.gl.image;

import java.awt.image.ColorModel;
import java.awt.image.ImageConsumer;
import java.awt.image.IndexColorModel;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;

public class GifDecoder extends ImageDecoder {
    // initializes proper field IDs
    private static native void initIDs();

    static {
        System.loadLibrary("gl"); //$NON-NLS-1$
        initIDs();
    }

    // ImageConsumer hints: common
    private static final int baseHints =
            ImageConsumer.SINGLEPASS | ImageConsumer.COMPLETESCANLINES |
            ImageConsumer.SINGLEFRAME;
    // ImageConsumer hints: interlaced
    private static final int interlacedHints =
            baseHints | ImageConsumer.RANDOMPIXELORDER;

    // Impossible color value - no translucent pixels allowed
    static final int IMPOSSIBLE_VALUE = 0x0FFFFFFF;

    // I/O buffer
    private static final int BUFFER_SIZE = 1024;
    private byte buffer[] = new byte[BUFFER_SIZE];

    GifDataStream gifDataStream = new GifDataStream();
    GifGraphicBlock currBlock;

    // Pointer to native structure which store decoding state
    // between subsequent decoding/IO-suspension cycles
    private long hNativeDecoder; // NULL initially

    // Number of bytes eaten by the native decoder
    private int bytesConsumed;

    private boolean consumersPrepared;
    private Hashtable<String, String> properties = new Hashtable<String, String>();

    // Could be set up by java code or native method when
    // transparent pixel index changes or local color table encountered
    private boolean forceRGB;

    private byte screenBuffer[];
    private int screenRGBBuffer[];

    public GifDecoder(DecodingImageSource src, InputStream is) {
        super(src, is);
    }

    private static native int[] toRGB(byte imageData[], byte colormap[], int transparentColor);

    private static native void releaseNativeDecoder(long hDecoder);

    private native int decode(
            byte input[],
            int bytesInBuffer,
            long hDecoder,
            GifDataStream dataStream,
            GifGraphicBlock currBlock
            );

    private int[] getScreenRGBBuffer() {
        if (screenRGBBuffer == null) {
            if (screenBuffer != null) {
                int transparentColor =
                        gifDataStream.logicalScreen.globalColorTable.cm.getTransparentPixel();
                transparentColor = transparentColor > 0 ? transparentColor : IMPOSSIBLE_VALUE;
                screenRGBBuffer =
                        toRGB(
                                screenBuffer,
                                gifDataStream.logicalScreen.globalColorTable.colors,
                                transparentColor
                        );
            } else {
                int size = gifDataStream.logicalScreen.logicalScreenHeight *
                        gifDataStream.logicalScreen.logicalScreenWidth;
                screenRGBBuffer = new int[size];
            }
        }

        return screenRGBBuffer;
    }

    private void prepareConsumers() {
        GifLogicalScreen gls = gifDataStream.logicalScreen;
        setDimensions(gls.logicalScreenWidth,
                gls.logicalScreenHeight);
        setProperties(properties);

        currBlock = gifDataStream.graphicBlocks.get(0);
        if (forceRGB) {
            setColorModel(ColorModel.getRGBdefault());
        } else {
            setColorModel(gls.globalColorTable.getColorModel(currBlock.transparentColor));
        }

        // Fill screen buffer with the background or transparent color
        if (forceRGB) {
            int fillColor = 0xFF000000;
            if (gls.backgroundColor != IMPOSSIBLE_VALUE) {
                fillColor = gls.backgroundColor;
            }

            Arrays.fill(getScreenRGBBuffer(), fillColor);
        } else {
            int fillColor = 0;

            if (gls.backgroundColor != IMPOSSIBLE_VALUE) {
                fillColor = gls.backgroundColor;
            } else {
                fillColor = gls.globalColorTable.cm.getTransparentPixel();
            }

            screenBuffer = new byte[gls.logicalScreenHeight*gls.logicalScreenWidth];
            Arrays.fill(screenBuffer, (byte) fillColor);
        }

        setHints(interlacedHints); // XXX - always random pixel order
    }

    @Override
    public void decodeImage() throws IOException {
        try {
            int bytesRead = 0;
            int needBytes, offset, bytesInBuffer = 0;
            boolean eosReached = false;
            GifGraphicBlock blockToDispose = null;

            // Create new graphic block
            if (currBlock == null) {
                currBlock = new GifGraphicBlock();
                gifDataStream.graphicBlocks.add(currBlock);
            }

            // Read from the input stream
            for (;;) {
                needBytes = BUFFER_SIZE - bytesInBuffer;
                offset = bytesInBuffer;

                bytesRead = inputStream.read(buffer, offset, needBytes);

                if (bytesRead < 0) {
                    eosReached = true;
                    bytesRead = 0;
                } // Don't break, maybe something left in buffer

                // Keep track on how much bytes left in buffer
                bytesInBuffer += bytesRead;

                // Here we pass number of new bytes read from the input stream (bytesRead)
                // since native decoder uses java buffer and doesn't have its own
                // buffer. So it adds this number to the number of bytes left
                // in buffer from the previous call.
                int numLines = decode(
                        buffer,
                        bytesRead,
                        hNativeDecoder,
                        gifDataStream,
                        currBlock);

                // Keep track on how much bytes left in buffer
                bytesInBuffer -= bytesConsumed;

                if (
                        !consumersPrepared &&
                        gifDataStream.logicalScreen.completed &&
                        gifDataStream.logicalScreen.globalColorTable.completed &&
                        (currBlock.imageData != null || // Have transparent pixel filled
                        currBlock.rgbImageData != null)
                ) {
                    prepareConsumers();
                    consumersPrepared = true;
                }

                if (bytesConsumed < 0) {
                    break; // Error exit
                }

                if (currBlock != null) {
                    if (numLines != 0) {
                        // Dispose previous image only before showing next
                        if (blockToDispose != null) {
                            blockToDispose.dispose();
                            blockToDispose = null;
                        }

                        currBlock.sendNewData(this, numLines);
                    }

                    if (currBlock.completed && hNativeDecoder != 0) {
                        blockToDispose = currBlock; // Dispose only before showing new pixels
                        currBlock = new GifGraphicBlock();
                        gifDataStream.graphicBlocks.add(currBlock);
                    }
                }

                if (hNativeDecoder == 0) {
                    break;
                }

                if (eosReached && numLines == 0) { // Maybe image is truncated...
                    releaseNativeDecoder(hNativeDecoder);
                    break;
                }
            }
        } finally {
            closeStream();
        }

        // Here all animation goes
        // Repeat image loopCount-1 times or infinitely if loopCount = 0
        if (gifDataStream.loopCount != 1) {
            if (currBlock.completed == false) {
                gifDataStream.graphicBlocks.remove(currBlock);
            }

            int numFrames = gifDataStream.graphicBlocks.size();
            // At first last block will be disposed
            GifGraphicBlock gb =
                    gifDataStream.graphicBlocks.get(numFrames-1);

            ImageLoader.beginAnimation();

            while (gifDataStream.loopCount != 1) {
                if (gifDataStream.loopCount != 0) {
                    gifDataStream.loopCount--;
                }

                // Show all frames
                for (int i=0; i<numFrames; i++) {
                    gb.dispose();
                    gb = gifDataStream.graphicBlocks.get(i);

                    // Show one frame
                    if (forceRGB) {
                        setPixels(
                                gb.imageLeft,
                                gb.imageTop,
                                gb.imageWidth,
                                gb.imageHeight,
                                ColorModel.getRGBdefault(),
                                gb.getRgbImageData(),
                                0,
                                gb.imageWidth
                        );
                    } else {
                        setPixels(
                                gb.imageLeft,
                                gb.imageTop,
                                gb.imageWidth,
                                gb.imageHeight,
                                null,
                                gb.imageData,
                                0,
                                gb.imageWidth
                        );
                    }
                }
            }
            ImageLoader.endAnimation();
        }

        imageComplete(ImageConsumer.STATICIMAGEDONE);
    }

    void setComment(String newComment) {
        Object currComment = properties.get("comment"); //$NON-NLS-1$

        if (currComment == null) {
            properties.put("comment", newComment); //$NON-NLS-1$
        } else {
            properties.put("comment", (String) currComment + "\n" + newComment); //$NON-NLS-1$ //$NON-NLS-2$
        }

        setProperties(properties);
    }

    class GifDataStream {
        //  Indicates that reading of the whole data stream accomplished
        boolean completed = false;

        // Added to support Netscape 2.0 application
        // extension block.
        int loopCount = 1;

        GifLogicalScreen logicalScreen = new GifLogicalScreen();
        List<GifGraphicBlock> graphicBlocks = new ArrayList<GifGraphicBlock>(10); // Of GifGraphicBlocks

        // Comments from the image
        String comments[];
    }

    class GifLogicalScreen {
        //  Indicates that reading of this block accomplished
        boolean completed = false;

        int logicalScreenWidth;
        int logicalScreenHeight;

        int backgroundColor = IMPOSSIBLE_VALUE;

        GifColorTable globalColorTable = new GifColorTable();
    }

    class GifGraphicBlock {
        //  Indicates that reading of this block accomplished
        boolean completed = false;

        final static int DISPOSAL_NONE = 0;
        final static int DISPOSAL_NODISPOSAL = 1;
        final static int DISPOSAL_BACKGROUND = 2;
        final static int DISPOSAL_RESTORE = 3;

        int disposalMethod;
        int delayTime; // Multiplied by 10 already
        int transparentColor = IMPOSSIBLE_VALUE;

        int imageLeft;
        int imageTop;
        int imageWidth;
        int imageHeight;

        // Auxilliary variables to minimize computations
        int imageRight;
        int imageBottom;

        boolean interlace;

        // Don't need local color table - if it is specified
        // image data are converted to RGB in the native code

        byte imageData[] = null;
        int rgbImageData[] = null;

        private int currY = 0; // Current output scanline

        int[] getRgbImageData() {
            if (rgbImageData == null) {
                rgbImageData =
                        toRGB(
                                imageData,
                                gifDataStream.logicalScreen.globalColorTable.colors,
                                transparentColor
                        );
                if (transparentColor != IMPOSSIBLE_VALUE) {
                    transparentColor =
                            gifDataStream.logicalScreen.globalColorTable.cm.getRGB(transparentColor);
                    transparentColor &= 0x00FFFFFF;
                }
            }
            return rgbImageData;
        }

        private void replaceTransparentPixels(int numLines) {
            List<GifGraphicBlock> graphicBlocks = gifDataStream.graphicBlocks;
            int prevBlockIndex = graphicBlocks.indexOf(this) - 1;

            if (prevBlockIndex >= 0) {
                int maxY = currY + numLines + imageTop;
                int offset = currY * imageWidth;

                // Update right and bottom coordinates
                imageRight = imageLeft + imageWidth;
                imageBottom = imageTop + imageHeight;

                int globalWidth = gifDataStream.logicalScreen.logicalScreenWidth;
                int pixelValue, imageOffset;
                int rgbData[] = forceRGB ? getRgbImageData() : null;

                for (int y = currY + imageTop; y < maxY; y++) {
                    imageOffset = globalWidth * y + imageLeft;
                    for (int x = imageLeft; x < imageRight; x++) {
                        pixelValue = forceRGB ?
                                rgbData[offset] :
                                imageData[offset] & 0xFF;
                        if (pixelValue == transparentColor) {
                            if (forceRGB) {
                                pixelValue = getScreenRGBBuffer() [imageOffset];
                                rgbData[offset] = pixelValue;
                            } else {
                                pixelValue = screenBuffer [imageOffset];
                                imageData[offset] = (byte) pixelValue;
                            }
                        }
                        offset++;
                        imageOffset++;
                    } // for
                } // for

            } // if (prevBlockIndex >= 0)
        }

        public void sendNewData(GifDecoder decoder, int numLines) {
            // Get values for transparent pixels
            // from the perevious frames
            if (transparentColor != IMPOSSIBLE_VALUE) {
                replaceTransparentPixels(numLines);
            }

            if (forceRGB) {
                decoder.setPixels(
                        imageLeft,
                        imageTop + currY,
                        imageWidth,
                        numLines,
                        ColorModel.getRGBdefault(),
                        getRgbImageData(),
                        currY*imageWidth,
                        imageWidth
                );
            } else {
                decoder.setPixels(
                        imageLeft,
                        imageTop + currY,
                        imageWidth,
                        numLines,
                        null,
                        imageData,
                        currY*imageWidth,
                        imageWidth
                );
            }

            currY += numLines;
        }

        public void dispose() {
            imageComplete(ImageConsumer.SINGLEFRAMEDONE);

            // Show current frame until delayInterval will not elapse
            if (delayTime > 0) {
                try {
                    Thread.sleep(delayTime);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } else {
                Thread.yield(); // Allow consumers to consume data
            }

            // Don't dispose if image is outside of the visible area
            if (imageLeft > gifDataStream.logicalScreen.logicalScreenWidth ||
                    imageTop > gifDataStream.logicalScreen.logicalScreenHeight) {
                disposalMethod = DISPOSAL_NONE;
            }

            switch(disposalMethod) {
                case DISPOSAL_BACKGROUND: {
                    if (forceRGB) {
                        getRgbImageData(); // Ensure that transparentColor is RGB, not index

                        int data[] = new int[imageWidth*imageHeight];

                        // Compatibility: Fill with transparent color if we have one
                        if (transparentColor != IMPOSSIBLE_VALUE) {
                            Arrays.fill(
                                    data,
                                    transparentColor
                            );
                        } else {
                            Arrays.fill(
                                    data,
                                    gifDataStream.logicalScreen.backgroundColor
                            );
                        }

                        setPixels(
                                imageLeft,
                                imageTop,
                                imageWidth,
                                imageHeight,
                                ColorModel.getRGBdefault(),
                                data,
                                0,
                                imageWidth
                        );

                        sendToScreenBuffer(data);
                    } else {
                        byte data[] = new byte[imageWidth*imageHeight];

                        // Compatibility: Fill with transparent color if we have one
                        if (transparentColor != IMPOSSIBLE_VALUE) {
                            Arrays.fill(
                                    data,
                                    (byte) transparentColor
                            );
                        } else {
                            Arrays.fill(
                                    data,
                                    (byte) gifDataStream.logicalScreen.backgroundColor
                            );
                        }

                        setPixels(
                                imageLeft,
                                imageTop,
                                imageWidth,
                                imageHeight,
                                null,
                                data,
                                0,
                                imageWidth
                        );

                        sendToScreenBuffer(data);
                    }
                    break;
                }
                case DISPOSAL_RESTORE: {
                    screenBufferToScreen();
                    break;
                }
                case DISPOSAL_NONE:
                case DISPOSAL_NODISPOSAL:
                default: {
                    // Copy transmitted data to the screen buffer
                    Object data = forceRGB ? (Object) getRgbImageData() : imageData;
                    sendToScreenBuffer(data);
                    break;
                }
            }
        }

        private void sendToScreenBuffer(Object data) {
            int dataInt[];
            byte dataByte[];

            int width = gifDataStream.logicalScreen.logicalScreenWidth;


            if (forceRGB) {
                dataInt = (int[]) data;

                if (imageWidth == width) {
                    System.arraycopy(dataInt,
                            0,
                            getScreenRGBBuffer(),
                            imageLeft + imageTop*width,
                            dataInt.length
                    );
                } else { // Each scanline
                    copyScanlines(dataInt, getScreenRGBBuffer(), width);
                }
            } else {
                dataByte = (byte[]) data;

                if (imageWidth == width) {
                    System.arraycopy(dataByte,
                            0,
                            screenBuffer,
                            imageLeft + imageTop*width,
                            dataByte.length
                    );
                } else { // Each scanline
                    copyScanlines(dataByte, screenBuffer, width);
                }
            }
        } // sendToScreenBuffer

        private void copyScanlines(Object src, Object dst, int width) {
            for (int i=0; i<imageHeight; i++) {
                System.arraycopy(src,
                        i*imageWidth,
                        dst,
                        imageLeft + i*width + imageTop*width,
                        imageWidth
                );
            } // for
        }

        private void screenBufferToScreen() {
            int width = gifDataStream.logicalScreen.logicalScreenWidth;

            Object dst = forceRGB ?
                    (Object) new int[imageWidth*imageHeight] :
                    new byte[imageWidth*imageHeight];

            Object src = forceRGB ?
                    getScreenRGBBuffer() :
                    (Object) screenBuffer;

            int offset = 0;
            Object toSend;

            if (width == imageWidth) {
                offset = imageWidth * imageTop;
                toSend = src;
            } else {
                for (int i=0; i<imageHeight; i++) {
                    System.arraycopy(src,
                            imageLeft + i*width + imageTop*width,
                            dst,
                            i*imageWidth,
                            imageWidth
                    );
                } // for
                toSend = dst;
            }

            if (forceRGB) {
                setPixels(
                        imageLeft,
                        imageTop,
                        imageWidth,
                        imageHeight,
                        ColorModel.getRGBdefault(),
                        (int [])toSend,
                        offset,
                        imageWidth
                );
            } else {
                setPixels(
                        imageLeft,
                        imageTop,
                        imageWidth,
                        imageHeight,
                        null,
                        (byte [])toSend,
                        offset,
                        imageWidth
                );
            }
        }
    }

    class GifColorTable {
        //  Indicates that reading of this block accomplished
        boolean completed = false;

        IndexColorModel cm = null;
        int size = 0; // Actual number of colors in the color table
        byte colors[] = new byte[256*3];

        IndexColorModel getColorModel(int transparentColor) {
            if (cm != null) {
                if (transparentColor != cm.getTransparentPixel()) {
                    return cm = null; // Force default ARGB color model
                }
                return cm;
            } else
                if (completed && size > 0) {
                    if (transparentColor == IMPOSSIBLE_VALUE) {
                        return cm =
                                new IndexColorModel(8, size, colors, 0, false);
                    }

                    if (transparentColor > size) {
                        size = transparentColor + 1;
                    }
                    return cm =
                            new IndexColorModel(8, size, colors, 0, false, transparentColor);
                }

            return cm = null; // Force default ARGB color model
        }
    }
}
