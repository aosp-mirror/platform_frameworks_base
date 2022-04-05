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
package android.hardware.camera2.marshal;

import android.hardware.camera2.utils.TypeReference;

import java.nio.ByteBuffer;

import static android.hardware.camera2.marshal.MarshalHelpers.*;
import static com.android.internal.util.Preconditions.*;

/**
 * Base class to marshal data to/from managed/native metadata byte buffers.
 *
 * <p>This class should not be created directly; an instance of it can be obtained
 * using {@link MarshalQueryable#createMarshaler} for the same type {@code T} if the native type
 * mapping for {@code T} {@link MarshalQueryable#isTypeMappingSupported supported}.</p>
 *
 * @param <T> the compile-time managed type
 */
public abstract class Marshaler<T> {

    protected final TypeReference<T> mTypeReference;
    protected final int mNativeType;

    /**
     * Instantiate a marshaler between a single managed/native type combination.
     *
     * <p>This particular managed/native type combination must be supported by
     * {@link #isTypeMappingSupported}.</p>
     *
     * @param query an instance of {@link MarshalQueryable}
     * @param typeReference the managed type reference
     *        Must be one for which {@link #isTypeMappingSupported} returns {@code true}
     * @param nativeType the native type, e.g.
     *        {@link android.hardware.camera2.impl.CameraMetadataNative#TYPE_BYTE TYPE_BYTE}.
     *        Must be one for which {@link #isTypeMappingSupported} returns {@code true}.
     *
     * @throws NullPointerException if any args were {@code null}
     * @throws UnsupportedOperationException if the type mapping was not supported
     */
    protected Marshaler(
            MarshalQueryable<T> query, TypeReference<T> typeReference, int nativeType) {
        mTypeReference = checkNotNull(typeReference, "typeReference must not be null");
        mNativeType = checkNativeType(nativeType);

        if (!query.isTypeMappingSupported(typeReference, nativeType)) {
            throw new UnsupportedOperationException(
                    "Unsupported type marshaling for managed type "
                            + typeReference + " and native type "
                            + MarshalHelpers.toStringNativeType(nativeType));
        }
    }

    /**
     * Marshal the specified object instance (value) into a byte buffer.
     *
     * <p>Upon completion, the {@link ByteBuffer#position()} will have advanced by
     * the {@link #calculateMarshalSize marshal size} of {@code value}.</p>
     *
     * @param value the value of type T that we wish to write into the byte buffer
     * @param buffer the byte buffer into which the marshaled object will be written
     */
    public abstract void marshal(T value, ByteBuffer buffer);

    /**
     * Get the size in bytes for how much space would be required to write this {@code value}
     * into a byte buffer using the given {@code nativeType}.
     *
     * <p>If the size of this {@code T} instance when serialized into a buffer is always constant,
     * then this method will always return the same value (and particularly, it will return
     * an equivalent value to {@link #getNativeSize()}.</p>
     *
     * <p>Overriding this method is a must when the size is {@link NATIVE_SIZE_DYNAMIC dynamic}.</p>
     *
     * @param value the value of type T that we wish to write into the byte buffer
     * @return the size that would need to be written to the byte buffer
     */
    public int calculateMarshalSize(T value) {
        int nativeSize = getNativeSize();

        if (nativeSize == NATIVE_SIZE_DYNAMIC) {
            throw new AssertionError("Override this function for dynamically-sized objects");
        }

        return nativeSize;
    }

    /**
     * Unmarshal a new object instance from the byte buffer into its managed type.
     *
     * <p>Upon completion, the {@link ByteBuffer#position()} will have advanced by
     * the {@link #calculateMarshalSize marshal size} of the returned {@code T} instance.</p>
     *
     * @param buffer the byte buffer, from which we will read the object
     * @return a new instance of type T read from the byte buffer
     */
    public abstract T unmarshal(ByteBuffer buffer);

    /**
     * Used to denote variable-length data structures.
     *
     * <p>If the size is dynamic then we can't know ahead of time how big of a data structure
     * to preallocate for e.g. arrays, so one object must be unmarshaled at a time.</p>
     */
    public static int NATIVE_SIZE_DYNAMIC = -1;

    /**
     * How many bytes a single instance of {@code T} will take up if marshalled to/from
     * {@code nativeType}.
     *
     * <p>When unmarshaling data from native to managed, the instance {@code T} is not yet
     * available. If the native size is always a fixed mapping regardless of the instance of
     * {@code T} (e.g. if the type is not a container of some sort), it can be used to preallocate
     * containers for {@code T} to avoid resizing them.</p>
     *
     * <p>In particular, the array marshaler takes advantage of this (when size is not dynamic)
     * to preallocate arrays of the right length when unmarshaling an array {@code T[]}.</p>
     *
     * @return a size in bytes, or {@link #NATIVE_SIZE_DYNAMIC} if the size is dynamic
     */
    public abstract int getNativeSize();

    /**
     * The type reference for {@code T} for the managed type side of this marshaler.
     */
    public TypeReference<T> getTypeReference() {
        return mTypeReference;
    }

    /** The native type corresponding to this marshaler for the native side of this marshaler.*/
    public int getNativeType() {
        return mNativeType;
    }
}
