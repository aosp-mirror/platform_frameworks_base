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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Utility methods for using ETC1 compressed textures.
 *
 */
public class ETC1Util {
    /**
     * Convenience method to load an ETC1 texture whether or not the active OpenGL context
     * supports the ETC1 texture compression format.
     * @param target the texture target.
     * @param level the texture level
     * @param border the border size. Typically 0.
     * @param fallbackFormat the format to use if ETC1 texture compression is not supported.
     * Must be GL_RGB.
     * @param fallbackType the type to use if ETC1 texture compression is not supported.
     * Can be either GL_UNSIGNED_SHORT_5_6_5, which implies 16-bits-per-pixel,
     * or GL_UNSIGNED_BYTE, which implies 24-bits-per-pixel.
     * @param input the input stream containing an ETC1 texture in PKM format.
     * @throws IOException
     */
    public static void loadTexture(int target, int level, int border,
            int fallbackFormat, int fallbackType, InputStream input)
        throws IOException {
        loadTexture(target, level, border, fallbackFormat, fallbackType, createTexture(input));
    }

    /**
     * Convenience method to load an ETC1 texture whether or not the active OpenGL context
     * supports the ETC1 texture compression format.
     * @param target the texture target.
     * @param level the texture level
     * @param border the border size. Typically 0.
     * @param fallbackFormat the format to use if ETC1 texture compression is not supported.
     * Must be GL_RGB.
     * @param fallbackType the type to use if ETC1 texture compression is not supported.
     * Can be either GL_UNSIGNED_SHORT_5_6_5, which implies 16-bits-per-pixel,
     * or GL_UNSIGNED_BYTE, which implies 24-bits-per-pixel.
     * @param texture the ETC1 to load.
     */
    public static void loadTexture(int target, int level, int border,
            int fallbackFormat, int fallbackType, ETC1Texture texture) {
        if (fallbackFormat != GLES10.GL_RGB) {
            throw new IllegalArgumentException("fallbackFormat must be GL_RGB");
        }
        if (! (fallbackType == GLES10.GL_UNSIGNED_SHORT_5_6_5
                || fallbackType == GLES10.GL_UNSIGNED_BYTE)) {
            throw new IllegalArgumentException("Unsupported fallbackType");
        }

        int width = texture.getWidth();
        int height = texture.getHeight();
        Buffer data = texture.getData();
        if (isETC1Supported()) {
            int imageSize = data.remaining();
            GLES10.glCompressedTexImage2D(target, level, ETC1.ETC1_RGB8_OES, width, height,
                    border, imageSize, data);
        } else {
            boolean useShorts = fallbackType != GLES10.GL_UNSIGNED_BYTE;
            int pixelSize = useShorts ? 2 : 3;
            int stride = pixelSize * width;
            ByteBuffer decodedData = ByteBuffer.allocateDirect(stride*height)
                .order(ByteOrder.nativeOrder());
            ETC1.decodeImage(data, decodedData, width, height, pixelSize, stride);
            GLES10.glTexImage2D(target, level, fallbackFormat, width, height, border,
                    fallbackFormat, fallbackType, decodedData);
        }
    }

    /**
     * Check if ETC1 texture compression is supported by the active OpenGL ES context.
     * @return true if the active OpenGL ES context supports ETC1 texture compression.
     */
    public static boolean isETC1Supported() {
        int[] results = new int[20];
        GLES10.glGetIntegerv(GLES10.GL_NUM_COMPRESSED_TEXTURE_FORMATS, results, 0);
        int numFormats = results[0];
        if (numFormats > results.length) {
            results = new int[numFormats];
        }
        GLES10.glGetIntegerv(GLES10.GL_COMPRESSED_TEXTURE_FORMATS, results, 0);
        for (int i = 0; i < numFormats; i++) {
            if (results[i] == ETC1.ETC1_RGB8_OES) {
                return true;
            }
        }
        return false;
    }

    /**
     * A utility class encapsulating a compressed ETC1 texture.
     */
    public static class ETC1Texture {
        public ETC1Texture(int width, int height, ByteBuffer data) {
            mWidth = width;
            mHeight = height;
            mData = data;
        }

        /**
         * Get the width of the texture in pixels.
         * @return the width of the texture in pixels.
         */
        public int getWidth() { return mWidth; }

        /**
         * Get the height of the texture in pixels.
         * @return the width of the texture in pixels.
         */
        public int getHeight() { return mHeight; }

        /**
         * Get the compressed data of the texture.
         * @return the texture data.
         */
        public ByteBuffer getData() { return mData; }

        private int mWidth;
        private int mHeight;
        private ByteBuffer mData;
    }

    /**
     * Create a new ETC1Texture from an input stream containing a PKM formatted compressed texture.
     * @param input an input stream containing a PKM formatted compressed texture.
     * @return an ETC1Texture read from the input stream.
     * @throws IOException
     */
    public static ETC1Texture createTexture(InputStream input) throws IOException {
        int width = 0;
        int height = 0;
        byte[] ioBuffer = new byte[4096];
        {
            if (input.read(ioBuffer, 0, ETC1.ETC_PKM_HEADER_SIZE) != ETC1.ETC_PKM_HEADER_SIZE) {
                throw new IOException("Unable to read PKM file header.");
            }
            ByteBuffer headerBuffer = ByteBuffer.allocateDirect(ETC1.ETC_PKM_HEADER_SIZE)
                .order(ByteOrder.nativeOrder());
            headerBuffer.put(ioBuffer, 0, ETC1.ETC_PKM_HEADER_SIZE).position(0);
            if (!ETC1.isValid(headerBuffer)) {
                throw new IOException("Not a PKM file.");
            }
            width = ETC1.getWidth(headerBuffer);
            height = ETC1.getHeight(headerBuffer);
        }
        int encodedSize = ETC1.getEncodedDataSize(width, height);
        ByteBuffer dataBuffer = ByteBuffer.allocateDirect(encodedSize).order(ByteOrder.nativeOrder());
        for (int i = 0; i < encodedSize; ) {
            int chunkSize = Math.min(ioBuffer.length, encodedSize - i);
            if (input.read(ioBuffer, 0, chunkSize) != chunkSize) {
                throw new IOException("Unable to read PKM file data.");
            }
            dataBuffer.put(ioBuffer, 0, chunkSize);
            i += chunkSize;
        }
        dataBuffer.position(0);
        return new ETC1Texture(width, height, dataBuffer);
    }

    /**
     * Helper function that compresses an image into an ETC1Texture.
     * @param input a native order direct buffer containing the image data
     * @param width the width of the image in pixels
     * @param height the height of the image in pixels
     * @param pixelSize the size of a pixel in bytes (2 or 3)
     * @param stride the width of a line of the image in bytes
     * @return the ETC1 texture.
     */
    public static ETC1Texture compressTexture(Buffer input, int width, int height, int pixelSize, int stride){
        int encodedImageSize = ETC1.getEncodedDataSize(width, height);
        ByteBuffer compressedImage = ByteBuffer.allocateDirect(encodedImageSize).
            order(ByteOrder.nativeOrder());
        ETC1.encodeImage(input, width, height, pixelSize, stride, compressedImage);
        return new ETC1Texture(width, height, compressedImage);
    }

    /**
     * Helper function that writes an ETC1Texture to an output stream formatted as a PKM file.
     * @param texture the input texture.
     * @param output the stream to write the formatted texture data to.
     * @throws IOException
     */
    public static void writeTexture(ETC1Texture texture, OutputStream output) throws IOException {
        ByteBuffer dataBuffer = texture.getData();
        int originalPosition = dataBuffer.position();
        try {
            int width = texture.getWidth();
            int height = texture.getHeight();
            ByteBuffer header = ByteBuffer.allocateDirect(ETC1.ETC_PKM_HEADER_SIZE).order(ByteOrder.nativeOrder());
            ETC1.formatHeader(header, width, height);
            byte[] ioBuffer = new byte[4096];
            header.get(ioBuffer, 0, ETC1.ETC_PKM_HEADER_SIZE);
            output.write(ioBuffer, 0, ETC1.ETC_PKM_HEADER_SIZE);
            int encodedSize = ETC1.getEncodedDataSize(width, height);
            for (int i = 0; i < encodedSize; ) {
                int chunkSize = Math.min(ioBuffer.length, encodedSize - i);
                dataBuffer.get(ioBuffer, 0, chunkSize);
                output.write(ioBuffer, 0, chunkSize);
                i += chunkSize;
            }
        } finally {
            dataBuffer.position(originalPosition);
        }
    }
}
