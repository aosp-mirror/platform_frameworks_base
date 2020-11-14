/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.util;

import android.annotation.NonNull;

import dalvik.annotation.optimization.FastNative;

/**
 * Specializations of {@code libcore.util.CharsetUtils} which enable efficient
 * in-place encoding without making any new allocations.
 * <p>
 * These methods purposefully accept only non-movable byte array addresses to
 * avoid extra JNI overhead.
 *
 * @hide
 */
public class CharsetUtils {
    /**
     * Attempt to encode the given string as modified UTF-8 into the destination
     * byte array without making any new allocations.
     *
     * @param src string value to be encoded
     * @param dest destination byte array to encode into
     * @param destOff offset into destination where encoding should begin
     * @param destLen length of destination
     * @return positive value when encoding succeeded, or negative value when
     *         failed; the magnitude of the value is the number of bytes
     *         required to encode the string.
     */
    public static int toModifiedUtf8Bytes(@NonNull String src,
            long dest, int destOff, int destLen) {
        return toModifiedUtf8Bytes(src, src.length(), dest, destOff, destLen);
    }

    /**
     * Attempt to encode the given string as modified UTF-8 into the destination
     * byte array without making any new allocations.
     *
     * @param src string value to be encoded
     * @param srcLen exact length of string to be encoded
     * @param dest destination byte array to encode into
     * @param destOff offset into destination where encoding should begin
     * @param destLen length of destination
     * @return positive value when encoding succeeded, or negative value when
     *         failed; the magnitude of the value is the number of bytes
     *         required to encode the string.
     */
    @FastNative
    private static native int toModifiedUtf8Bytes(@NonNull String src, int srcLen,
            long dest, int destOff, int destLen);

    /**
     * Attempt to decode a modified UTF-8 string from the source byte array.
     *
     * @param src source byte array to decode from
     * @param srcOff offset into source where decoding should begin
     * @param srcLen length of source that should be decoded
     * @return the successfully decoded string
     */
    @FastNative
    public static native @NonNull String fromModifiedUtf8Bytes(
            long src, int srcOff, int srcLen);
}
