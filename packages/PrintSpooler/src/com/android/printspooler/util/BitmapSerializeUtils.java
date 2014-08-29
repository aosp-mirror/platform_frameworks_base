/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.printspooler.util;

import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;

/**
 * Helper for serialization of bitmaps in the very specific
 * use case of having the same bitmap on both ends and just
 * marshaling the pixels from one side to the other.
 */
public final class BitmapSerializeUtils {

    static {
        System.loadLibrary("printspooler_jni");
    }

    private BitmapSerializeUtils() {
        /* do nothing */
    }

    /**
     * Reads a bitmap pixels from a file descriptor.
     *
     * @param bitmap A bitmap whose pixels to populate.
     * @param source The source file descriptor.
     */
    public static void readBitmapPixels(Bitmap bitmap, ParcelFileDescriptor source) {
        nativeReadBitmapPixels(bitmap, source.getFd());
    }

    /**
     * Writes a bitmap pixels to a file descriptor.
     *
     * @param bitmap The bitmap.
     * @param destination The destination file descriptor.
     */
    public static void writeBitmapPixels(Bitmap bitmap, ParcelFileDescriptor destination) {
        nativeWriteBitmapPixels(bitmap, destination.getFd());
    }

    private static native void nativeReadBitmapPixels(Bitmap bitmap, int fd);

    private static native void nativeWriteBitmapPixels(Bitmap bitmap, int fd);
}
