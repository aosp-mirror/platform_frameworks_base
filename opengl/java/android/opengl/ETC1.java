/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.opengl;

import java.nio.Buffer;

/**
 * Methods for encoding and decoding ETC1 textures.
 * <p>
 * The standard for the ETC1 texture format can be found at
 * http://www.khronos.org/registry/gles/extensions/OES/OES_compressed_ETC1_RGB8_texture.txt
 * <p>
 * The PKM file format is of a 16-byte header that describes the image bounds
 * followed by the encoded ETC1 texture data.
 * <p>
 * @see ETC1Util
 */
public class ETC1 {

    /**
     * Size in bytes of an encoded block.
     */
    public static final int ENCODED_BLOCK_SIZE = 8;

    /**
     * Size in bytes of a decoded block.
     */
    public static final int DECODED_BLOCK_SIZE = 48;

    /**
     * Size of a PKM file header, in bytes.
     */
    public static final int ETC_PKM_HEADER_SIZE = 16;

    /**
     * Accepted by the internalformat parameter of glCompressedTexImage2D.
     */
    public static final int ETC1_RGB8_OES = 0x8D64;

    /**
     * Encode a block of pixels.
     *
     * @param in a native order direct buffer of size DECODED_BLOCK_SIZE that represent a
     * 4 x 4 square of 3-byte pixels in form R, G, B. Byte (3 * (x + 4 * y) is the R
     * value of pixel (x, y).
     *
     * @param validPixelMask is a 16-bit mask where bit (1 << (x + y * 4)) indicates whether
     * the corresponding (x,y) pixel is valid. Invalid pixel color values are ignored when compressing.
     *
     * @param out a native order direct buffer of size ENCODED_BLOCK_SIZE that receives the
     * ETC1 compressed version of the data.
     *
     */
    public static native void encodeBlock(Buffer in, int validPixelMask, Buffer out);

    /**
     * Decode a block of pixels.
     *
     * @param in a native order direct buffer of size ENCODED_BLOCK_SIZE that contains the
     * ETC1 compressed version of the data.
     *
     * @param out a native order direct buffer of size DECODED_BLOCK_SIZE that will receive
     * the decoded data. The data represents a
     * 4 x 4 square of 3-byte pixels in form R, G, B. Byte (3 * (x + 4 * y) is the R
     * value of pixel (x, y).
     */
    public static native void decodeBlock(Buffer in, Buffer out);

    /**
     * Return the size of the encoded image data (does not include size of PKM header).
     */
    public static native int getEncodedDataSize(int width, int height);

    /**
     * Encode an entire image.
     * @param in a native order direct buffer of the image data. Formatted such that
     *           pixel (x,y) is at pIn + pixelSize * x + stride * y;
     * @param out a native order direct buffer of the encoded data.
     * Must be large enough to store entire encoded image.
     * @param pixelSize must be 2 or 3. 2 is an GL_UNSIGNED_SHORT_5_6_5 image,
     * 3 is a GL_BYTE RGB image.
     */
    public static native void encodeImage(Buffer in, int width, int height,
            int pixelSize, int stride, Buffer out);

    /**
     * Decode an entire image.
     * @param in native order direct buffer of the encoded data.
     * @param out native order direct buffer of the image data. Will be written such that
     * pixel (x,y) is at pIn + pixelSize * x + stride * y. Must be
     * large enough to store entire image.
     * @param pixelSize must be 2 or 3. 2 is an GL_UNSIGNED_SHORT_5_6_5 image,
     * 3 is a GL_BYTE RGB image.
     */
    public static native void decodeImage(Buffer in, Buffer out,
            int width, int height, int pixelSize, int stride);

    /**
     * Format a PKM header
     * @param header native order direct buffer of the header.
     * @param width the width of the image in pixels.
     * @param height the height of the image in pixels.
     */
    public static native void formatHeader(Buffer header, int width, int height);

    /**
     * Check if a PKM header is correctly formatted.
     * @param header native order direct buffer of the header.
     */
    public static native boolean isValid(Buffer header);

    /**
     * Read the image width from a PKM header
     * @param header native order direct buffer of the header.
     */
    public static native int getWidth(Buffer header);

    /**
     * Read the image height from a PKM header
     * @param header native order direct buffer of the header.
     */
    public static native int getHeight(Buffer header);
}
