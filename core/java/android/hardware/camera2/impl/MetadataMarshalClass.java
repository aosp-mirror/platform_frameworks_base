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

import java.nio.ByteBuffer;

public interface MetadataMarshalClass<T> {

    /**
     * Marshal the specified object instance (value) into a byte buffer.
     *
     * @param value the value of type T that we wish to write into the byte buffer
     * @param buffer the byte buffer into which the marshalled object will be written
     * @param nativeType the native type, e.g.
     *        {@link android.hardware.camera2.impl.CameraMetadataNative#TYPE_BYTE TYPE_BYTE}.
     *        Guaranteed to be one for which isNativeTypeSupported returns true.
     * @param sizeOnly if this is true, don't write to the byte buffer. calculate the size only.
     * @return the size that needs to be written to the byte buffer
     */
    int marshal(T value, ByteBuffer buffer, int nativeType, boolean sizeOnly);

    /**
     * Unmarshal a new object instance from the byte buffer.
     * @param buffer the byte buffer, from which we will read the object
     * @param nativeType the native type, e.g.
     *        {@link android.hardware.camera2.impl.CameraMetadataNative#TYPE_BYTE TYPE_BYTE}.
     *        Guaranteed to be one for which isNativeTypeSupported returns true.
     * @return a new instance of type T read from the byte buffer
     */
    T unmarshal(ByteBuffer buffer, int nativeType);

    Class<T> getMarshalingClass();

    /**
     * Determines whether or not this marshaller supports this native type. Most marshallers
     * will are likely to only support one type.
     *
     * @param nativeType the native type, e.g.
     *        {@link android.hardware.camera2.impl.CameraMetadataNative#TYPE_BYTE TYPE_BYTE}
     * @return true if it supports, false otherwise
     */
    boolean isNativeTypeSupported(int nativeType);

    public static int NATIVE_SIZE_DYNAMIC = -1;

    /**
     * How many bytes T will take up if marshalled to/from nativeType
     * @param nativeType the native type, e.g.
     *        {@link android.hardware.camera2.impl.CameraMetadataNative#TYPE_BYTE TYPE_BYTE}
     * @return a size in bytes, or NATIVE_SIZE_DYNAMIC if the size is dynamic
     */
    int getNativeSize(int nativeType);
}
