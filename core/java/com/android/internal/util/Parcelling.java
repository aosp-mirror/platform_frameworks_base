/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.internal.util;

import android.annotation.Nullable;
import android.os.Parcel;
import android.util.ArrayMap;

import java.util.regex.Pattern;

/**
 * Describes a 2-way parcelling contract of type {@code T} into/out of a {@link Parcel}
 *
 * @param <T> the type being [un]parcelled
 */
public interface Parcelling<T> {

    /**
     * Write an item into parcel.
     */
    void parcel(T item, Parcel dest, int parcelFlags);

    /**
     * Read an item from parcel.
     */
    T unparcel(Parcel source);


    /**
     * A registry of {@link Parcelling} singletons.
     */
    class Cache {
        private Cache() {}

        private static ArrayMap<Class, Parcelling> sCache = new ArrayMap<>();

        /**
         * Retrieves an instance of a given {@link Parcelling} class if present.
         */
        public static @Nullable <P extends Parcelling<?>> P get(Class<P> clazz) {
            return (P) sCache.get(clazz);
        }

        /**
         * Stores an instance of a given {@link Parcelling}.
         *
         * @return the provided parcelling for convenience.
         */
        public static <P extends Parcelling<?>> P put(P parcelling) {
            sCache.put(parcelling.getClass(), parcelling);
            return parcelling;
        }

        /**
         * Produces an instance of a given {@link Parcelling} class, by either retrieving a cached
         * instance or reflectively creating one.
         */
        public static <P extends Parcelling<?>> P getOrCreate(Class<P> clazz) {
            P cached = get(clazz);
            if (cached != null) {
                return cached;
            } else {
                try {
                    return put(clazz.newInstance());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    /**
     * Common {@link Parcelling} implementations.
     */
    interface BuiltIn {

        class ForPattern implements Parcelling<Pattern> {

            @Override
            public void parcel(Pattern item, Parcel dest, int parcelFlags) {
                dest.writeString(item == null ? null : item.pattern());
            }

            @Override
            public Pattern unparcel(Parcel source) {
                String s = source.readString();
                return s == null ? null : Pattern.compile(s);
            }
        }
    }
}
