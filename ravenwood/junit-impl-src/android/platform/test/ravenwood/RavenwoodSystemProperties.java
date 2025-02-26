/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.platform.test.ravenwood;

import static com.android.ravenwood.common.RavenwoodCommonUtils.RAVENWOOD_VERBOSE_LOGGING;
import static com.android.ravenwood.common.RavenwoodCommonUtils.getRavenwoodRuntimePath;

import android.util.Log;

import com.android.ravenwood.RavenwoodRuntimeNative;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A class to manage the core default system properties of the Ravenwood environment.
 */
public class RavenwoodSystemProperties {
    private static final String TAG = com.android.ravenwood.common.RavenwoodCommonUtils.TAG;

    /** We pull in properties from this file. */
    private static final String RAVENWOOD_BUILD_PROP = "ravenwood-data/ravenwood-build.prop";

    /** This is the actual build.prop we use to build the device (contents depends on lunch). */
    private static final String DEVICE_BUILD_PROP = "ravenwood-data/build.prop";

    /** The default values. */
    static final Map<String, String> sDefaultValues = new HashMap<>();

    static final Set<String> sReadableKeys = new HashSet<>();
    static final Set<String> sWritableKeys = new HashSet<>();

    private static final String[] PARTITIONS = {
            "bootimage",
            "odm",
            "product",
            "system",
            "system_ext",
            "vendor",
            "vendor_dlkm",
    };

    static Map<String, String> readProperties(String propFile) {
        // Use an ordered map just for cleaner dump log.
        final Map<String, String> ret = new LinkedHashMap<>();
        try {
            Files.readAllLines(Path.of(propFile)).stream()
                    .map(String::trim)
                    .filter(s -> !s.startsWith("#"))
                    .map(s -> s.split("\\s*=\\s*", 2))
                    .filter(a -> a.length == 2 && a[1].length() > 0)
                    .forEach(a -> ret.put(a[0], a[1]));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return ret;
    }

    /**
     * Load default sysprops from {@link #RAVENWOOD_BUILD_PROP}. We also pull in
     * certain properties from the acutual device's build.prop {@link #DEVICE_BUILD_PROP} too.
     *
     * More info about property file loading: system/core/init/property_service.cpp
     * In the following logic, the only partition we would need to consider is "system",
     * since we only read from system-build.prop
     */
    static void initialize() {
        var path = getRavenwoodRuntimePath();
        var ravenwoodProps = readProperties(path + RAVENWOOD_BUILD_PROP);
        var deviceProps = readProperties(path + DEVICE_BUILD_PROP);

        Log.v(TAG, "Default system properties:");
        ravenwoodProps.forEach((key, origValue) -> {
            final String value;

            if (origValue.startsWith("$$$")) {
                // If a value starts with "$$$", then:
                // - If it's "$$$r", the key is allowed to read.
                // - If it's "$$$w", the key is allowed to write.
                // - Otherwise, it's a reference to the device-side value.
                // In case of $$$r and $$$w, if the key ends with a '.', then it'll be treaded
                // as a prefix match.
                var deviceKey = origValue.substring(3);
                if ("r".equals(deviceKey)) {
                    sReadableKeys.add(key);
                    Log.v(TAG, key + " (readable)");
                    return;
                } else if ("w".equals(deviceKey)) {
                    sWritableKeys.add(key);
                    Log.v(TAG, key + " (writable)");
                    return;
                }

                var deviceValue = deviceProps.get(deviceKey);
                if (deviceValue == null) {
                    throw new RuntimeException("Failed to initialize system properties. Key '"
                            + deviceKey + "' doesn't exist in the device side build.prop");
                }
                value = deviceValue;
            } else {
                value = origValue;
            }
            Log.v(TAG, key + "=" + value);
            sDefaultValues.put(key, value);
        });

        // Copy ro.product.* and ro.build.* to all partitions, just in case
        // We don't want to log these because these are just a lot of duplicate values
        for (var entry : Set.copyOf(sDefaultValues.entrySet())) {
            var key = entry.getKey();
            if (key.startsWith("ro.product.") || key.startsWith("ro.build.")) {
                var name = key.substring(3);
                for (String partition : PARTITIONS) {
                    var newKey = "ro." + partition + "." + name;
                    if (!sDefaultValues.containsKey(newKey)) {
                        sDefaultValues.put(newKey, entry.getValue());
                    }
                }
            }
        }

        if (RAVENWOOD_VERBOSE_LOGGING) {
            // Dump all properties for local debugging.
            Log.v(TAG, "All system properties:");
            for (var key : sDefaultValues.keySet().stream().sorted().toList()) {
                Log.v(TAG, "" + key + "=" + sDefaultValues.get(key));
            }
        }

        // Actually set the system properties
        sDefaultValues.forEach(RavenwoodRuntimeNative::setSystemProperty);
    }

    private static boolean checkAllowedInner(String key, Set<String> allowed) {
        if (allowed.contains(key)) {
            return true;
        }

        // Also search for a prefix match.
        for (var k : allowed) {
            if (k.endsWith(".") && key.startsWith(k)) {
                return true;
            }
        }
        return false;
    }

    private static boolean checkAllowed(String key, Set<String> allowed) {
        return checkAllowedInner(key, allowed) || checkAllowedInner(getKeyRoot(key), allowed);
    }

    private static boolean isKeyReadable(String key) {
        // All core values should be readable
        if (sDefaultValues.containsKey(key)) {
            return true;
        }
        if (checkAllowed(key, sReadableKeys)) {
            return true;
        }
        // All writable keys are also readable
        return isKeyWritable(key);
    }

    private static boolean isKeyWritable(String key) {
        return checkAllowed(key, sWritableKeys);
    }

    static boolean isKeyAccessible(String key, boolean write) {
        return write ? isKeyWritable(key) : isKeyReadable(key);
    }

    /**
     * Return the "root" of the given property key, stripping away any modifier prefix such as
     * {@code ro.} or {@code persist.}.
     */
    private static String getKeyRoot(String key) {
        if (key.startsWith("ro.")) {
            return key.substring(3);
        } else if (key.startsWith("persist.")) {
            return key.substring(8);
        } else {
            return key;
        }
    }
}
