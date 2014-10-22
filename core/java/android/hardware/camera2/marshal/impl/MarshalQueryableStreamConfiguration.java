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
import android.hardware.camera2.params.StreamConfiguration;
import android.hardware.camera2.utils.TypeReference;

import static android.hardware.camera2.impl.CameraMetadataNative.*;
import static android.hardware.camera2.marshal.MarshalHelpers.*;

import java.nio.ByteBuffer;

/**
 * Marshaler for {@code android.scaler.availableStreamConfigurations} custom class
 * {@link StreamConfiguration}
 *
 * <p>Data is stored as {@code (format, width, height, input?)} tuples (int32).</p>
 */
public class MarshalQueryableStreamConfiguration
        implements MarshalQueryable<StreamConfiguration> {
    private static final int SIZE = SIZEOF_INT32 * 4;

    private class MarshalerStreamConfiguration extends Marshaler<StreamConfiguration> {
        protected MarshalerStreamConfiguration(TypeReference<StreamConfiguration> typeReference,
                int nativeType) {
            super(MarshalQueryableStreamConfiguration.this, typeReference, nativeType);
        }

        @Override
        public void marshal(StreamConfiguration value, ByteBuffer buffer) {
            buffer.putInt(value.getFormat());
            buffer.putInt(value.getWidth());
            buffer.putInt(value.getHeight());
            buffer.putInt(value.isInput() ? 1 : 0);
        }

        @Override
        public StreamConfiguration unmarshal(ByteBuffer buffer) {
            int format = buffer.getInt();
            int width = buffer.getInt();
            int height = buffer.getInt();
            boolean input = buffer.getInt() != 0;

            return new StreamConfiguration(format, width, height, input);
        }

        @Override
        public int getNativeSize() {
            return SIZE;
        }

    }

    @Override
    public Marshaler<StreamConfiguration> createMarshaler(
            TypeReference<StreamConfiguration> managedType, int nativeType) {
        return new MarshalerStreamConfiguration(managedType, nativeType);
    }

    @Override
    public boolean isTypeMappingSupported(TypeReference<StreamConfiguration> managedType,
            int nativeType) {
        return nativeType == TYPE_INT32 && managedType.getType().equals(StreamConfiguration.class);
    }
}
