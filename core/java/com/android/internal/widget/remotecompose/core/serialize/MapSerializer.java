/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.serialize;

import android.annotation.Nullable;

import java.util.List;
import java.util.Map;

/** Represents a serializer for a map */
public interface MapSerializer {

    /**
     * Add metadata to this map for filtering by the data format generator.
     *
     * @param value A set of tags to add
     */
    MapSerializer addTags(SerializeTags... value);

    /**
     * Add a list entry to this map. The List values can be any primitive, List, Map, or
     * Serializable
     *
     * @param key The key
     * @param value The list
     */
    <T> MapSerializer add(String key, @Nullable List<T> value);

    /**
     * Add a map entry to this map. The map values can be any primitive, List, Map, or Serializable
     *
     * @param key The key
     * @param value The list
     */
    <T> MapSerializer add(String key, @Nullable Map<String, T> value);

    /**
     * Adds any Serializable type to this map
     *
     * @param key The key
     * @param value The Serializable
     */
    MapSerializer add(String key, @Nullable Serializable value);

    /**
     * Adds a String entry
     *
     * @param key The key
     * @param value The String
     */
    MapSerializer add(String key, @Nullable String value);

    /**
     * Adds a color entry
     *
     * @param key The key
     * @param a Alpha value [0, 1]
     * @param r Red value [0, 1]
     * @param g Green value [0, 1]
     * @param b Blue value [0, 1]
     */
    MapSerializer add(String key, float a, float r, float g, float b);

    /**
     * Adds an ID and Value pair. This can be either a value or variable.
     *
     * @param key The key
     * @param id Maybe float NaN ID
     * @param value Maybe value
     */
    MapSerializer add(String key, float id, float value);

    /**
     * Adds a Byte entry
     *
     * @param key The key
     * @param value The Byte
     */
    MapSerializer add(String key, @Nullable Byte value);

    /**
     * Adds a Short entry
     *
     * @param key The key
     * @param value The Short
     */
    MapSerializer add(String key, @Nullable Short value);

    /**
     * Adds an Integer entry
     *
     * @param key The key
     * @param value The Integer
     */
    MapSerializer add(String key, @Nullable Integer value);

    /**
     * Adds a Long entry
     *
     * @param key The key
     * @param value The Long
     */
    MapSerializer add(String key, @Nullable Long value);

    /**
     * Adds a Float entry
     *
     * @param key The key
     * @param value The Float
     */
    MapSerializer add(String key, @Nullable Float value);

    /**
     * Adds a Double entry
     *
     * @param key The key
     * @param value The Double
     */
    MapSerializer add(String key, @Nullable Double value);

    /**
     * Adds a Boolean entry
     *
     * @param key The key
     * @param value The Boolean
     */
    MapSerializer add(String key, @Nullable Boolean value);

    /**
     * Adds a Enum entry
     *
     * @param key The key
     * @param value The Enum
     */
    <T extends Enum<T>> MapSerializer add(String key, @Nullable Enum<T> value);
}
