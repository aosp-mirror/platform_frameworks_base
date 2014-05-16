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

import android.util.Size;
import android.hardware.camera2.marshal.Marshaler;
import android.hardware.camera2.marshal.MarshalQueryable;
import android.hardware.camera2.utils.TypeReference;

import static android.hardware.camera2.impl.CameraMetadataNative.*;
import static android.hardware.camera2.marshal.MarshalHelpers.*;

import java.nio.ByteBuffer;

/**
 * Marshal {@link Size} to/from {@code TYPE_INT32}
 */
public class MarshalQueryableSize implements MarshalQueryable<Size> {
    private static final int SIZE = SIZEOF_INT32 * 2;

    private class MarshalerSize extends Marshaler<Size> {
        protected MarshalerSize(TypeReference<Size> typeReference, int nativeType) {
            super(MarshalQueryableSize.this, typeReference, nativeType);
        }

        @Override
        public void marshal(Size value, ByteBuffer buffer) {
            buffer.putInt(value.getWidth());
            buffer.putInt(value.getHeight());
        }

        @Override
        public Size unmarshal(ByteBuffer buffer) {
            int width = buffer.getInt();
            int height = buffer.getInt();

            return new Size(width, height);
        }

        @Override
        public int getNativeSize() {
            return SIZE;
        }
    }

    @Override
    public Marshaler<Size> createMarshaler(TypeReference<Size> managedType, int nativeType) {
        return new MarshalerSize(managedType, nativeType);
    }

    @Override
    public boolean isTypeMappingSupported(TypeReference<Size> managedType, int nativeType) {
        return nativeType == TYPE_INT32 && (Size.class.equals(managedType.getType()));
    }
}
