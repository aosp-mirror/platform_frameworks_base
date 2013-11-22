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

import android.graphics.Rect;

import java.nio.ByteBuffer;

public class MetadataMarshalRect implements MetadataMarshalClass<Rect> {
    private static final int SIZE = 16;

    @Override
    public int marshal(Rect value, ByteBuffer buffer, int nativeType, boolean sizeOnly) {
        if (sizeOnly) {
            return SIZE;
        }

        buffer.putInt(value.left);
        buffer.putInt(value.top);
        buffer.putInt(value.width());
        buffer.putInt(value.height());

        return SIZE;
    }

    @Override
    public Rect unmarshal(ByteBuffer buffer, int nativeType) {

        int left = buffer.getInt();
        int top = buffer.getInt();
        int width = buffer.getInt();
        int height = buffer.getInt();

        int right = left + width;
        int bottom = top + height;

        return new Rect(left, top, right, bottom);
    }

    @Override
    public Class<Rect> getMarshalingClass() {
        return Rect.class;
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
