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
import android.hardware.camera2.params.ReprocessFormatsMap;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.hardware.camera2.utils.TypeReference;

import static android.hardware.camera2.impl.CameraMetadataNative.*;
import static android.hardware.camera2.marshal.MarshalHelpers.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Marshaler for {@code android.scaler.availableInputOutputFormatsMap} custom class
 * {@link ReprocessFormatsMap}
 */
public class MarshalQueryableReprocessFormatsMap
        implements MarshalQueryable<ReprocessFormatsMap> {

    private class MarshalerReprocessFormatsMap extends Marshaler<ReprocessFormatsMap> {
        protected MarshalerReprocessFormatsMap(
                TypeReference<ReprocessFormatsMap> typeReference, int nativeType) {
            super(MarshalQueryableReprocessFormatsMap.this, typeReference, nativeType);
        }

        @Override
        public void marshal(ReprocessFormatsMap value, ByteBuffer buffer) {
            /*
             * // writing (static example, DNG+ZSL)
             * int32_t[] contents = {
             *   RAW_OPAQUE, 3, RAW16, YUV_420_888, BLOB,
             *   RAW16, 2, YUV_420_888, BLOB,
             *   ...,
             *   INPUT_FORMAT, OUTPUT_FORMAT_COUNT, [OUTPUT_0, OUTPUT_1, ..., OUTPUT_FORMAT_COUNT-1]
             * };
             */
            int[] inputs = StreamConfigurationMap.imageFormatToInternal(value.getInputs());
            for (int input : inputs) {
                // INPUT_FORMAT
                buffer.putInt(input);

                int[] outputs =
                        StreamConfigurationMap.imageFormatToInternal(value.getOutputs(input));
                // OUTPUT_FORMAT_COUNT
                buffer.putInt(outputs.length);

                // [OUTPUT_0, OUTPUT_1, ..., OUTPUT_FORMAT_COUNT-1]
                for (int output : outputs) {
                    buffer.putInt(output);
                }
            }
        }

        @Override
        public ReprocessFormatsMap unmarshal(ByteBuffer buffer) {
            int len = buffer.remaining() / SIZEOF_INT32;
            if (buffer.remaining() % SIZEOF_INT32 != 0) {
                throw new AssertionError("ReprocessFormatsMap was not TYPE_INT32");
            }

            int[] entries = new int[len];

            IntBuffer intBuffer = buffer.asIntBuffer();
            intBuffer.get(entries);

            // TODO: consider moving rest of parsing code from ReprocessFormatsMap to here

            return new ReprocessFormatsMap(entries);
        }

        @Override
        public int getNativeSize() {
            return NATIVE_SIZE_DYNAMIC;
        }

        @Override
        public int calculateMarshalSize(ReprocessFormatsMap value) {
            /*
             * // writing (static example, DNG+ZSL)
             * int32_t[] contents = {
             *   RAW_OPAQUE, 3, RAW16, YUV_420_888, BLOB,
             *   RAW16, 2, YUV_420_888, BLOB,
             *   ...,
             *   INPUT_FORMAT, OUTPUT_FORMAT_COUNT, [OUTPUT_0, OUTPUT_1, ..., OUTPUT_FORMAT_COUNT-1]
             * };
             */
            int length = 0;

            int[] inputs = value.getInputs();
            for (int input : inputs) {

                length += 1; // INPUT_FORMAT
                length += 1; // OUTPUT_FORMAT_COUNT

                int[] outputs = value.getOutputs(input);
                length += outputs.length; // [OUTPUT_0, OUTPUT_1, ..., OUTPUT_FORMAT_COUNT-1]
            }

            return length * SIZEOF_INT32;
        }
    }

    @Override
    public Marshaler<ReprocessFormatsMap> createMarshaler(
            TypeReference<ReprocessFormatsMap> managedType, int nativeType) {
        return new MarshalerReprocessFormatsMap(managedType, nativeType);
    }

    @Override
    public boolean isTypeMappingSupported(TypeReference<ReprocessFormatsMap> managedType,
            int nativeType) {
        return nativeType == TYPE_INT32 && managedType.getType().equals(ReprocessFormatsMap.class);
    }
}
