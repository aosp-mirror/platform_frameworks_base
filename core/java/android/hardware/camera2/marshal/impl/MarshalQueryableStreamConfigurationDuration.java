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
import android.hardware.camera2.params.StreamConfigurationDuration;
import android.hardware.camera2.utils.TypeReference;

import static android.hardware.camera2.impl.CameraMetadataNative.*;
import static android.hardware.camera2.marshal.MarshalHelpers.*;

import java.nio.ByteBuffer;

/**
 * Marshaler for custom class {@link StreamConfigurationDuration} for min-frame and stall durations.
 *
 * <p>
 * Data is stored as {@code (format, width, height, durationNs)} tuples (int64).
 * </p>
 */
public class MarshalQueryableStreamConfigurationDuration
        implements MarshalQueryable<StreamConfigurationDuration> {

    private static final int SIZE = SIZEOF_INT64 * 4;
    /**
     * Values and-ed with this will do an unsigned int to signed long conversion;
     * in other words the sign bit from the int will not be extended.
     * */
    private static final long MASK_UNSIGNED_INT = 0x00000000ffffffffL;

    private class MarshalerStreamConfigurationDuration
        extends Marshaler<StreamConfigurationDuration> {

        protected MarshalerStreamConfigurationDuration(
                TypeReference<StreamConfigurationDuration> typeReference, int nativeType) {
            super(MarshalQueryableStreamConfigurationDuration.this, typeReference, nativeType);
        }

        @Override
        public void marshal(StreamConfigurationDuration value, ByteBuffer buffer) {
            buffer.putLong(value.getFormat() & MASK_UNSIGNED_INT); // unsigned int -> long
            buffer.putLong(value.getWidth());
            buffer.putLong(value.getHeight());
            buffer.putLong(value.getDuration());
        }

        @Override
        public StreamConfigurationDuration unmarshal(ByteBuffer buffer) {
            int format = (int)buffer.getLong();
            int width = (int)buffer.getLong();
            int height = (int)buffer.getLong();
            long durationNs = buffer.getLong();

            return new StreamConfigurationDuration(format, width, height, durationNs);
        }

        @Override
        public int getNativeSize() {
            return SIZE;
        }
    }

    @Override
    public Marshaler<StreamConfigurationDuration> createMarshaler(
            TypeReference<StreamConfigurationDuration> managedType, int nativeType) {
        return new MarshalerStreamConfigurationDuration(managedType, nativeType);
    }

    @Override
    public boolean isTypeMappingSupported(TypeReference<StreamConfigurationDuration> managedType,
            int nativeType) {
        return nativeType == TYPE_INT64 &&
                (StreamConfigurationDuration.class.equals(managedType.getType()));
    }

}