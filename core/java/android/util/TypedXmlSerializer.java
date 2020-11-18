/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.util;

import android.annotation.NonNull;
import android.annotation.Nullable;

import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;

/**
 * Specialization of {@link XmlSerializer} which adds explicit methods to
 * support consistent and efficient conversion of primitive data types.
 *
 * @hide
 */
public interface TypedXmlSerializer extends XmlSerializer {
    /**
     * Functionally equivalent to {@link #attribute(String, String, String)} but
     * with the additional signal that the given value is a candidate for being
     * canonicalized, similar to {@link String#intern()}.
     */
    @NonNull XmlSerializer attributeInterned(@Nullable String namespace, @NonNull String name,
            @NonNull String value) throws IOException;

    /**
     * Encode the given strongly-typed value and serialize using
     * {@link #attribute(String, String, String)}.
     */
    @NonNull XmlSerializer attributeBytesHex(@Nullable String namespace, @NonNull String name,
            @NonNull byte[] value) throws IOException;

    /**
     * Encode the given strongly-typed value and serialize using
     * {@link #attribute(String, String, String)}.
     */
    @NonNull XmlSerializer attributeBytesBase64(@Nullable String namespace, @NonNull String name,
            @NonNull byte[] value) throws IOException;

    /**
     * Encode the given strongly-typed value and serialize using
     * {@link #attribute(String, String, String)}.
     */
    @NonNull XmlSerializer attributeInt(@Nullable String namespace, @NonNull String name,
            int value) throws IOException;

    /**
     * Encode the given strongly-typed value and serialize using
     * {@link #attribute(String, String, String)}.
     */
    @NonNull XmlSerializer attributeIntHex(@Nullable String namespace, @NonNull String name,
            int value) throws IOException;

    /**
     * Encode the given strongly-typed value and serialize using
     * {@link #attribute(String, String, String)}.
     */
    @NonNull XmlSerializer attributeLong(@Nullable String namespace, @NonNull String name,
            long value) throws IOException;

    /**
     * Encode the given strongly-typed value and serialize using
     * {@link #attribute(String, String, String)}.
     */
    @NonNull XmlSerializer attributeLongHex(@Nullable String namespace, @NonNull String name,
            long value) throws IOException;

    /**
     * Encode the given strongly-typed value and serialize using
     * {@link #attribute(String, String, String)}.
     */
    @NonNull XmlSerializer attributeFloat(@Nullable String namespace, @NonNull String name,
            float value) throws IOException;

    /**
     * Encode the given strongly-typed value and serialize using
     * {@link #attribute(String, String, String)}.
     */
    @NonNull XmlSerializer attributeDouble(@Nullable String namespace, @NonNull String name,
            double value) throws IOException;

    /**
     * Encode the given strongly-typed value and serialize using
     * {@link #attribute(String, String, String)}.
     */
    @NonNull XmlSerializer attributeBoolean(@Nullable String namespace, @NonNull String name,
            boolean value) throws IOException;
}
