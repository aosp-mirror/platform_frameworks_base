/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.os.Message;
import android.util.Log;
import android.util.SparseArray;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Static utility class for dealing with {@link Message} objects.
 */
// Exported to Mainline modules; cannot use annotations
// @android.ravenwood.annotation.RavenwoodKeepWholeClass
public class MessageUtils {

    private static final String TAG = MessageUtils.class.getSimpleName();
    private static final boolean DBG = false;

    /** Thrown when two different constants have the same value. */
    public static class DuplicateConstantError extends Error {
        private DuplicateConstantError() {}
        public DuplicateConstantError(String name1, String name2, int value) {
            super(String.format("Duplicate constant value: both %s and %s = %d",
                name1, name2, value));
        }
    }

    /**
     * Finds the names of integer constants. Searches the specified {@code classes}, looking for
     * accessible static integer fields whose names begin with one of the specified
     * {@code prefixes}.
     *
     * @param classes the classes to examine.
     * @param prefixes only consider fields names starting with one of these prefixes.
     * @return a {@link SparseArray} mapping integer constants to their names.
     */
    public static SparseArray<String> findMessageNames(Class[] classes, String[] prefixes) {
        SparseArray<String> messageNames = new SparseArray<>();
        for (Class c : classes) {
            String className = c.getName();
            if (DBG) Log.d(TAG, "Examining class " + className);

            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (SecurityException e) {
                Log.e(TAG, "Can't list fields of class " + className);
                continue;
            }

            for (Field field : fields) {
                int modifiers = field.getModifiers();
                if (!Modifier.isStatic(modifiers) | !Modifier.isFinal(modifiers)) {
                    continue;
                }

                String name = field.getName();
                for (String prefix : prefixes) {
                    // Does this look like a constant?
                    if (!name.startsWith(prefix)) {
                        continue;
                    }

                    try {
                        // TODO: can we have the caller try to access the field instead, so we don't
                        // expose constants it does not have access to?
                        field.setAccessible(true);

                        // Fetch the constant's value.
                        int value;
                        try {
                            value = field.getInt(null);
                        } catch (IllegalArgumentException | ExceptionInInitializerError e) {
                            // The field is not an integer (or short or byte), or c's static
                            // initializer failed and we have no idea what its value is.
                            // Either way, give up on this field.
                            break;
                        }

                        // Check for duplicate values.
                        String previousName = messageNames.get(value);
                        if (previousName != null && !previousName.equals(name)) {
                            throw new DuplicateConstantError(name, previousName, value);
                        }

                        messageNames.put(value, name);
                        if (DBG) {
                            Log.d(TAG, String.format("Found constant: %s.%s = %d",
                                    className, name, value));
                        }
                    } catch (SecurityException | IllegalAccessException e) {
                        // Not allowed to make the field accessible, or no access. Ignore.
                        continue;
                    }
                }
            }
        }
        return messageNames;
    }

    /**
     * Default prefixes for constants.
     */
    public static final String[] DEFAULT_PREFIXES = {"CMD_", "EVENT_"};

    /**
     * Finds the names of integer constants. Searches the specified {@code classes}, looking for
     * accessible static integer values whose names begin with {@link #DEFAULT_PREFIXES}.
     *
     * @param classNames the classes to examine.
     * @return a {@link SparseArray} mapping integer constants to their names.
     */
    public static SparseArray<String> findMessageNames(Class[] classNames) {
        return findMessageNames(classNames, DEFAULT_PREFIXES);
    }
}

