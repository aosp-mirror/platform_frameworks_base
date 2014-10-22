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
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.utils.TypeReference;

import java.nio.ByteBuffer;

import static android.hardware.camera2.impl.CameraMetadataNative.*;
import static android.hardware.camera2.marshal.MarshalHelpers.*;

/**
 * Marshal {@link MeteringRectangle} to/from {@link #TYPE_INT32}
 */
public class MarshalQueryableMeteringRectangle implements MarshalQueryable<MeteringRectangle> {
    private static final int SIZE = SIZEOF_INT32 * 5;

    /** (xmin, ymin, xmax, ymax, weight) */
    private class MarshalerMeteringRectangle extends Marshaler<MeteringRectangle> {
        protected MarshalerMeteringRectangle(TypeReference<MeteringRectangle> typeReference,
                int nativeType) {
            super(MarshalQueryableMeteringRectangle.this, typeReference, nativeType);
        }

        @Override
        public void marshal(MeteringRectangle value, ByteBuffer buffer) {
            int xMin = value.getX();
            int yMin = value.getY();
            int xMax = xMin + value.getWidth();
            int yMax = yMin + value.getHeight();
            int weight = value.getMeteringWeight();

            buffer.putInt(xMin);
            buffer.putInt(yMin);
            buffer.putInt(xMax);
            buffer.putInt(yMax);
            buffer.putInt(weight);
        }

        @Override
        public MeteringRectangle unmarshal(ByteBuffer buffer) {
            int xMin = buffer.getInt();
            int yMin = buffer.getInt();
            int xMax = buffer.getInt();
            int yMax = buffer.getInt();
            int weight = buffer.getInt();

            int width = xMax - xMin;
            int height = yMax - yMin;

            return new MeteringRectangle(xMin, yMin, width, height, weight);
        }

        @Override
        public int getNativeSize() {
            return SIZE;
        }
    }

    @Override
    public Marshaler<MeteringRectangle> createMarshaler(
            TypeReference<MeteringRectangle> managedType, int nativeType) {
        return new MarshalerMeteringRectangle(managedType, nativeType);
    }

    @Override
    public boolean isTypeMappingSupported(
            TypeReference<MeteringRectangle> managedType, int nativeType) {
        return nativeType == TYPE_INT32 && MeteringRectangle.class.equals(managedType.getType());
    }

}
