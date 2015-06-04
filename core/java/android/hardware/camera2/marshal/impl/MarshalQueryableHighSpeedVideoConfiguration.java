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
import android.hardware.camera2.params.HighSpeedVideoConfiguration;
import android.hardware.camera2.utils.TypeReference;

import static android.hardware.camera2.impl.CameraMetadataNative.*;
import static android.hardware.camera2.marshal.MarshalHelpers.*;

import java.nio.ByteBuffer;

/**
 * Marshaler for {@code android.control.availableHighSpeedVideoConfigurations} custom class
 * {@link HighSpeedVideoConfiguration}
 *
 * <p>Data is stored as {@code (width, height, fpsMin, fpsMax)} tuples (int32).</p>
 */
public class MarshalQueryableHighSpeedVideoConfiguration
        implements MarshalQueryable<HighSpeedVideoConfiguration> {
    private static final int SIZE = SIZEOF_INT32 * 5;

    private class MarshalerHighSpeedVideoConfiguration
            extends Marshaler<HighSpeedVideoConfiguration> {
        protected MarshalerHighSpeedVideoConfiguration(
                TypeReference<HighSpeedVideoConfiguration> typeReference,
                int nativeType) {
            super(MarshalQueryableHighSpeedVideoConfiguration.this, typeReference, nativeType);
        }

        @Override
        public void marshal(HighSpeedVideoConfiguration value, ByteBuffer buffer) {
            buffer.putInt(value.getWidth());
            buffer.putInt(value.getHeight());
            buffer.putInt(value.getFpsMin());
            buffer.putInt(value.getFpsMax());
            buffer.putInt(value.getBatchSizeMax());
        }

        @Override
        public HighSpeedVideoConfiguration unmarshal(ByteBuffer buffer) {
            int width = buffer.getInt();
            int height = buffer.getInt();
            int fpsMin = buffer.getInt();
            int fpsMax = buffer.getInt();
            int batchSizeMax = buffer.getInt();

            return new HighSpeedVideoConfiguration(width, height, fpsMin, fpsMax, batchSizeMax);
        }

        @Override
        public int getNativeSize() {
            return SIZE;
        }

    }

    @Override
    public Marshaler<HighSpeedVideoConfiguration> createMarshaler(
            TypeReference<HighSpeedVideoConfiguration> managedType, int nativeType) {
        return new MarshalerHighSpeedVideoConfiguration(managedType, nativeType);
    }

    @Override
    public boolean isTypeMappingSupported(TypeReference<HighSpeedVideoConfiguration> managedType,
            int nativeType) {
        return nativeType == TYPE_INT32 &&
                managedType.getType().equals(HighSpeedVideoConfiguration.class);
    }
}
