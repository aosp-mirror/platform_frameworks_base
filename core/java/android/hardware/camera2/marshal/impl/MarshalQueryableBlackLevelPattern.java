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

import android.hardware.camera2.marshal.MarshalQueryable;
import android.hardware.camera2.marshal.Marshaler;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.utils.TypeReference;

import java.nio.ByteBuffer;

import static android.hardware.camera2.impl.CameraMetadataNative.TYPE_INT32;
import static android.hardware.camera2.marshal.MarshalHelpers.SIZEOF_INT32;

/**
 * Marshal {@link BlackLevelPattern} to/from {@link #TYPE_INT32} {@code x 4}
 */
public class MarshalQueryableBlackLevelPattern implements MarshalQueryable<BlackLevelPattern> {
    private static final int SIZE = SIZEOF_INT32 * BlackLevelPattern.COUNT;

    private class MarshalerBlackLevelPattern extends Marshaler<BlackLevelPattern> {
        protected MarshalerBlackLevelPattern(TypeReference<BlackLevelPattern> typeReference,
                                               int nativeType) {
            super(MarshalQueryableBlackLevelPattern.this, typeReference, nativeType);
        }

        @Override
        public void marshal(BlackLevelPattern value, ByteBuffer buffer) {
            for (int i = 0; i < BlackLevelPattern.COUNT / 2; ++i) {
                for (int j = 0; j < BlackLevelPattern.COUNT / 2; ++j) {
                    buffer.putInt(value.getOffsetForIndex(j, i));
                }
            }
        }

        @Override
        public BlackLevelPattern unmarshal(ByteBuffer buffer) {
            int[] channelOffsets = new int[BlackLevelPattern.COUNT];
            for (int i = 0; i < BlackLevelPattern.COUNT; ++i) {
                channelOffsets[i] = buffer.getInt();
            }
            return new BlackLevelPattern(channelOffsets);
        }

        @Override
        public int getNativeSize() {
            return SIZE;
        }
    }

    @Override
    public Marshaler<BlackLevelPattern> createMarshaler(
            TypeReference<BlackLevelPattern> managedType, int nativeType) {
        return new MarshalerBlackLevelPattern(managedType, nativeType);
    }

    @Override
    public boolean isTypeMappingSupported(
            TypeReference<BlackLevelPattern> managedType, int nativeType) {
        return nativeType == TYPE_INT32 &&
                (BlackLevelPattern.class.equals(managedType.getType()));
    }
}
