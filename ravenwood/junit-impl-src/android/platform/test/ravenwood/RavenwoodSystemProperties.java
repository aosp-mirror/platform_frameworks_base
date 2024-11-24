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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * A class to manage the core default system properties of the Ravenwood environment.
 */
public class RavenwoodSystemProperties {
    private static final String TAG = "RavenwoodSystemProperties";

    /** We pull in properties from this file. */
    private static final String RAVENWOOD_BUILD_PROP = "ravenwood-data/ravenwood-build.prop";

    /** This is the actual build.prop we use to build the device (contents depends on lunch). */
    private static final String DEVICE_BUILD_PROP = "ravenwood-data/build.prop";

    /** The default values. */
    static final Map<String, String> sDefaultValues = new HashMap<>();

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

        Log.i(TAG, "Default system properties:");
        ravenwoodProps.forEach((key, origValue) -> {
            final String value;

            // If a value starts with "$$$", then this is a reference to the device-side value.
            if (origValue.startsWith("$$$")) {
                var deviceKey = origValue.substring(3);
                var deviceValue = deviceProps.get(deviceKey);
                if (deviceValue == null) {
                    throw new RuntimeException("Failed to initialize system properties. Key '"
                            + deviceKey + "' doesn't exist in the device side build.prop");
                }
                value = deviceValue;
            } else {
                value = origValue;
            }
            Log.i(TAG, key + "=" + value);
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

    private static boolean isKeyReadable(String key) {
        final String root = getKeyRoot(key);

        if (root.startsWith("debug.")) return true;

        // This set is carefully curated to help identify situations where a test may
        // accidentally depend on a default value of an obscure property whose owner hasn't
        // decided how Ravenwood should behave.
        if (root.startsWith("boot.")) return true;
        if (root.startsWith("build.")) return true;
        if (root.startsWith("product.")) return true;
        if (root.startsWith("soc.")) return true;
        if (root.startsWith("system.")) return true;

        // For PropertyInvalidatedCache
        if (root.startsWith("cache_key.")) return true;

        switch (key) {
            case "gsm.version.baseband":
            case "no.such.thing":
            case "qemu.sf.lcd_density":
            case "ro.bootloader":
            case "ro.debuggable":
            case "ro.hardware":
            case "ro.hw_timeout_multiplier":
            case "ro.odm.build.media_performance_class":
            case "ro.sf.lcd_density":
            case "ro.treble.enabled":
            case "ro.vndk.version":
            case "ro.icu.data.path":
                return true;
        }

        return false;
    }

    private static boolean isKeyWritable(String key) {
        final String root = getKeyRoot(key);

        if (root.startsWith("debug.")) return true;

        // For PropertyInvalidatedCache
        if (root.startsWith("cache_key.")) return true;

        return false;
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
