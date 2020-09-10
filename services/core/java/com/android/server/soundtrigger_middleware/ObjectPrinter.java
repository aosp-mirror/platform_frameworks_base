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

package com.android.server.soundtrigger_middleware;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Map;

/**
 * A collection of pretty-print utilities for data objects.
 */
class ObjectPrinter {
    /** Default maximum elements to print in a collection. */
    static public final int kDefaultMaxCollectionLength = 16;

    /**
     * Simple version of {@link #print(Object, boolean, int)} that prints an object, without
     * recursing into sub-objects.
     *
     * @param obj The object to print.
     * @return A string representing the object.
     */
    static String print(@Nullable Object obj) {
        return print(obj, false, kDefaultMaxCollectionLength);
    }

    /**
     * Pretty-prints an object.
     *
     * @param obj                 The object to print.
     * @param deep                Whether to pretty-print sub-objects (if false, just prints them
     *                            with {@link Object#toString()}).
     * @param maxCollectionLength Whenever encountering collections, maximum number of elements to
     *                            print.
     * @return A string representing the object.
     */
    static String print(@Nullable Object obj, boolean deep, int maxCollectionLength) {
        StringBuilder builder = new StringBuilder();
        print(builder, obj, deep, maxCollectionLength);
        return builder.toString();
    }

    /**
     * This version is suitable for use inside a toString() override of an object, e.g.:
     * <pre><code>
     *     class MyObject {
     *         ...
     *         @Override
     *         String toString() {
     *             return ObjectPrinter.printPublicFields(this, ...);
     *         }
     *     }
     * </code></pre>
     *
     * @param obj                 The object to print.
     * @param deep                Whether to pretty-print sub-objects (if false, just prints them
     *                            with {@link Object#toString()}).
     * @param maxCollectionLength Whenever encountering collections, maximum number of elements to
     *                            print.
     */
    static String printPublicFields(@Nullable Object obj, boolean deep, int maxCollectionLength) {
        StringBuilder builder = new StringBuilder();
        printPublicFields(builder, obj, deep, maxCollectionLength);
        return builder.toString();
    }

    /**
     * A version of {@link #print(Object, boolean, int)} that uses a {@link StringBuilder}.
     *
     * @param builder             StringBuilder to print into.
     * @param obj                 The object to print.
     * @param deep                Whether to pretty-print sub-objects (if false, just prints them
     *                            with {@link Object#toString()}).
     * @param maxCollectionLength Whenever encountering collections, maximum number of elements to
     *                            print.
     */
    static void print(@NonNull StringBuilder builder, @Nullable Object obj, boolean deep,
            int maxCollectionLength) {
        try {
            if (obj == null) {
                builder.append("null");
                return;
            }
            if (obj instanceof Boolean) {
                builder.append(obj.toString());
                return;
            }
            if (obj instanceof Number) {
                builder.append(obj.toString());
                return;
            }
            if (obj instanceof Character) {
                builder.append('\'');
                builder.append(obj.toString());
                builder.append('\'');
                return;
            }
            if (obj instanceof String) {
                builder.append('"');
                builder.append(obj.toString());
                builder.append('"');
                return;
            }

            Class cls = obj.getClass();

            if (Collection.class.isAssignableFrom(cls)) {
                Collection collection = (Collection) obj;
                builder.append("[ ");
                int length = collection.size();
                boolean isLong = false;
                int i = 0;
                for (Object child : collection) {
                    if (i > 0) {
                        builder.append(", ");
                    }
                    if (i >= maxCollectionLength) {
                        isLong = true;
                        break;
                    }
                    print(builder, child, deep, maxCollectionLength);
                    ++i;
                }
                if (isLong) {
                    builder.append("... (+");
                    builder.append(length - maxCollectionLength);
                    builder.append(" entries)");
                }
                builder.append(" ]");
                return;
            }

            if (Map.class.isAssignableFrom(cls)) {
                Map<?, ?> map = (Map<?, ?>) obj;
                builder.append("< ");
                int length = map.size();
                boolean isLong = false;
                int i = 0;
                for (Map.Entry<?, ?> child : map.entrySet()) {
                    if (i > 0) {
                        builder.append(", ");
                    }
                    if (i >= maxCollectionLength) {
                        isLong = true;
                        break;
                    }
                    print(builder, child.getKey(), deep, maxCollectionLength);
                    builder.append(": ");
                    print(builder, child.getValue(), deep, maxCollectionLength);
                    ++i;
                }
                if (isLong) {
                    builder.append("... (+");
                    builder.append(length - maxCollectionLength);
                    builder.append(" entries)");
                }
                builder.append(" >");
                return;
            }

            if (cls.isArray()) {
                builder.append("[ ");
                int length = Array.getLength(obj);
                boolean isLong = false;
                for (int i = 0; i < length; ++i) {
                    if (i > 0) {
                        builder.append(", ");
                    }
                    if (i >= maxCollectionLength) {
                        isLong = true;
                        break;
                    }
                    print(builder, Array.get(obj, i), deep, maxCollectionLength);
                }
                if (isLong) {
                    builder.append("... (+");
                    builder.append(length - maxCollectionLength);
                    builder.append(" entries)");
                }
                builder.append(" ]");
                return;
            }

            if (!deep) {
                builder.append(obj.toString());
                return;
            }
            printPublicFields(builder, obj, deep, maxCollectionLength);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * A version of {@link #printPublicFields(Object, boolean, int)} that uses a {@link
     * StringBuilder}.
     *
     * @param obj                 The object to print.
     * @param deep                Whether to pretty-print sub-objects (if false, just prints them
     *                            with {@link Object#toString()}).
     * @param maxCollectionLength Whenever encountering collections, maximum number of elements to
     *                            print.
     */
    static void printPublicFields(@NonNull StringBuilder builder, @Nullable Object obj,
            boolean deep,
            int maxCollectionLength) {
        try {
            Class cls = obj.getClass();
            builder.append("{ ");

            boolean first = true;
            for (Field fld : cls.getDeclaredFields()) {
                int mod = fld.getModifiers();
                if ((mod & Modifier.PUBLIC) != 0 && (mod & Modifier.STATIC) == 0) {
                    if (first) {
                        first = false;
                    } else {
                        builder.append(", ");
                    }
                    builder.append(fld.getName());
                    builder.append(": ");
                    print(builder, fld.get(obj), deep, maxCollectionLength);
                }
            }
            builder.append(" }");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
