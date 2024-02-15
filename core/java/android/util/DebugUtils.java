/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;

import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * <p>Various utilities for debugging and logging.</p>
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class DebugUtils {
    /** @hide */ public DebugUtils() {}

    /**
     * <p>Filters objects against the <code>ANDROID_OBJECT_FILTER</code>
     * environment variable. This environment variable can filter objects
     * based on their class name and attribute values.</p>
     *
     * <p>Here is the syntax for <code>ANDROID_OBJECT_FILTER</code>:</p>
     *
     * <p><code>ClassName@attribute1=value1@attribute2=value2...</code></p>
     *
     * <p>Examples:</p>
     * <ul>
     * <li>Select TextView instances: <code>TextView</code></li>
     * <li>Select TextView instances of text "Loading" and bottom offset of 22:
     * <code>TextView@text=Loading.*@bottom=22</code></li>
     * </ul>
     *
     * <p>The class name and the values are regular expressions.</p>
     *
     * <p>This class is useful for debugging and logging purpose:</p>
     * <pre>
     * if (DEBUG) {
     *   if (DebugUtils.isObjectSelected(childView) && LOGV_ENABLED) {
     *     Log.v(TAG, "Object " + childView + " logged!");
     *   }
     * }
     * </pre>
     *
     * <p><strong>NOTE</strong>: This method is very expensive as it relies
     * heavily on regular expressions and reflection. Calls to this method
     * should always be stripped out of the release binaries and avoided
     * as much as possible in debug mode.</p>
     *
     * @param object any object to match against the ANDROID_OBJECT_FILTER
     *        environement variable
     * @return true if object is selected by the ANDROID_OBJECT_FILTER
     *         environment variable, false otherwise
     */
    public static boolean isObjectSelected(Object object) {
        boolean match = false;
        String s = System.getenv("ANDROID_OBJECT_FILTER");
        if (s != null && s.length() > 0) {
            String[] selectors = s.split("@");
            // first selector == class name
            if (object.getClass().getSimpleName().matches(selectors[0])) {
                // check potential attributes
                for (int i = 1; i < selectors.length; i++) {
                    String[] pair = selectors[i].split("=");
                    Class<?> klass = object.getClass();
                    try {
                        Method declaredMethod = null;
                        Class<?> parent = klass;
                        do {
                            declaredMethod = parent.getDeclaredMethod("get" +
                                    pair[0].substring(0, 1).toUpperCase(Locale.ROOT) +
                                    pair[0].substring(1),
                                    (Class[]) null);
                        } while ((parent = klass.getSuperclass()) != null &&
                                declaredMethod == null);

                        if (declaredMethod != null) {
                            Object value = declaredMethod
                                    .invoke(object, (Object[])null);
                            match |= (value != null ?
                                    value.toString() : "null").matches(pair[1]);
                        }
                    } catch (NoSuchMethodException e) {
                        e.printStackTrace();
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    } catch (InvocationTargetException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        return match;
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public static void buildShortClassTag(Object cls, StringBuilder out) {
        if (cls == null) {
            out.append("null");
        } else {
            String simpleName = cls.getClass().getSimpleName();
            if (simpleName == null || simpleName.isEmpty()) {
                simpleName = cls.getClass().getName();
                int end = simpleName.lastIndexOf('.');
                if (end > 0) {
                    simpleName = simpleName.substring(end+1);
                }
            }
            out.append(simpleName);
            out.append('{');
            out.append(Integer.toHexString(System.identityHashCode(cls)));
        }
    }

    /** @hide */
    public static void printSizeValue(PrintWriter pw, long number) {
        float result = number;
        String suffix = "";
        if (result > 900) {
            suffix = "KB";
            result = result / 1024;
        }
        if (result > 900) {
            suffix = "MB";
            result = result / 1024;
        }
        if (result > 900) {
            suffix = "GB";
            result = result / 1024;
        }
        if (result > 900) {
            suffix = "TB";
            result = result / 1024;
        }
        if (result > 900) {
            suffix = "PB";
            result = result / 1024;
        }
        String value;
        if (result < 1) {
            value = String.format("%.2f", result);
        } else if (result < 10) {
            value = String.format("%.1f", result);
        } else if (result < 100) {
            value = String.format("%.0f", result);
        } else {
            value = String.format("%.0f", result);
        }
        pw.print(value);
        pw.print(suffix);
    }

    /** @hide */
    public static String sizeValueToString(long number, StringBuilder outBuilder) {
        if (outBuilder == null) {
            outBuilder = new StringBuilder(32);
        }
        float result = number;
        String suffix = "";
        if (result > 900) {
            suffix = "KB";
            result = result / 1024;
        }
        if (result > 900) {
            suffix = "MB";
            result = result / 1024;
        }
        if (result > 900) {
            suffix = "GB";
            result = result / 1024;
        }
        if (result > 900) {
            suffix = "TB";
            result = result / 1024;
        }
        if (result > 900) {
            suffix = "PB";
            result = result / 1024;
        }
        String value;
        if (result < 1) {
            value = String.format("%.2f", result);
        } else if (result < 10) {
            value = String.format("%.1f", result);
        } else if (result < 100) {
            value = String.format("%.0f", result);
        } else {
            value = String.format("%.0f", result);
        }
        outBuilder.append(value);
        outBuilder.append(suffix);
        return outBuilder.toString();
    }

    /**
     * Use prefixed constants (static final values) on given class to turn value
     * into human-readable string.
     *
     * @hide
     */
    public static String valueToString(Class<?> clazz, String prefix, int value) {
        for (Field field : clazz.getDeclaredFields()) {
            final int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)
                    && field.getType().equals(int.class) && field.getName().startsWith(prefix)) {
                try {
                    if (value == field.getInt(null)) {
                        return constNameWithoutPrefix(prefix, field);
                    }
                } catch (IllegalAccessException ignored) {
                }
            }
        }
        return Integer.toString(value);
    }

    /**
     * Use prefixed constants (static final values) on given class to turn flags
     * into human-readable string.
     *
     * @hide
     */
    public static String flagsToString(Class<?> clazz, String prefix, long flags) {
        final StringBuilder res = new StringBuilder();
        boolean flagsWasZero = flags == 0;

        for (Field field : clazz.getDeclaredFields()) {
            final int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)
                    && (field.getType().equals(int.class) || field.getType().equals(long.class))
                    && field.getName().startsWith(prefix)) {
                final long value = getFieldValue(field);
                if (value == 0 && flagsWasZero) {
                    return constNameWithoutPrefix(prefix, field);
                }
                if (value != 0 && (flags & value) == value) {
                    flags &= ~value;
                    res.append(constNameWithoutPrefix(prefix, field)).append('|');
                }
            }
        }
        if (flags != 0 || res.length() == 0) {
            res.append(Long.toHexString(flags));
        } else {
            res.deleteCharAt(res.length() - 1);
        }
        return res.toString();
    }

    private static long getFieldValue(Field field) {
        // Field could be int or long
        try {
            final long longValue = field.getLong(null);
            if (longValue != 0) {
                return longValue;
            }
            final int intValue = field.getInt(null);
            if (intValue != 0) {
                return intValue;
            }
        } catch (IllegalAccessException ignored) {
        }
        return 0;
    }

    /**
     * Gets human-readable representation of constants (static final values).
     *
     * @hide
     */
    public static String constantToString(Class<?> clazz, String prefix, int value) {
        for (Field field : clazz.getDeclaredFields()) {
            final int modifiers = field.getModifiers();
            try {
                if (Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)
                        && field.getType().equals(int.class) && field.getName().startsWith(prefix)
                        && field.getInt(null) == value) {
                    return constNameWithoutPrefix(prefix, field);
                }
            } catch (IllegalAccessException ignored) {
            }
        }
        return prefix + Integer.toString(value);
    }

    private static String constNameWithoutPrefix(String prefix, Field field) {
        return field.getName().substring(prefix.length());
    }

    /**
     * Returns method names from current stack trace, where {@link StackTraceElement#getClass}
     * starts with the given classes name
     *
     * @hide
     */
    public static List<String> callersWithin(Class<?> cls, int offset) {
        List<String> result = Arrays.stream(Thread.currentThread().getStackTrace())
                .skip(offset + 3)
                .filter(st -> st.getClassName().startsWith(cls.getName()))
                .map(StackTraceElement::getMethodName)
                .collect(Collectors.toList());
        Collections.reverse(result);
        return result;
    }
}
