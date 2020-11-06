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

package com.android.server.vcn.util;

import android.annotation.NonNull;
import android.os.PersistableBundle;

import java.util.ArrayList;
import java.util.List;

/** @hide */
public class PersistableBundleUtils {
    private static final String LIST_KEY_FORMAT = "LIST_ITEM_%d";
    private static final String LIST_LENGTH_KEY = "LIST_LENGTH";

    /**
     * Functional interface to convert an object of the specified type to a PersistableBundle.
     *
     * @param <T> the type of the source object
     */
    public interface Serializer<T> {
        /**
         * Converts this object to a PersistableBundle.
         *
         * @return the PersistableBundle representation of this object
         */
        PersistableBundle toPersistableBundle(T obj);
    }

    /**
     * Functional interface used to create an object of the specified type from a PersistableBundle.
     *
     * @param <T> the type of the resultant object
     */
    public interface Deserializer<T> {
        /**
         * Creates an instance of specified type from a PersistableBundle representation.
         *
         * @param in the PersistableBundle representation
         * @return an instance of the specified type
         */
        T fromPersistableBundle(PersistableBundle in);
    }

    /**
     * Converts from a list of Persistable objects to a single PersistableBundle.
     *
     * <p>To avoid key collisions, NO additional key/value pairs should be added to the returned
     * PersistableBundle object.
     *
     * @param <T> the type of the objects to convert to the PersistableBundle
     * @param in the list of objects to be serialized into a PersistableBundle
     * @param serializer an implementation of the {@link Serializer} functional interface that
     *     converts an object of type T to a PersistableBundle
     */
    @NonNull
    public static <T> PersistableBundle fromList(
            @NonNull List<T> in, @NonNull Serializer<T> serializer) {
        final PersistableBundle result = new PersistableBundle();

        result.putInt(LIST_LENGTH_KEY, in.size());
        for (int i = 0; i < in.size(); i++) {
            final String key = String.format(LIST_KEY_FORMAT, i);
            result.putPersistableBundle(key, serializer.toPersistableBundle(in.get(i)));
        }
        return result;
    }

    /**
     * Converts from a PersistableBundle to a list of objects.
     *
     * @param <T> the type of the objects to convert from a PersistableBundle
     * @param in the PersistableBundle containing the persisted list
     * @param deserializer an implementation of the {@link Deserializer} functional interface that
     *     builds the relevant type of objects.
     */
    @NonNull
    public static <T> List<T> toList(
            @NonNull PersistableBundle in, @NonNull Deserializer<T> deserializer) {
        final int listLength = in.getInt(LIST_LENGTH_KEY);
        final ArrayList<T> result = new ArrayList<>(listLength);

        for (int i = 0; i < listLength; i++) {
            final String key = String.format(LIST_KEY_FORMAT, i);
            final PersistableBundle item = in.getPersistableBundle(key);

            result.add(deserializer.fromPersistableBundle(item));
        }
        return result;
    }
}
