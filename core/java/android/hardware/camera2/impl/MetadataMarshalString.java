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
package android.hardware.camera2.impl;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

public class MetadataMarshalString implements MetadataMarshalClass<String> {

    private static final Charset UTF8_CHARSET = Charset.forName("UTF-8");

    @Override
    public int marshal(String value, ByteBuffer buffer, int nativeType, boolean sizeOnly) {
        byte[] arr = value.getBytes(UTF8_CHARSET);

        if (!sizeOnly) {
            buffer.put(arr);
            buffer.put((byte)0); // metadata strings are NULL-terminated
        }

        return arr.length + 1;
    }

    @Override
    public String unmarshal(ByteBuffer buffer, int nativeType) {

        buffer.mark(); // save the current position

        boolean foundNull = false;
        int stringLength = 0;
        while (buffer.hasRemaining()) {
            if (buffer.get() == (byte)0) {
                foundNull = true;
                break;
            }

            stringLength++;
        }
        if (!foundNull) {
            throw new IllegalArgumentException("Strings must be null-terminated");
        }

        buffer.reset(); // go back to the previously marked position

        byte[] strBytes = new byte[stringLength + 1];
        buffer.get(strBytes, /*dstOffset*/0, stringLength + 1); // including null character

        // not including null character
        return new String(strBytes, /*offset*/0, stringLength, UTF8_CHARSET);
    }

    @Override
    public Class<String> getMarshalingClass() {
        return String.class;
    }

    @Override
    public boolean isNativeTypeSupported(int nativeType) {
        return nativeType == CameraMetadataNative.TYPE_BYTE;
    }

    @Override
    public int getNativeSize(int nativeType) {
        return NATIVE_SIZE_DYNAMIC;
    }
}
