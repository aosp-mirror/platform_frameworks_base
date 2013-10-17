/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.tools.layoutlib.java;

import java.nio.charset.Charset;

/**
 * Defines the same class as the java.lang.UnsafeByteSequence which was added in
 * Dalvik VM. This hack, provides a replacement for that class which can't be
 * loaded in the standard JVM since it's in the java package and standard JVM
 * doesn't have it.
 * <p/>
 * Extracted from API level 18, file:
 * platform/libcore/luni/src/main/java/java/lang/UnsafeByteSequence.java
 */
public class UnsafeByteSequence {
    private byte[] bytes;
    private int count;

    public UnsafeByteSequence(int initialCapacity) {
        this.bytes = new byte[initialCapacity];
    }

    public int size() {
        return count;
    }

    /**
     * Moves the write pointer back to the beginning of the sequence,
     * but without resizing or reallocating the buffer.
     */
    public void rewind() {
        count = 0;
    }

    public void write(byte[] buffer, int offset, int length) {
        if (count + length >= bytes.length) {
            byte[] newBytes = new byte[(count + length) * 2];
            System.arraycopy(bytes, 0, newBytes, 0, count);
            bytes = newBytes;
        }
        System.arraycopy(buffer, offset, bytes, count, length);
        count += length;
    }

    public void write(int b) {
        if (count == bytes.length) {
            byte[] newBytes = new byte[count * 2];
            System.arraycopy(bytes, 0, newBytes, 0, count);
            bytes = newBytes;
        }
        bytes[count++] = (byte) b;
    }

    public byte[] toByteArray() {
        if (count == bytes.length) {
            return bytes;
        }
        byte[] result = new byte[count];
        System.arraycopy(bytes, 0, result, 0, count);
        return result;
    }

    public String toString(Charset cs) {
        return new String(bytes, 0, count, cs);
    }
}
