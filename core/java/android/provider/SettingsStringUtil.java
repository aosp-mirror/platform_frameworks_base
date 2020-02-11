/*
 * Copyright (C) 2017 The Android Open Source Project
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


package android.provider;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.text.TextUtils;

import com.android.internal.util.ArrayUtils;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.function.Function;

/**
 * Utilities for dealing with {@link String} values in {@link Settings}
 *
 * @hide
 */
public class SettingsStringUtil {
    private SettingsStringUtil() {}

    public static final String DELIMITER = ":";

    /**
     * A {@link HashSet} of items, that uses a common convention of setting string
     * serialization/deserialization of separating multiple items with {@link #DELIMITER}
     */
    public static abstract class ColonDelimitedSet<T> extends HashSet<T> {

        public ColonDelimitedSet(String colonSeparatedItems) {
            for (String cn :
                    TextUtils.split(TextUtils.emptyIfNull(colonSeparatedItems), DELIMITER)) {
                add(itemFromString(cn));
            }
        }

        protected abstract T itemFromString(String s);
        protected String itemToString(T item) {
            return String.valueOf(item);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            Iterator<T> it = iterator();
            if (it.hasNext()) {
                sb.append(itemToString(it.next()));
                while (it.hasNext()) {
                    sb.append(DELIMITER);
                    sb.append(itemToString(it.next()));
                }
            }
            return sb.toString();
        }


        public static class OfStrings extends ColonDelimitedSet<String> {
            public OfStrings(String colonSeparatedItems) {
                super(colonSeparatedItems);
            }

            @Override
            protected String itemFromString(String s) {
                return s;
            }

            public static String addAll(String delimitedElements, Collection<String> elements) {
                final ColonDelimitedSet<String> set
                        = new ColonDelimitedSet.OfStrings(delimitedElements);
                return set.addAll(elements) ? set.toString() : delimitedElements;
            }

            public static String add(String delimitedElements, String element) {
                final ColonDelimitedSet<String> set
                        = new ColonDelimitedSet.OfStrings(delimitedElements);
                if (set.contains(element)) {
                    return delimitedElements;
                }
                set.add(element);
                return set.toString();
            }

            public static String remove(String delimitedElements, String element) {
                final ColonDelimitedSet<String> set
                        = new ColonDelimitedSet.OfStrings(delimitedElements);
                if (!set.contains(element)) {
                    return delimitedElements;
                }
                set.remove(element);
                return set.toString();
            }

            public static boolean contains(String delimitedElements, String element) {
                final String[] elements = TextUtils.split(delimitedElements, DELIMITER);
                return ArrayUtils.indexOf(elements, element) != -1;
            }
        }
    }

    public static class ComponentNameSet extends ColonDelimitedSet<ComponentName> {
        public ComponentNameSet(String colonSeparatedPackageNames) {
            super(colonSeparatedPackageNames);
        }

        @Override
        protected ComponentName itemFromString(String s) {
            return ComponentName.unflattenFromString(s);
        }

        @Override
        protected String itemToString(ComponentName item) {
            return item != null ? item.flattenToString() : "null";
        }

        public static String add(String delimitedElements, ComponentName element) {
            final ComponentNameSet set = new ComponentNameSet(delimitedElements);
            if (set.contains(element)) {
                return delimitedElements;
            }
            set.add(element);
            return set.toString();
        }

        public static String remove(String delimitedElements, ComponentName element) {
            final ComponentNameSet set = new ComponentNameSet(delimitedElements);
            if (!set.contains(element)) {
                return delimitedElements;
            }
            set.remove(element);
            return set.toString();
        }

        public static boolean contains(String delimitedElements, ComponentName element) {
            return ColonDelimitedSet.OfStrings.contains(
                    delimitedElements, element.flattenToString());
        }
    }

    public static class SettingStringHelper {
        private final ContentResolver mContentResolver;
        private final String mSettingName;
        private final int mUserId;

        public SettingStringHelper(ContentResolver contentResolver, String name, int userId) {
            mContentResolver = contentResolver;
            mUserId = userId;
            mSettingName = name;
        }

        public String read() {
            return Settings.Secure.getStringForUser(
                    mContentResolver, mSettingName, mUserId);
        }

        public boolean write(String value) {
            return Settings.Secure.putStringForUser(
                    mContentResolver, mSettingName, value, mUserId);
        }

        public boolean modify(Function<String, String> change) {
            return write(change.apply(read()));
        }
    }
}
