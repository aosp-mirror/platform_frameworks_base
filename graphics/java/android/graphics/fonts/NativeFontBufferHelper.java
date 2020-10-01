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

package android.graphics.fonts;

import android.annotation.NonNull;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

import libcore.util.NativeAllocationRegistry;

import java.nio.ByteBuffer;

/**
 * This is a helper class for showing native allocated buffer in Java API.
 *
 * @hide
 */
public class NativeFontBufferHelper {
    private NativeFontBufferHelper() {}

    private static final NativeAllocationRegistry REGISTRY =
            NativeAllocationRegistry.createMalloced(
                    ByteBuffer.class.getClassLoader(), nGetReleaseFunc());

    /**
     * Wrap native buffer with ByteBuffer with adding reference to it.
     */
    public static @NonNull ByteBuffer refByteBuffer(long fontPtr) {
        long refPtr = nRefFontBuffer(fontPtr);
        ByteBuffer buffer = nWrapByteBuffer(refPtr);

        // Releasing native object so that decreasing shared pointer ref count when the byte buffer
        // is GCed.
        REGISTRY.registerNativeAllocation(buffer, refPtr);

        return buffer;
    }

    @CriticalNative
    private static native long nRefFontBuffer(long fontPtr);

    @FastNative
    private static native ByteBuffer nWrapByteBuffer(long refPtr);

    @CriticalNative
    private static native long nGetReleaseFunc();
}
