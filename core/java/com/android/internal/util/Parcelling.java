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

import static java.util.Collections.emptySet;

import android.annotation.Nullable;
import android.os.Parcel;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Describes a 2-way parcelling contract of type {@code T} into/out of a {@link Parcel}
 *
 * Implementations should be stateless.
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
            // No synchronization - creating an extra instance in a race case is ok
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

        class ForInternedString implements Parcelling<String> {
            @Override
            public void parcel(@Nullable String item, Parcel dest, int parcelFlags) {
                dest.writeString(item);
            }

            @Nullable
            @Override
            public String unparcel(Parcel source) {
                return TextUtils.safeIntern(source.readString());
            }
        }

        class ForInternedStringArray implements Parcelling<String[]> {
            @Override
            public void parcel(String[] item, Parcel dest, int parcelFlags) {
                dest.writeStringArray(item);
            }

            @Nullable
            @Override
            public String[] unparcel(Parcel source) {
                String[] array = source.readStringArray();
                if (array != null) {
                    int size = ArrayUtils.size(array);
                    for (int index = 0; index < size; index++) {
                        array[index] = TextUtils.safeIntern(array[index]);
                    }
                }
                return array;
            }
        }

        class ForInternedStringList implements Parcelling<List<String>> {
            @Override
            public void parcel(List<String> item, Parcel dest, int parcelFlags) {
                dest.writeStringList(item);
            }

            @Override
            public List<String> unparcel(Parcel source) {
                ArrayList<String> list = source.createStringArrayList();
                if (list != null) {
                    int size = list.size();
                    for (int index = 0; index < size; index++) {
                        list.set(index, list.get(index).intern());
                    }
                }
                return CollectionUtils.emptyIfNull(list);
            }
        }

        class ForInternedStringValueMap implements Parcelling<Map<String, String>> {
            @Override
            public void parcel(Map<String, String> item, Parcel dest, int parcelFlags) {
                dest.writeMap(item);
            }

            @Override
            public Map<String, String> unparcel(Parcel source) {
                ArrayMap<String, String> map = new ArrayMap<>();
                source.readMap(map, String.class.getClassLoader());
                for (int index = 0; index < map.size(); index++) {
                    map.setValueAt(index, TextUtils.safeIntern(map.valueAt(index)));
                }
                return map;
            }
        }

        class ForStringSet implements Parcelling<Set<String>> {
            @Override
            public void parcel(Set<String> item, Parcel dest, int parcelFlags) {
                if (item == null) {
                    dest.writeInt(-1);
                } else {
                    dest.writeInt(item.size());
                    for (String string : item) {
                        dest.writeString(string);
                    }
                }
            }

            @Override
            public Set<String> unparcel(Parcel source) {
                final int size = source.readInt();
                if (size < 0) {
                    return emptySet();
                }
                Set<String> set = new ArraySet<>();
                for (int count = 0; count < size; count++) {
                    set.add(source.readString());
                }
                return set;
            }
        }

        class ForInternedStringSet implements Parcelling<Set<String>> {
            @Override
            public void parcel(Set<String> item, Parcel dest, int parcelFlags) {
                if (item == null) {
                    dest.writeInt(-1);
                } else {
                    dest.writeInt(item.size());
                    for (String string : item) {
                        dest.writeString(string);
                    }
                }
            }

            @Override
            public Set<String> unparcel(Parcel source) {
                final int size = source.readInt();
                if (size < 0) {
                    return emptySet();
                }
                Set<String> set = new ArraySet<>();
                for (int count = 0; count < size; count++) {
                    set.add(TextUtils.safeIntern(source.readString()));
                }
                return set;
            }
        }

        class ForInternedStringArraySet implements Parcelling<ArraySet<String>> {
            @Override
            public void parcel(ArraySet<String> item, Parcel dest, int parcelFlags) {
                if (item == null) {
                    dest.writeInt(-1);
                } else {
                    dest.writeInt(item.size());
                    for (String string : item) {
                        dest.writeString(string);
                    }
                }
            }

            @Override
            public ArraySet<String> unparcel(Parcel source) {
                final int size = source.readInt();
                if (size < 0) {
                  return null;
                }
                ArraySet<String> set = new ArraySet<>();
                for (int count = 0; count < size; count++) {
                    set.add(TextUtils.safeIntern(source.readString()));
                }
                return set;
            }
        }

        class ForBoolean implements Parcelling<Boolean> {
            @Override
            public void parcel(@Nullable Boolean item, Parcel dest, int parcelFlags) {
                if (item == null) {
                    // This writes 1 for null to mirror TypedArray.getInteger(booleanResId, 1)
                    dest.writeInt(1);
                } else if (!item) {
                    dest.writeInt(0);
                } else {
                    dest.writeInt(-1);
                }
            }

            @Nullable
            @Override
            public Boolean unparcel(Parcel source) {
                switch (source.readInt()) {
                    default:
                        throw new IllegalStateException("Malformed Parcel reading Boolean: "
                                + source);
                    case 1:
                        return null;
                    case 0:
                        return Boolean.FALSE;
                    case -1:
                        return Boolean.TRUE;
                }
            }
        }

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
