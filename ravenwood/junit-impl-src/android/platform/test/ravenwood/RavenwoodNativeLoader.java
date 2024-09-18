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

import com.android.ravenwood.common.RavenwoodCommonUtils;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * We use this class to load libandroid_runtime.
 * In the future, we may load other native libraries.
 */
public final class RavenwoodNativeLoader {
    public static final String CORE_NATIVE_CLASSES = "core_native_classes";
    public static final String ICU_DATA_PATH = "icu.data.path";
    public static final String KEYBOARD_PATHS = "keyboard_paths";
    public static final String GRAPHICS_NATIVE_CLASSES = "graphics_native_classes";

    public static final String LIBANDROID_RUNTIME_NAME = "android_runtime";

    /**
     * Classes with native methods that are backed by libandroid_runtime.
     *
     * See frameworks/base/core/jni/platform/host/HostRuntime.cpp
     */
    private static final Class<?>[] sLibandroidClasses = {
            android.util.Log.class,
            android.os.Parcel.class,
            android.os.Binder.class,
            android.content.res.ApkAssets.class,
            android.content.res.AssetManager.class,
            android.content.res.StringBlock.class,
            android.content.res.XmlBlock.class,
    };

    /**
     * Classes with native methods that are backed by libhwui.
     *
     * See frameworks/base/libs/hwui/apex/LayoutlibLoader.cpp
     */
    private static final Class<?>[] sLibhwuiClasses = {
            android.graphics.Interpolator.class,
            android.graphics.Matrix.class,
            android.graphics.Path.class,
            android.graphics.Color.class,
            android.graphics.ColorSpace.class,
    };

    /**
     * Extra strings needed to pass to register_android_graphics_classes().
     *
     * `android.graphics.Graphics` is not actually a class, so we just hardcode it here.
     */
    public final static String[] GRAPHICS_EXTRA_INIT_PARAMS = new String[] {
            "android.graphics.Graphics"
    };

    private RavenwoodNativeLoader() {
    }

    private static void log(String message) {
        System.out.println("RavenwoodNativeLoader: " + message);
    }

    private static void log(String fmt, Object... args) {
        log(String.format(fmt, args));
    }

    private static void ensurePropertyNotSet(String key) {
        if (System.getProperty(key) != null) {
            throw new RuntimeException("System property \"" + key + "\" is set unexpectedly");
        }
    }

    private static void setProperty(String key, String value) {
        System.setProperty(key, value);
        log("Property set: %s=\"%s\"", key, value);
    }

    private static void dumpSystemProperties() {
        for (var prop : System.getProperties().entrySet()) {
            log("  %s=\"%s\"", prop.getKey(), prop.getValue());
        }
    }

    /**
     * libandroid_runtime uses Java's system properties to decide what JNI methods to set up.
     * Set up these properties and load the native library
     */
    public static void loadFrameworkNativeCode() {
        if ("1".equals(System.getenv("RAVENWOOD_DUMP_PROPERTIES"))) {
            log("Java system properties:");
            dumpSystemProperties();
        }

        // Make sure these properties are not set.
        ensurePropertyNotSet(CORE_NATIVE_CLASSES);
        ensurePropertyNotSet(ICU_DATA_PATH);
        ensurePropertyNotSet(KEYBOARD_PATHS);
        ensurePropertyNotSet(GRAPHICS_NATIVE_CLASSES);

        // Build the property values
        final var joiner = Collectors.joining(",");
        final var libandroidClasses =
                Arrays.stream(sLibandroidClasses).map(Class::getName).collect(joiner);
        final var libhwuiClasses = Stream.concat(
                Arrays.stream(sLibhwuiClasses).map(Class::getName),
                Arrays.stream(GRAPHICS_EXTRA_INIT_PARAMS)
        ).collect(joiner);

        // Load the libraries
        setProperty(CORE_NATIVE_CLASSES, libandroidClasses);
        setProperty(GRAPHICS_NATIVE_CLASSES, libhwuiClasses);
        log("Loading " + LIBANDROID_RUNTIME_NAME + " for '" + libandroidClasses + "' and '"
                + libhwuiClasses + "'");
        RavenwoodCommonUtils.loadJniLibrary(LIBANDROID_RUNTIME_NAME);
    }
}
