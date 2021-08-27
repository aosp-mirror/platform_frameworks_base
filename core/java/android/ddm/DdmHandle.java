/*
 * Copyright (C) 2021 The Android Open Source Project
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
package android.ddm;

import org.apache.harmony.dalvik.ddmc.ChunkHandler;

import java.nio.ByteBuffer;

/**
 * Contains utility methods for chunk serialization and deserialization.
 */
public abstract class DdmHandle extends ChunkHandler {

    /**
     * Utility function to copy a String out of a ByteBuffer.
     *
     * This is here because multiple chunk handlers can make use of it,
     * and there's nowhere better to put it.
     */
    public static String getString(ByteBuffer buf, int len) {
        char[] data = new char[len];
        for (int i = 0; i < len; i++) {
            data[i] = buf.getChar();
        }
        return new String(data);
    }

    /**
     * Utility function to copy a String into a ByteBuffer.
     */
    public static void putString(ByteBuffer buf, String str) {
        int len = str.length();
        for (int i = 0; i < len; i++) {
            buf.putChar(str.charAt(i));
        }
    }

}
