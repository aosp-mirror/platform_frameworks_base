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
package android.hardware.camera2.marshal.impl;

import android.hardware.camera2.marshal.Marshaler;
import android.hardware.camera2.marshal.MarshalQueryable;
import android.hardware.camera2.utils.TypeReference;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import static android.hardware.camera2.impl.CameraMetadataNative.*;

/**
 * Marshal {@link String} to/from {@link #TYPE_BYTE}.
 */
public class MarshalQueryableString implements MarshalQueryable<String> {

    private static final String TAG = MarshalQueryableString.class.getSimpleName();
    private static final boolean DEBUG = false;

    private static class PreloadHolder {
        public static final Charset UTF8_CHARSET = Charset.forName("UTF-8");
    }
    private static final byte NUL = (byte)'\0'; // used as string terminator

    private class MarshalerString extends Marshaler<String> {

        protected MarshalerString(TypeReference<String> typeReference, int nativeType) {
            super(MarshalQueryableString.this, typeReference, nativeType);
        }

        @Override
        public void marshal(String value, ByteBuffer buffer) {
            byte[] arr = value.getBytes(PreloadHolder.UTF8_CHARSET);

            buffer.put(arr);
            buffer.put(NUL); // metadata strings are NUL-terminated
        }

        @Override
        public int calculateMarshalSize(String value) {
            byte[] arr = value.getBytes(PreloadHolder.UTF8_CHARSET);

            return arr.length + 1; // metadata strings are NUL-terminated
        }

        @Override
        public String unmarshal(ByteBuffer buffer) {
            buffer.mark(); // save the current position

            boolean foundNull = false;
            int stringLength = 0;
            while (buffer.hasRemaining()) {
                if (buffer.get() == NUL) {
                    foundNull = true;
                    break;
                }

                stringLength++;
            }

            if (DEBUG) {
                Log.v(TAG,
                        "unmarshal - scanned " + stringLength + " characters; found null? "
                                + foundNull);
            }

            if (!foundNull) {
                throw new UnsupportedOperationException("Strings must be null-terminated");
            }

            buffer.reset(); // go back to the previously marked position

            byte[] strBytes = new byte[stringLength + 1];
            buffer.get(strBytes, /*dstOffset*/0, stringLength + 1); // including null character

            // not including null character
            return new String(strBytes, /*offset*/0, stringLength, PreloadHolder.UTF8_CHARSET);
        }

        @Override
        public int getNativeSize() {
            return NATIVE_SIZE_DYNAMIC;
        }
    }

    @Override
    public Marshaler<String> createMarshaler(
            TypeReference<String> managedType, int nativeType) {
        return new MarshalerString(managedType, nativeType);
    }

    @Override
    public boolean isTypeMappingSupported(TypeReference<String> managedType, int nativeType) {
        return nativeType == TYPE_BYTE && String.class.equals(managedType.getType());
    }
}
