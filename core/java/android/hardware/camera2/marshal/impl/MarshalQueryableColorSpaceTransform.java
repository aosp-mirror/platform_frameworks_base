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
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.utils.TypeReference;

import java.nio.ByteBuffer;

import static android.hardware.camera2.impl.CameraMetadataNative.*;
import static android.hardware.camera2.marshal.MarshalHelpers.*;

/**
 * Marshal {@link ColorSpaceTransform} to/from {@link #TYPE_RATIONAL}
 */
public class MarshalQueryableColorSpaceTransform implements
        MarshalQueryable<ColorSpaceTransform> {

    private static final int ELEMENTS_INT32 = 3 * 3 * (SIZEOF_RATIONAL / SIZEOF_INT32);
    private static final int SIZE = SIZEOF_INT32 * ELEMENTS_INT32;

    /** rational x 3 x 3 */
    private class MarshalerColorSpaceTransform extends Marshaler<ColorSpaceTransform> {
        protected MarshalerColorSpaceTransform(TypeReference<ColorSpaceTransform> typeReference,
                int nativeType) {
            super(MarshalQueryableColorSpaceTransform.this, typeReference, nativeType);
        }

        @Override
        public void marshal(ColorSpaceTransform value, ByteBuffer buffer) {
            int[] transformAsArray = new int[ELEMENTS_INT32];
            value.copyElements(transformAsArray, /*offset*/0);

            for (int i = 0; i < ELEMENTS_INT32; ++i) {
                buffer.putInt(transformAsArray[i]);
            }
        }

        @Override
        public ColorSpaceTransform unmarshal(ByteBuffer buffer) {
            int[] transformAsArray = new int[ELEMENTS_INT32];

            for (int i = 0; i < ELEMENTS_INT32; ++i) {
                transformAsArray[i] = buffer.getInt();
            }

            return new ColorSpaceTransform(transformAsArray);
        }

        @Override
        public int getNativeSize() {
            return SIZE;
        }
    }

    @Override
    public Marshaler<ColorSpaceTransform> createMarshaler(
            TypeReference<ColorSpaceTransform> managedType, int nativeType) {
        return new MarshalerColorSpaceTransform(managedType, nativeType);
    }

    @Override
    public boolean isTypeMappingSupported(
            TypeReference<ColorSpaceTransform> managedType, int nativeType) {
        return nativeType == TYPE_RATIONAL &&
                ColorSpaceTransform.class.equals(managedType.getType());
    }

}
