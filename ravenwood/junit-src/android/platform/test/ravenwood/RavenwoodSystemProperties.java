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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public class RavenwoodSystemProperties {
    private volatile boolean mIsImmutable;

    private final Map<String, String> mValues = new HashMap<>();

    /** Set of additional keys that should be considered readable */
    private final Set<String> mKeyReadable = new HashSet<>();
    private final Predicate<String> mKeyReadablePredicate = (key) -> {
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
                return true;
        }

        return mKeyReadable.contains(key);
    };

    /** Set of additional keys that should be considered writable */
    private final Set<String> mKeyWritable = new HashSet<>();
    private final Predicate<String> mKeyWritablePredicate = (key) -> {
        final String root = getKeyRoot(key);

        if (root.startsWith("debug.")) return true;

        return mKeyWritable.contains(key);
    };

    public RavenwoodSystemProperties() {
        // TODO: load these values from build.prop generated files
        setValueForPartitions("product.brand", "Android");
        setValueForPartitions("product.device", "Ravenwood");
        setValueForPartitions("product.manufacturer", "Android");
        setValueForPartitions("product.model", "Ravenwood");
        setValueForPartitions("product.name", "Ravenwood");

        setValueForPartitions("product.cpu.abilist", "x86_64");
        setValueForPartitions("product.cpu.abilist32", "");
        setValueForPartitions("product.cpu.abilist64", "x86_64");

        setValueForPartitions("build.date", "Thu Jan 01 00:00:00 GMT 2024");
        setValueForPartitions("build.date.utc", "1704092400");
        setValueForPartitions("build.id", "MAIN");
        setValueForPartitions("build.tags", "dev-keys");
        setValueForPartitions("build.type", "userdebug");
        setValueForPartitions("build.version.all_codenames", "REL");
        setValueForPartitions("build.version.codename", "REL");
        setValueForPartitions("build.version.incremental", "userdebug.ravenwood.20240101");
        setValueForPartitions("build.version.known_codenames", "REL");
        setValueForPartitions("build.version.release", "14");
        setValueForPartitions("build.version.release_or_codename", "VanillaIceCream");
        setValueForPartitions("build.version.sdk", "34");

        setValue("ro.board.first_api_level", "1");
        setValue("ro.product.first_api_level", "1");

        setValue("ro.soc.manufacturer", "Android");
        setValue("ro.soc.model", "Ravenwood");

        setValue("ro.debuggable", "1");

        setValue(RAVENWOOD_SYSPROP, "1");
    }

    /** Copy constructor */
    public RavenwoodSystemProperties(RavenwoodSystemProperties source, boolean immutable) {
        this.mKeyReadable.addAll(source.mKeyReadable);
        this.mKeyWritable.addAll(source.mKeyWritable);
        this.mValues.putAll(source.mValues);
        this.mIsImmutable = immutable;
    }

    public Map<String, String> getValues() {
        return new HashMap<>(mValues);
    }

    public Predicate<String> getKeyReadablePredicate() {
        return mKeyReadablePredicate;
    }

    public Predicate<String> getKeyWritablePredicate() {
        return mKeyWritablePredicate;
    }

    private static final String[] PARTITIONS = {
            "bootimage",
            "odm",
            "product",
            "system",
            "system_ext",
            "vendor",
            "vendor_dlkm",
    };

    private void ensureNotImmutable() {
        if (mIsImmutable) {
            throw new RuntimeException("Unable to update immutable instance");
        }
    }

    /**
     * Set the given property for all possible partitions where it could be defined. For
     * example, the value of {@code ro.build.type} is typically also mirrored under
     * {@code ro.system.build.type}, etc.
     */
    private void setValueForPartitions(String key, String value) {
        ensureNotImmutable();

        setValue("ro." + key, value);
        for (String partition : PARTITIONS) {
            setValue("ro." + partition + "." + key, value);
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

    /**
     * Return an immutable, default instance.
     */
    // Create a default instance, and make an immutable copy of it.
    public static final RavenwoodSystemProperties DEFAULT_VALUES =
            new RavenwoodSystemProperties(new RavenwoodSystemProperties(), true);
}