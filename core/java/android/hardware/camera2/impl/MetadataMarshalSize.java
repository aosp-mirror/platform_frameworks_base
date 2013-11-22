/*
 * Copyright (C) 2013 The Android Open Source Project
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
package android.hardware.camera2.impl;

import android.hardware.camera2.Size;

import java.nio.ByteBuffer;

public class MetadataMarshalSize implements MetadataMarshalClass<Size> {

    private static final int SIZE = 8;

    @Override
    public int marshal(Size value, ByteBuffer buffer, int nativeType, boolean sizeOnly) {
        if (sizeOnly) {
            return SIZE;
        }

        buffer.putInt(value.getWidth());
        buffer.putInt(value.getHeight());

        return SIZE;
    }

    @Override
    public Size unmarshal(ByteBuffer buffer, int nativeType) {
        int width = buffer.getInt();
        int height = buffer.getInt();

        return new Size(width, height);
    }

    @Override
    public Class<Size> getMarshalingClass() {
        return Size.class;
    }

    @Override
    public boolean isNativeTypeSupported(int nativeType) {
        return nativeType == CameraMetadataNative.TYPE_INT32;
    }

    @Override
    public int getNativeSize(int nativeType) {
        return SIZE;
    }
}
