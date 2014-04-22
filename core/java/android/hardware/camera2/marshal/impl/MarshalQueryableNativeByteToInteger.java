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

import static android.hardware.camera2.impl.CameraMetadataNative.*;
import static android.hardware.camera2.marshal.MarshalHelpers.*;

import android.hardware.camera2.marshal.Marshaler;
import android.hardware.camera2.marshal.MarshalQueryable;
import android.hardware.camera2.utils.TypeReference;

import java.nio.ByteBuffer;

/**
 * Marshal fake native enums (ints): TYPE_BYTE <-> int/Integer
 */
public class MarshalQueryableNativeByteToInteger implements MarshalQueryable<Integer> {

    private static final int UINT8_MASK = (1 << Byte.SIZE) - 1;

    private class MarshalerNativeByteToInteger extends Marshaler<Integer> {
        protected MarshalerNativeByteToInteger(TypeReference<Integer> typeReference,
                int nativeType) {
            super(MarshalQueryableNativeByteToInteger.this, typeReference, nativeType);
        }

        @Override
        public void marshal(Integer value, ByteBuffer buffer) {
            buffer.put((byte)(int)value); // truncate down to byte
        }

        @Override
        public Integer unmarshal(ByteBuffer buffer) {
            // expand unsigned byte to int; avoid sign extension
            return buffer.get() & UINT8_MASK;
        }

        @Override
        public int getNativeSize() {
            return SIZEOF_BYTE;
        }
    }

    @Override
    public Marshaler<Integer> createMarshaler(TypeReference<Integer> managedType,
            int nativeType) {
        return new MarshalerNativeByteToInteger(managedType, nativeType);
    }

    @Override
    public boolean isTypeMappingSupported(TypeReference<Integer> managedType, int nativeType) {
        return (Integer.class.equals(managedType.getType())
                || int.class.equals(managedType.getType())) && nativeType == TYPE_BYTE;
    }


}
