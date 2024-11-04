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

import static com.android.ravenwood.common.RavenwoodCommonUtils.RAVENWOOD_SYSPROP;

import com.android.ravenwood.common.RavenwoodCommonUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class RavenwoodSystemProperties {
    private static final String TAG = "RavenwoodSystemProperties";

    private static final Map<String, String> sDefaultValues = new HashMap<>();

    private static final String[] PARTITIONS = {
            "bootimage",
            "odm",
            "product",
            "system",
            "system_ext",
            "vendor",
            "vendor_dlkm",
    };

    /**
     * More info about property file loading: system/core/init/property_service.cpp
     * In the following logic, the only partition we would need to consider is "system",
     * since we only read from system-build.prop
     */
    static void initialize(String propFile) {
        // Load all properties from build.prop
        try {
            Files.readAllLines(Path.of(propFile)).stream()
                    .map(String::trim)
                    .filter(s -> !s.startsWith("#"))
                    .map(s -> s.split("\\s*=\\s*", 2))
                    .filter(a -> a.length == 2)
                    .forEach(a -> sDefaultValues.put(a[0], a[1]));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // If ro.product.${name} is not set, derive from ro.product.${partition}.${name}
        // If ro.product.cpu.abilist* is not set, derive from ro.${partition}.product.cpu.abilist*
        for (var entry : Set.copyOf(sDefaultValues.entrySet())) {
            final String key;
            if (entry.getKey().startsWith("ro.product.system.")) {
                var name = entry.getKey().substring(18);
                key = "ro.product." + name;

            } else if (entry.getKey().startsWith("ro.system.product.cpu.abilist")) {
                var name = entry.getKey().substring(22);
                key = "ro.product.cpu." + name;
            } else {
                continue;
            }
            if (!sDefaultValues.containsKey(key)) {
                sDefaultValues.put(key, entry.getValue());
            }
        }

        // Some other custom values
        sDefaultValues.put("ro.board.first_api_level", "1");
        sDefaultValues.put("ro.product.first_api_level", "1");
        sDefaultValues.put("ro.soc.manufacturer", "Android");
        sDefaultValues.put("ro.soc.model", "Ravenwood");
        sDefaultValues.put(RAVENWOOD_SYSPROP, "1");

        // Log all values
        sDefaultValues.forEach((key, value) -> RavenwoodCommonUtils.log(TAG, key + "=" + value));

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
    }

    private volatile boolean mIsImmutable;

    private final Map<String, String> mValues = new HashMap<>();

    /** Set of additional keys that should be considered readable */
    private final Set<String> mKeyReadable = new HashSet<>();

    /** Set of additional keys that should be considered writable */
    private final Set<String> mKeyWritable = new HashSet<>();

    public RavenwoodSystemProperties() {
        mValues.putAll(sDefaultValues);
    }

    /** Copy constructor */
    public RavenwoodSystemProperties(RavenwoodSystemProperties source, boolean immutable) {
        mKeyReadable.addAll(source.mKeyReadable);
        mKeyWritable.addAll(source.mKeyWritable);
        mValues.putAll(source.mValues);
        mIsImmutable = immutable;
    }

    public Map<String, String> getValues() {
        return new HashMap<>(mValues);
    }

    public boolean isKeyReadable(String key) {
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

        return mKeyReadable.contains(key);
    }

    public boolean isKeyWritable(String key) {
        final String root = getKeyRoot(key);

        if (root.startsWith("debug.")) return true;

        // For PropertyInvalidatedCache
        if (root.startsWith("cache_key.")) return true;

        return mKeyWritable.contains(key);
    }

    private void ensureNotImmutable() {
        if (mIsImmutable) {
            throw new RuntimeException("Unable to update immutable instance");
        }
    }

    public void setValue(String key, Object value) {
        ensureNotImmutable();

        final String valueString = (value == null) ? null : String.valueOf(value);
        if ((valueString == null) || valueString.isEmpty()) {
            mValues.remove(key);
        } else {
            mValues.put(key, valueString);
        }
    }

    public void setAccessNone(String key) {
        ensureNotImmutable();
        mKeyReadable.remove(key);
        mKeyWritable.remove(key);
    }

    public void setAccessReadOnly(String key) {
        ensureNotImmutable();
        mKeyReadable.add(key);
        mKeyWritable.remove(key);
    }

    public void setAccessReadWrite(String key) {
        ensureNotImmutable();
        mKeyReadable.add(key);
        mKeyWritable.add(key);
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
