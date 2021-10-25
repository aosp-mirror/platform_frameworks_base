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

/**
 * Query if a marshaler can marshal to/from a particular native and managed type; if it supports
 * the combination, allow creating a marshaler instance to do the serialization.
 *
 * <p>Not all queryable instances will support exactly one combination. Some, such as the
 * primitive queryable will support all primitive to/from managed mappings (as long as they are
 * 1:1). Others, such as the rectangle queryable will only support integer to rectangle mappings.
 * </p>
 *
 * <p>Yet some others are codependent on other queryables; e.g. array queryables might only support
 * a type map for {@code T[]} if another queryable exists with support for the component type
 * {@code T}.</p>
 */
public interface MarshalQueryable<T> {
    /**
     * Create a marshaler between the selected managed and native type.
     *
     * <p>This marshaler instance is only good for that specific type mapping; and will refuse
     * to map other managed types, other native types, or an other combination that isn't
     * this exact one.</p>
     *
     * @param managedType a managed type reference
     * @param nativeType the native type, e.g.
     *          {@link android.hardware.camera2.impl.CameraMetadataNative#TYPE_BYTE TYPE_BYTE}
     * @return
     *
     * @throws UnsupportedOperationException
     *          if {@link #isTypeMappingSupported} returns {@code false}
     */
    public Marshaler<T> createMarshaler(
            TypeReference<T> managedType, int nativeType);

    /**
     * Determine whether or not this query marshal is able to create a marshaler that will
     * support the managed type and native type mapping.
     *
     * <p>If this returns {@code true}, then a marshaler can be instantiated by
     * {@link #createMarshaler} that will marshal data to/from the native type
     * from/to the managed type.</p>
     *
     * <p>Most marshalers are likely to only support one type map.</p>
     */
    public boolean isTypeMappingSupported(TypeReference<T> managedType, int nativeType);
}
