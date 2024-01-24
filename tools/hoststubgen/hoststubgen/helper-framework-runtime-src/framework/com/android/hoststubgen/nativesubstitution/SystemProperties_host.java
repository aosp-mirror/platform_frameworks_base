/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.hoststubgen.nativesubstitution;

import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

public class SystemProperties_host {
    private static final Object sLock = new Object();

    /** Active system property values */
    @GuardedBy("sLock")
    private static Map<String, String> sValues;
    /** Predicate tested to determine if a given key can be read. */
    @GuardedBy("sLock")
    private static Predicate<String> sKeyReadablePredicate;
    /** Predicate tested to determine if a given key can be written. */
    @GuardedBy("sLock")
    private static Predicate<String> sKeyWritablePredicate;
    /** Callback to trigger when values are changed */
    @GuardedBy("sLock")
    private static Runnable sChangeCallback;

    /**
     * Reverse mapping that provides a way back to an original key from the
     * {@link System#identityHashCode(Object)} of {@link String#intern}.
     */
    @GuardedBy("sLock")
    private static SparseArray<String> sKeyHandles = new SparseArray<>();

    public static void native_init$ravenwood(Map<String, String> values,
            Predicate<String> keyReadablePredicate, Predicate<String> keyWritablePredicate,
            Runnable changeCallback) {
        synchronized (sLock) {
            sValues = Objects.requireNonNull(values);
            sKeyReadablePredicate = Objects.requireNonNull(keyReadablePredicate);
            sKeyWritablePredicate = Objects.requireNonNull(keyWritablePredicate);
            sChangeCallback = Objects.requireNonNull(changeCallback);
            sKeyHandles.clear();
        }
    }

    public static void native_reset$ravenwood() {
        synchronized (sLock) {
            sValues = null;
            sKeyReadablePredicate = null;
            sKeyWritablePredicate = null;
            sChangeCallback = null;
            sKeyHandles.clear();
        }
    }

    public static void native_set(String key, String val) {
        synchronized (sLock) {
            Objects.requireNonNull(key);
            Preconditions.requireNonNullViaRavenwoodRule(sValues);
            if (!sKeyWritablePredicate.test(key)) {
                throw new IllegalArgumentException(
                        "Write access to system property '" + key + "' denied via RavenwoodRule");
            }
            if (key.startsWith("ro.") && sValues.containsKey(key)) {
                throw new IllegalArgumentException(
                        "System property '" + key + "' already defined once; cannot redefine");
            }
            if ((val == null) || val.isEmpty()) {
                sValues.remove(key);
            } else {
                sValues.put(key, val);
            }
            sChangeCallback.run();
        }
    }

    public static String native_get(String key, String def) {
        synchronized (sLock) {
            Objects.requireNonNull(key);
            Preconditions.requireNonNullViaRavenwoodRule(sValues);
            if (!sKeyReadablePredicate.test(key)) {
                throw new IllegalArgumentException(
                        "Read access to system property '" + key + "' denied via RavenwoodRule");
            }
            return sValues.getOrDefault(key, def);
        }
    }

    public static int native_get_int(String key, int def) {
        try {
            return Integer.parseInt(native_get(key, ""));
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    public static long native_get_long(String key, long def) {
        try {
            return Long.parseLong(native_get(key, ""));
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    public static boolean native_get_boolean(String key, boolean def) {
        return parseBoolean(native_get(key, ""), def);
    }

    public static long native_find(String name) {
        synchronized (sLock) {
            Preconditions.requireNonNullViaRavenwoodRule(sValues);
            if (sValues.containsKey(name)) {
                name = name.intern();
                final int handle = System.identityHashCode(name);
                sKeyHandles.put(handle, name);
                return handle;
            } else {
                return 0;
            }
        }
    }

    public static String native_get(long handle) {
        synchronized (sLock) {
            return native_get(sKeyHandles.get((int) handle), "");
        }
    }

    public static int native_get_int(long handle, int def) {
        synchronized (sLock) {
            return native_get_int(sKeyHandles.get((int) handle), def);
        }
    }

    public static long native_get_long(long handle, long def) {
        synchronized (sLock) {
            return native_get_long(sKeyHandles.get((int) handle), def);
        }
    }

    public static boolean native_get_boolean(long handle, boolean def) {
        synchronized (sLock) {
            return native_get_boolean(sKeyHandles.get((int) handle), def);
        }
    }

    public static void native_add_change_callback() {
        // Ignored; callback always registered via init above
    }

    public static void native_report_sysprop_change() {
        // Report through callback always registered via init above
        synchronized (sLock) {
            Preconditions.requireNonNullViaRavenwoodRule(sValues);
            sChangeCallback.run();
        }
    }

    private static boolean parseBoolean(String val, boolean def) {
        // Matches system/libbase/include/android-base/parsebool.h
        if (val == null) return def;
        switch (val) {
            case "1":
            case "on":
            case "true":
            case "y":
            case "yes":
                return true;
            case "0":
            case "false":
            case "n":
            case "no":
            case "off":
                return false;
            default:
                return def;
        }
    }
}
