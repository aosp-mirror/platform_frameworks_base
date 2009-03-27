/*
 * Copyright (C) 2006 The Android Open Source Project
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

import javax.microedition.khronos.opengles.GL10;
import android.graphics.Bitmap;

/**
 *
 * Utility class to help bridging OpenGL ES and Android APIs.
 *
 */

public final class GLUtils {

    /*
     * We use a class initializer to allow the native code to cache some
     * field offsets.
     */
    static {
        nativeClassInit();
    }

    private GLUtils() {
    }

    /**
     * return the internal format as defined by OpenGL ES of the supplied bitmap.
     * @param bitmap
     * @return the internal format of the bitmap.
     */
    public static int getInternalFormat(Bitmap bitmap) {
        if (bitmap == null) {
            throw new NullPointerException("getInternalFormat can't be used with a null Bitmap");
        }
        int result = native_getInternalFormat(bitmap);
        if (result < 0) {
            throw new IllegalArgumentException("Unknown internalformat");
        }
        return result;
    }

    /**
     * Return the type as defined by OpenGL ES of the supplied bitmap, if there
     * is one. If the bitmap is stored in a compressed format, it may not have
     * a valid OpenGL ES type.
     * @throws IllegalArgumentException if the bitmap does not have a type.
     * @param bitmap
     * @return the OpenGL ES type of the bitmap.
     */
    public static int getType(Bitmap bitmap) {
        if (bitmap == null) {
            throw new NullPointerException("getType can't be used with a null Bitmap");
        }
        int result = native_getType(bitmap);
        if (result < 0) {
            throw new IllegalArgumentException("Unknown type");
        }
        return result;
    }

    /**
     * Calls glTexImage2D() on the current OpenGL context. If no context is
     * current the behavior is the same as calling glTexImage2D() with  no
     * current context, that is, eglGetError() will return the appropriate
     * error.
     * Unlike glTexImage2D() bitmap cannot be null and will raise an exception
     * in that case.
     * All other parameters are identical to those used for glTexImage2D().
     *
     * NOTE: this method doesn't change GL_UNPACK_ALIGNMENT, you must make
     * sure to set it properly according to the supplied bitmap.
     *
     * Whether or not bitmap can have non power of two dimensions depends on
     * the current OpenGL context. Always check glGetError() some time
     * after calling this method, just like when using OpenGL directly.
     *
     * @param target
     * @param level
     * @param internalformat
     * @param bitmap
     * @param border
     */
    public static void texImage2D(int target, int level, int internalformat,
            Bitmap bitmap, int border) {
        if (bitmap == null) {
            throw new NullPointerException("texImage2D can't be used with a null Bitmap");
        }
        if (native_texImage2D(target, level, internalformat, bitmap, -1, border)!=0) {
            throw new IllegalArgumentException("invalid Bitmap format");
        }
    }

    /**
     * A version of texImage2D() that takes an explicit type parameter
     * as defined by the OpenGL ES specification. The actual type and
     * internalformat of the bitmap must be compatible with the specified
     * type and internalformat parameters.
     *
     * @param target
     * @param level
     * @param internalformat
     * @param bitmap
     * @param type
     * @param border
     */
    public static void texImage2D(int target, int level, int internalformat,
            Bitmap bitmap, int type, int border) {
        if (bitmap == null) {
            throw new NullPointerException("texImage2D can't be used with a null Bitmap");
        }
        if (native_texImage2D(target, level, internalformat, bitmap, type, border)!=0) {
            throw new IllegalArgumentException("invalid Bitmap format");
        }
    }

    /**
     * A version of texImage2D that determines the internalFormat and type
     * automatically.
     *
     * @param target
     * @param level
     * @param bitmap
     * @param border
     */
    public static void texImage2D(int target, int level, Bitmap bitmap,
            int border) {
        if (bitmap == null) {
            throw new NullPointerException("texImage2D can't be used with a null Bitmap");
        }
        if (native_texImage2D(target, level, -1, bitmap, -1, border)!=0) {
            throw new IllegalArgumentException("invalid Bitmap format");
        }
    }

    /**
     * Calls glTexSubImage2D() on the current OpenGL context. If no context is
     * current the behavior is the same as calling glTexSubImage2D() with  no
     * current context, that is, eglGetError() will return the appropriate
     * error.
     * Unlike glTexSubImage2D() bitmap cannot be null and will raise an exception
     * in that case.
     * All other parameters are identical to those used for glTexSubImage2D().
     *
     * NOTE: this method doesn't change GL_UNPACK_ALIGNMENT, you must make
     * sure to set it properly according to the supplied bitmap.
     *
     * Whether or not bitmap can have non power of two dimensions depends on
     * the current OpenGL context. Always check glGetError() some time
     * after calling this method, just like when using OpenGL directly.
     *
     * @param target
     * @param level
     * @param xoffset
     * @param yoffset
     * @param bitmap
     */
    public static void texSubImage2D(int target, int level, int xoffset, int yoffset,
            Bitmap bitmap) {
        if (bitmap == null) {
            throw new NullPointerException("texSubImage2D can't be used with a null Bitmap");
        }
        int type = getType(bitmap);
        if (native_texSubImage2D(target, level, xoffset, yoffset, bitmap, -1, type)!=0) {
            throw new IllegalArgumentException("invalid Bitmap format");
        }
    }

    /**
     * A version of texSubImage2D() that takes an explicit type parameter
     * as defined by the OpenGL ES specification.
     *
     * @param target
     * @param level
     * @param xoffset
     * @param yoffset
     * @param bitmap
     * @param type
     */
    public static void texSubImage2D(int target, int level, int xoffset, int yoffset,
            Bitmap bitmap, int format, int type) {
        if (bitmap == null) {
            throw new NullPointerException("texSubImage2D can't be used with a null Bitmap");
        }
        if (native_texSubImage2D(target, level, xoffset, yoffset, bitmap, format, type)!=0) {
            throw new IllegalArgumentException("invalid Bitmap format");
        }
    }

    native private static void nativeClassInit();

    native private static int native_getInternalFormat(Bitmap bitmap);
    native private static int native_getType(Bitmap bitmap);
    native private static int native_texImage2D(int target, int level, int internalformat,
            Bitmap bitmap, int type, int border);
    native private static int native_texSubImage2D(int target, int level, int xoffset, int yoffset,
            Bitmap bitmap, int format, int type);
}
