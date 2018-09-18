/*
 * Copyright (C) 2018 The Android Open Source Project
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
import android.hardware.camera2.params.RecommendedStreamConfiguration;
import android.hardware.camera2.utils.TypeReference;

import static android.hardware.camera2.impl.CameraMetadataNative.*;
import static android.hardware.camera2.marshal.MarshalHelpers.*;

import java.nio.ByteBuffer;

/**
 * Marshaler for {@code android.scaler.availableRecommendedStreamConfigurations} custom class
 * {@link RecommendedStreamConfiguration}
 *
 * <p>Data is stored as {@code (width, height, format, input, usecaseBitmap)} tuples (int32).</p>
 */
public class MarshalQueryableRecommendedStreamConfiguration
        implements MarshalQueryable<RecommendedStreamConfiguration> {
    private static final int SIZE = SIZEOF_INT32 * 5;

    private class MarshalerRecommendedStreamConfiguration
            extends Marshaler<RecommendedStreamConfiguration> {
        protected MarshalerRecommendedStreamConfiguration(
                TypeReference<RecommendedStreamConfiguration> typeReference, int nativeType) {
            super(MarshalQueryableRecommendedStreamConfiguration.this, typeReference, nativeType);
        }

        @Override
        public void marshal(RecommendedStreamConfiguration value, ByteBuffer buffer) {
            buffer.putInt(value.getWidth());
            buffer.putInt(value.getHeight());
            buffer.putInt(value.getFormat());
            buffer.putInt(value.isInput() ? 1 : 0);
            buffer.putInt(value.getUsecaseBitmap());
        }

        @Override
        public RecommendedStreamConfiguration unmarshal(ByteBuffer buffer) {
            int width = buffer.getInt();
            int height = buffer.getInt();
            int format = buffer.getInt();
            boolean input = buffer.getInt() != 0;
            int usecaseBitmap = buffer.getInt();

            return new RecommendedStreamConfiguration(format, width, height, input, usecaseBitmap);
        }

        @Override
        public int getNativeSize() {
            return SIZE;
        }

    }

    @Override
    public Marshaler<RecommendedStreamConfiguration> createMarshaler(
            TypeReference<RecommendedStreamConfiguration> managedType, int nativeType) {
        return new MarshalerRecommendedStreamConfiguration(managedType, nativeType);
    }

    @Override
    public boolean isTypeMappingSupported(TypeReference<RecommendedStreamConfiguration> managedType,
            int nativeType) {
        return nativeType ==
            TYPE_INT32 && managedType.getType().equals(RecommendedStreamConfiguration.class);
    }
}
