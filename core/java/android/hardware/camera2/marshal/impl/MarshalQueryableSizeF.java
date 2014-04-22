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
import android.util.SizeF;

import static android.hardware.camera2.impl.CameraMetadataNative.*;
import static android.hardware.camera2.marshal.MarshalHelpers.*;

import java.nio.ByteBuffer;

/**
 * Marshal {@link SizeF} to/from {@code TYPE_FLOAT}
 */
public class MarshalQueryableSizeF implements MarshalQueryable<SizeF> {

    private static final int SIZE = SIZEOF_FLOAT * 2;

    private class MarshalerSizeF extends Marshaler<SizeF> {

        protected MarshalerSizeF(TypeReference<SizeF> typeReference, int nativeType) {
            super(MarshalQueryableSizeF.this, typeReference, nativeType);
        }

        @Override
        public void marshal(SizeF value, ByteBuffer buffer) {
            buffer.putFloat(value.getWidth());
            buffer.putFloat(value.getHeight());
        }

        @Override
        public SizeF unmarshal(ByteBuffer buffer) {
            float width = buffer.getFloat();
            float height = buffer.getFloat();

            return new SizeF(width, height);
        }

        @Override
        public int getNativeSize() {
            return SIZE;
        }
    }

    @Override
    public Marshaler<SizeF> createMarshaler(
            TypeReference<SizeF> managedType, int nativeType) {
        return new MarshalerSizeF(managedType, nativeType);
    }

    @Override
    public boolean isTypeMappingSupported(TypeReference<SizeF> managedType, int nativeType) {
        return nativeType == TYPE_FLOAT && (SizeF.class.equals(managedType.getType()));
    }
}

