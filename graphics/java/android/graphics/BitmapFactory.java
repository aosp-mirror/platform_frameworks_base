/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.graphics;

import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.FileDescriptor;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;


/**
 * Creates Bitmap objects from various sources, including files, streams, 
 * and byte-arrays. 
 */
public class BitmapFactory {
    private static final String TAG = "BitmapFactory";
    private static final boolean DEBUG_LOAD = false;

    public static class Options {
        /**
         * Create a default Options object, which if left unchanged will give
         * the same result from the decoder as if null were passed.
         */
        public Options() {
            inDither = true;
        }

        /**
         * If set to true, the decoder will return null (no bitmap), but
         * the out... fields will still be set, allowing the caller to query
         * the bitmap without having to allocate the memory for its pixels.
         */
        public boolean inJustDecodeBounds;
        /**
         * If set to a value > 1, requests the decoder to subsample the original
         * image, returning a smaller image to save memory. The sample size is
         * the number of pixels in either dimension that correspond to a single
         * pixel in the decoded bitmap. For example, inSampleSize == 4 returns
         * an image that is 1/4 the width/height of the original, and 1/16 the
         * number of pixels. Any value <= 1 is treated the same as 1. Note: the
         * decoder will try to fulfill this request, but the resulting bitmap
         * may have different dimensions that precisely what has been requested.
         * Also, powers of 2 are often faster/easier for the decoder to honor.
         */
        public int inSampleSize;
        
        /**
         * If this is non-null, the decoder will try to decode into this
         * internal configuration. If it is null, or the request cannot be met,
         * the decoder will try to pick the best matching config based on the
         * system's screen depth, and characteristics of the original image such
         * as if it has per-pixel alpha (requiring a config that also does).
         */
        public Bitmap.Config inPreferredConfig;
        
        /**
         * If dither is true, the decoder will atttempt to dither the decoded
         * image.
         */
        public boolean inDither;
        
        /**
         * The resulting width of the bitmap, set independent of the state of
         * inJustDecodeBounds. However, if there is an error trying to decode,
         * outWidth will be set to -1.
         */
        public int outWidth;
        /**
         * The resulting height of the bitmap, set independent of the state of
         * inJustDecodeBounds. However, if there is an error trying to decode,
         * outHeight will be set to -1.
         */
        public int outHeight;
        
        /**
         * If known, this string is set to the mimetype of the decoded image.
         * If not know, or there is an error, it is set to null.
         */
        public String outMimeType;
        
        /**
         * Temp storage to use for decoding.  Suggest 16K or so.
         */
        public byte [] inTempStorage;
        
        private native void requestCancel();

        /**
         * Flag to indicate that cancel has been called on this object.  This
         * is useful if there's an intermediary that wants to first decode the
         * bounds and then decode the image.  In that case the intermediary
         * can check, inbetween the bounds decode and the image decode, to see
         * if the operation is canceled.
         */
        public boolean mCancel;
        
        /**
         *  This can be called from another thread while this options object is
         *  inside a decode... call. Calling this will notify the decoder that
         *  it should cancel its operation. This is not guaranteed to cancel
         *  the decode, but if it does, the decoder... operation will return
         *  null, or if inJustDecodeBounds is true, will set outWidth/outHeight
         *  to -1
         */
        public void requestCancelDecode() {
            mCancel = true;
            requestCancel();
        }
    }

    /**
     * Decode a file path into a bitmap. If the specified file name is null,
     * or cannot be decoded into a bitmap, the function returns null.
     *
     * @param pathName complete path name for the file to be decoded.
     * @param opts null-ok; Options that control downsampling and whether the
     *             image should be completely decoded, or just is size returned.
     * @return The decoded bitmap, or null if the image data could not be
     *         decoded, or, if opts is non-null, if opts requested only the
     *         size be returned (in opts.outWidth and opts.outHeight)
     */
    public static Bitmap decodeFile(String pathName, Options opts) {
        Bitmap bm = null;
        InputStream stream = null;
        try {
            stream = new FileInputStream(pathName);
            bm = decodeStream(stream, null, opts);
        } catch (Exception e) {
            /*  do nothing.
                If the exception happened on open, bm will be null.
            */
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException e) {
                    // do nothing here
                }
            }
        }
        return bm;
    }

    /**
     * Decode a file path into a bitmap. If the specified file name is null,
     * or cannot be decoded into a bitmap, the function returns null.
     *
     * @param pathName complete path name for the file to be decoded.
     * @return the resulting decoded bitmap, or null if it could not be decoded.
     */
    public static Bitmap decodeFile(String pathName) {
        return decodeFile(pathName, null);
    }

    /**
     * Decode an image referenced by a resource ID.
     *
     * @param res   The resources object containing the image data
     * @param id The resource id of the image data
     * @param opts null-ok; Options that control downsampling and whether the
     *             image should be completely decoded, or just is size returned.
     * @return The decoded bitmap, or null if the image data could not be
     *         decoded, or, if opts is non-null, if opts requested only the
     *         size be returned (in opts.outWidth and opts.outHeight)
     */
    public static Bitmap decodeResource(Resources res, int id, Options opts) {
        Bitmap bm = null;
        
        try {
            InputStream is = res.openRawResource(id);
            bm = decodeStream(is, null, opts);
            is.close();
        }
        catch (java.io.IOException e) {
            /*  do nothing.
                If the exception happened on open, bm will be null.
                If it happened on close, bm is still valid.
            */
        }
        return bm;
    }
    
    /**
     * Decode an image referenced by a resource ID.
     *
     * @param res The resources object containing the image data
     * @param id The resource id of the image data
     * @return The decoded bitmap, or null if the image could not be decode.
     */
    public static Bitmap decodeResource(Resources res, int id) {
        return decodeResource(res, id, null);
    }

    /**
     * Decode an immutable bitmap from the specified byte array.
     *
     * @param data byte array of compressed image data
     * @param offset offset into imageData for where the decoder should begin
     *               parsing.
     * @param length the number of bytes, beginning at offset, to parse
     * @param opts null-ok; Options that control downsampling and whether the
     *             image should be completely decoded, or just is size returned.
     * @return The decoded bitmap, or null if the image data could not be
     *         decoded, or, if opts is non-null, if opts requested only the
     *         size be returned (in opts.outWidth and opts.outHeight)
     */
    public static Bitmap decodeByteArray(byte[] data, int offset, int length,
                                         Options opts) {
        if ((offset | length) < 0 || data.length < offset + length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        return nativeDecodeByteArray(data, offset, length, opts);
    }
    
    /**
     * Decode an immutable bitmap from the specified byte array.
     *
     * @param data byte array of compressed image data
     * @param offset offset into imageData for where the decoder should begin
     *               parsing.
     * @param length the number of bytes, beginning at offset, to parse
     * @return The decoded bitmap, or null if the image could not be decode.
     */
    public static Bitmap decodeByteArray(byte[] data, int offset, int length) {
        return decodeByteArray(data, offset, length, null);
    }
    
    /**
     * Decode an input stream into a bitmap. If the input stream is null, or
     * cannot be used to decode a bitmap, the function returns null.
     * The stream's position will be where ever it was after the encoded data
     * was read.
     * 
     * @param is The input stream that holds the raw data to be decoded into a
     *           bitmap.
     * @param outPadding If not null, return the padding rect for the bitmap if
     *                   it exists, otherwise set padding to [-1,-1,-1,-1]. If
     *                   no bitmap is returned (null) then padding is
     *                   unchanged.
     * @param opts null-ok; Options that control downsampling and whether the
     *             image should be completely decoded, or just is size returned.
     * @return The decoded bitmap, or null if the image data could not be
     *         decoded, or, if opts is non-null, if opts requested only the
     *         size be returned (in opts.outWidth and opts.outHeight)
     */
    public static Bitmap decodeStream(InputStream is, Rect outPadding,
                                      Options opts) {
        // we don't throw in this case, thus allowing the caller to only check
        // the cache, and not force the image to be decoded.
        if (is == null) {
            return null;
        }
        
        // we need mark/reset to work properly

        if (!is.markSupported()) {
            is = new BufferedInputStream(is, 16 * 1024);
        }

        // so we can call reset() if a given codec gives up after reading up to
        // this many bytes. FIXME: need to find out from the codecs what this
        // value should be.
        is.mark(1024);

        Bitmap  bm;

        if (is instanceof AssetManager.AssetInputStream) {
            bm = nativeDecodeAsset(
                ((AssetManager.AssetInputStream)is).getAssetInt(), outPadding,
                                   opts);
        }
        else {
            // pass some temp storage down to the native code. 1024 is made up,
            // but should be large enough to avoid too many small calls back
            // into is.read(...) This number is not related to the value passed
            // to mark(...) above.
            byte [] tempStorage = null;
            if (opts != null)
                tempStorage = opts.inTempStorage;
            if (tempStorage == null)
                tempStorage = new byte[16 * 1024];
            bm = nativeDecodeStream(is, tempStorage, outPadding, opts);
        }

        return bm;
    }

    /**
     * Decode an input stream into a bitmap. If the input stream is null, or
     * cannot be used to decode a bitmap, the function returns null.
     * The stream's position will be where ever it was after the encoded data
     * was read.
     * 
     * @param is The input stream that holds the raw data to be decoded into a
     *           bitmap.
     * @return The decoded bitmap, or null if the image data could not be
     *         decoded, or, if opts is non-null, if opts requested only the
     *         size be returned (in opts.outWidth and opts.outHeight)
     */
    public static Bitmap decodeStream(InputStream is) {
        return decodeStream(is, null, null);
    }

    /**
     * Decode a bitmap from the file descriptor. If the bitmap cannot be decoded
     * return null. The position within the descriptor will not be changed when
     * this returns, so the descriptor can be used again as is.
     *
     * @param fd The file descriptor containing the bitmap data to decode
     * @param outPadding If not null, return the padding rect for the bitmap if
     *                   it exists, otherwise set padding to [-1,-1,-1,-1]. If
     *                   no bitmap is returned (null) then padding is
     *                   unchanged.
     * @param opts null-ok; Options that control downsampling and whether the
     *             image should be completely decoded, or just is size returned.
     * @return the decoded bitmap, or null
     */
    public static Bitmap decodeFileDescriptor(FileDescriptor fd,
                                              Rect outPadding, Options opts) {
        return nativeDecodeFileDescriptor(fd, outPadding, opts);
    }
    
    /**
     * Decode a bitmap from the file descriptor. If the bitmap cannot be decoded
     * return null. The position within the descriptor will not be changed when
     * this returns, so the descriptor can be used again as is.
     *
     * @param fd The file descriptor containing the bitmap data to decode
     * @return the decoded bitmap, or null
     */
    public static Bitmap decodeFileDescriptor(FileDescriptor fd) {
        return nativeDecodeFileDescriptor(fd, null, null);
    }
    
    private static native Bitmap nativeDecodeStream(InputStream is,
                                    byte[] storage, Rect padding, Options opts);
    private static native Bitmap nativeDecodeFileDescriptor(FileDescriptor fd,
                                    Rect padding, Options opts);
    private static native Bitmap nativeDecodeAsset(int asset, Rect padding,
                                    Options opts);
    private static native Bitmap nativeDecodeByteArray(byte[] data, int offset,
                                    int length, Options opts);
}

