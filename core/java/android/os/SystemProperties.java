/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.util.Log;
import android.util.MutableInt;

import com.android.internal.annotations.GuardedBy;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

import libcore.util.HexEncoding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Gives access to the system properties store.  The system properties
 * store contains a list of string key-value pairs.
 *
 * <p>Use this class only for the system properties that are local. e.g., within
 * an app, a partition, or a module. For system properties used across the
 * boundaries, formally define them in <code>*.sysprop</code> files and use the
 * auto-generated methods. For more information, see <a href=
 * "https://source.android.com/devices/architecture/sysprops-apis">Implementing
 * System Properties as APIs</a>.</p>
 *
 * {@hide}
 */
@SystemApi
public class SystemProperties {
    private static final String TAG = "SystemProperties";
    private static final boolean TRACK_KEY_ACCESS = false;

    /**
     * Android O removed the property name length limit, but com.amazon.kindle 7.8.1.5
     * uses reflection to read this whenever text is selected (http://b/36095274).
     * @hide
     */
    @UnsupportedAppUsage(trackingBug = 172649311)
    public static final int PROP_NAME_MAX = Integer.MAX_VALUE;

    /** @hide */
    public static final int PROP_VALUE_MAX = 91;

    @UnsupportedAppUsage
    @GuardedBy("sChangeCallbacks")
    private static final ArrayList<Runnable> sChangeCallbacks = new ArrayList<Runnable>();

    @GuardedBy("sRoReads")
    private static final HashMap<String, MutableInt> sRoReads =
            TRACK_KEY_ACCESS ? new HashMap<>() : null;

    private static void onKeyAccess(String key) {
        if (!TRACK_KEY_ACCESS) return;

        if (key != null && key.startsWith("ro.")) {
            synchronized (sRoReads) {
                MutableInt numReads = sRoReads.getOrDefault(key, null);
                if (numReads == null) {
                    numReads = new MutableInt(0);
                    sRoReads.put(key, numReads);
                }
                numReads.value++;
                if (numReads.value > 3) {
                    Log.d(TAG, "Repeated read (count=" + numReads.value
                            + ") of a read-only system property '" + key + "'",
                            new Exception());
                }
            }
        }
    }

    // The one-argument version of native_get used to be a regular native function. Nowadays,
    // we use the two-argument form of native_get all the time, but we can't just delete the
    // one-argument overload: apps use it via reflection, as the UnsupportedAppUsage annotation
    // indicates. Let's just live with having a Java function with a very unusual name.
    @UnsupportedAppUsage
    private static String native_get(String key) {
        return native_get(key, "");
    }

    @FastNative
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    private static native String native_get(String key, String def);
    @FastNative
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    private static native int native_get_int(String key, int def);
    @FastNative
    @UnsupportedAppUsage
    private static native long native_get_long(String key, long def);
    @FastNative
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    private static native boolean native_get_boolean(String key, boolean def);

    @FastNative
    private static native long native_find(String name);
    @FastNative
    private static native String native_get(long handle);
    @CriticalNative
    private static native int native_get_int(long handle, int def);
    @CriticalNative
    private static native long native_get_long(long handle, long def);
    @CriticalNative
    private static native boolean native_get_boolean(long handle, boolean def);

    // _NOT_ FastNative: native_set performs IPC and can block
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    private static native void native_set(String key, String def);

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    private static native void native_add_change_callback();
    private static native void native_report_sysprop_change();

    /**
     * Get the String value for the given {@code key}.
     *
     * @param key the key to lookup
     * @return an empty string if the {@code key} isn't found
     * @hide
     */
    @NonNull
    @SystemApi
    public static String get(@NonNull String key) {
        if (TRACK_KEY_ACCESS) onKeyAccess(key);
        return native_get(key);
    }

    /**
     * Get the String value for the given {@code key}.
     *
     * @param key the key to lookup
     * @param def the default value in case the property is not set or empty
     * @return if the {@code key} isn't found, return {@code def} if it isn't null, or an empty
     * string otherwise
     * @hide
     */
    @NonNull
    @SystemApi
    public static String get(@NonNull String key, @Nullable String def) {
        if (TRACK_KEY_ACCESS) onKeyAccess(key);
        return native_get(key, def);
    }

    /**
     * Get the value for the given {@code key}, and return as an integer.
     *
     * @param key the key to lookup
     * @param def a default value to return
     * @return the key parsed as an integer, or def if the key isn't found or
     *         cannot be parsed
     * @hide
     */
    @SystemApi
    public static int getInt(@NonNull String key, int def) {
        if (TRACK_KEY_ACCESS) onKeyAccess(key);
        return native_get_int(key, def);
    }

    /**
     * Get the value for the given {@code key}, and return as a long.
     *
     * @param key the key to lookup
     * @param def a default value to return
     * @return the key parsed as a long, or def if the key isn't found or
     *         cannot be parsed
     * @hide
     */
    @SystemApi
    public static long getLong(@NonNull String key, long def) {
        if (TRACK_KEY_ACCESS) onKeyAccess(key);
        return native_get_long(key, def);
    }

    /**
     * Get the value for the given {@code key}, returned as a boolean.
     * Values 'n', 'no', '0', 'false' or 'off' are considered false.
     * Values 'y', 'yes', '1', 'true' or 'on' are considered true.
     * (case sensitive).
     * If the key does not exist, or has any other value, then the default
     * result is returned.
     *
     * @param key the key to lookup
     * @param def a default value to return
     * @return the key parsed as a boolean, or def if the key isn't found or is
     *         not able to be parsed as a boolean.
     * @hide
     */
    @SystemApi
    public static boolean getBoolean(@NonNull String key, boolean def) {
        if (TRACK_KEY_ACCESS) onKeyAccess(key);
        return native_get_boolean(key, def);
    }

    /**
     * Set the value for the given {@code key} to {@code val}.
     *
     * @throws IllegalArgumentException for non read-only properties if the {@code val} exceeds
     * 91 characters
     * @throws RuntimeException if the property cannot be set, for example, if it was blocked by
     * SELinux. libc will log the underlying reason.
     * @hide
     */
    @UnsupportedAppUsage
    public static void set(@NonNull String key, @Nullable String val) {
        if (val != null && !key.startsWith("ro.") && val.length() > PROP_VALUE_MAX) {
            throw new IllegalArgumentException("value of system property '" + key
                    + "' is longer than " + PROP_VALUE_MAX + " characters: " + val);
        }
        if (TRACK_KEY_ACCESS) onKeyAccess(key);
        native_set(key, val);
    }

    /**
     * Add a callback that will be run whenever any system property changes.
     *
     * @param callback The {@link Runnable} that should be executed when a system property
     * changes.
     * @hide
     */
    @UnsupportedAppUsage
    public static void addChangeCallback(@NonNull Runnable callback) {
        synchronized (sChangeCallbacks) {
            if (sChangeCallbacks.size() == 0) {
                native_add_change_callback();
            }
            sChangeCallbacks.add(callback);
        }
    }

    /**
     * Remove the target callback.
     *
     * @param callback The {@link Runnable} that should be removed.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static void removeChangeCallback(@NonNull Runnable callback) {
        synchronized (sChangeCallbacks) {
            if (sChangeCallbacks.contains(callback)) {
                sChangeCallbacks.remove(callback);
            }
        }
    }

    @SuppressWarnings("unused")  // Called from native code.
    private static void callChangeCallbacks() {
        ArrayList<Runnable> callbacks = null;
        synchronized (sChangeCallbacks) {
            //Log.i("foo", "Calling " + sChangeCallbacks.size() + " change callbacks!");
            if (sChangeCallbacks.size() == 0) {
                return;
            }
            callbacks = new ArrayList<Runnable>(sChangeCallbacks);
        }
        final long token = Binder.clearCallingIdentity();
        try {
            for (int i = 0; i < callbacks.size(); i++) {
                try {
                    callbacks.get(i).run();
                } catch (Throwable t) {
                    // Ignore and try to go on. Don't use wtf here: that
                    // will cause the process to exit on some builds and break tests.
                    Log.e(TAG, "Exception in SystemProperties change callback", t);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Notifies listeners that a system property has changed
     * @hide
     */
    @UnsupportedAppUsage
    public static void reportSyspropChanged() {
        native_report_sysprop_change();
    }

    /**
     * Return a {@code SHA-1} digest of the given keys and their values as a
     * hex-encoded string. The ordering of the incoming keys doesn't change the
     * digest result.
     *
     * @hide
     */
    public static @NonNull String digestOf(@NonNull String... keys) {
        Arrays.sort(keys);
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-1");
            for (String key : keys) {
                final String item = key + "=" + get(key) + "\n";
                digest.update(item.getBytes(StandardCharsets.UTF_8));
            }
            return HexEncoding.encodeToString(digest.digest()).toLowerCase();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @UnsupportedAppUsage
    private SystemProperties() {
    }

    /**
     * Look up a property location by name.
     * @name name of the property
     * @return property handle or {@code null} if property isn't set
     * @hide
     */
    @Nullable public static Handle find(@NonNull String name) {
        long nativeHandle = native_find(name);
        if (nativeHandle == 0) {
            return null;
        }
        return new Handle(nativeHandle);
    }

    /**
     * Handle to a pre-located property. Looking up a property handle in advance allows
     * for optimal repeated lookup of a single property.
     * @hide
     */
    public static final class Handle {

        private final long mNativeHandle;

        /**
         * @return Value of the property
         */
        @NonNull public String get() {
            return native_get(mNativeHandle);
        }
        /**
         * @param def default value
         * @return value or {@code def} on parse error
         */
        public int getInt(int def) {
            return native_get_int(mNativeHandle, def);
        }
        /**
         * @param def default value
         * @return value or {@code def} on parse error
         */
        public long getLong(long def) {
            return native_get_long(mNativeHandle, def);
        }
        /**
         * @param def default value
         * @return value or {@code def} on parse error
         */
        public boolean getBoolean(boolean def) {
            return native_get_boolean(mNativeHandle, def);
        }

        private Handle(long nativeHandle) {
            mNativeHandle = nativeHandle;
        }
    }
}
