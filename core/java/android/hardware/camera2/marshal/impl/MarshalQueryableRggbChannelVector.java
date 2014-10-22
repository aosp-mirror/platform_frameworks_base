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
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.utils.TypeReference;

import java.nio.ByteBuffer;

import static android.hardware.camera2.impl.CameraMetadataNative.*;
import static android.hardware.camera2.marshal.MarshalHelpers.*;

/**
 * Marshal {@link RggbChannelVector} to/from {@link #TYPE_FLOAT} {@code x 4}
 */
public class MarshalQueryableRggbChannelVector implements MarshalQueryable<RggbChannelVector> {
    private static final int SIZE = SIZEOF_FLOAT * RggbChannelVector.COUNT;

    private class MarshalerRggbChannelVector extends Marshaler<RggbChannelVector> {
        protected MarshalerRggbChannelVector(TypeReference<RggbChannelVector> typeReference,
                int nativeType) {
            super(MarshalQueryableRggbChannelVector.this, typeReference, nativeType);
        }

        @Override
        public void marshal(RggbChannelVector value, ByteBuffer buffer) {
            for (int i = 0; i < RggbChannelVector.COUNT; ++i) {
                buffer.putFloat(value.getComponent(i));
            }
        }

        @Override
        public RggbChannelVector unmarshal(ByteBuffer buffer) {
            float red = buffer.getFloat();
            float gEven = buffer.getFloat();
            float gOdd = buffer.getFloat();
            float blue = buffer.getFloat();

            return new RggbChannelVector(red, gEven, gOdd, blue);
        }

        @Override
        public int getNativeSize() {
            return SIZE;
        }
    }

    @Override
    public Marshaler<RggbChannelVector> createMarshaler(
            TypeReference<RggbChannelVector> managedType, int nativeType) {
        return new MarshalerRggbChannelVector(managedType, nativeType);
    }

    @Override
    public boolean isTypeMappingSupported(
            TypeReference<RggbChannelVector> managedType, int nativeType) {
        return nativeType == TYPE_FLOAT && (RggbChannelVector.class.equals(managedType.getType()));
    }

}
