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
package com.android.platform.test.ravenwood.runtimehelper;

import android.platform.test.ravenwood.RavenwoodUtils;

import java.io.File;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

/**
 * Standard class loader hook.
 *
 * Currently, we use this class to load libandroid_runtime (if needed). In the future, we may
 * load other JNI or do other set up here.
 */
public class ClassLoadHook {
    /**
     * If true, we won't load `libandroid_runtime`
     *
     * <p>Looks like there's some complexity in running a host test with JNI with `atest`,
     * so we need a way to remove the dependency.
     */
    private static final boolean SKIP_LOADING_LIBANDROID = "1".equals(System.getenv(
            "RAVENWOOD_SKIP_LOADING_LIBANDROID"));

    public static final String CORE_NATIVE_CLASSES = "core_native_classes";
    public static final String ICU_DATA_PATH = "icu.data.path";
    public static final String KEYBOARD_PATHS = "keyboard_paths";
    public static final String GRAPHICS_NATIVE_CLASSES = "graphics_native_classes";

    public static final String LIBANDROID_RUNTIME_NAME = "android_runtime";

    /**
     * Extra strings needed to pass to register_android_graphics_classes().
     *
     * `android.graphics.Graphics` is not actually a class, so we can't use the same initialization
     * strategy than the "normal" classes. So we just hardcode it here.
     */
    public static final String GRAPHICS_EXTRA_INIT_PARAMS = ",android.graphics.Graphics";

    private static String sInitialDir = new File("").getAbsolutePath();

    static {
        log("Initialized. Current dir=" + sInitialDir);
    }

    private ClassLoadHook() {
    }

    /**
     * Called when classes with
     * {@code @HostSideTestClassLoadHook(
     * "com.android.hoststubgen.runtimehelper.LibandroidLoadingHook.onClassLoaded") }
     * are loaded.
     */
    public static void onClassLoaded(Class<?> clazz) {
        System.out.println("Framework class loaded: " + clazz.getCanonicalName());

        loadFrameworkNativeCode();
    }

    private static void log(String message) {
        System.out.println("ClassLoadHook: " + message);
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

    private static boolean sLoadFrameworkNativeCodeCalled = false;

    /**
     * Load `libandroid_runtime` if needed.
     */
    private static void loadFrameworkNativeCode() {
        // This is called from class-initializers, so no synchronization is needed.
        if (sLoadFrameworkNativeCodeCalled) {
            return;
        }
        sLoadFrameworkNativeCodeCalled = true;

        // libandroid_runtime uses Java's system properties to decide what JNI methods to set up.
        // Set up these properties for host-side tests.

        if ("1".equals(System.getenv("RAVENWOOD_DUMP_PROPERTIES"))) {
            log("Java system properties:");
            dumpSystemProperties();
        }

        if (SKIP_LOADING_LIBANDROID) {
            log("Skip loading native runtime.");
            return;
        }

        // Make sure these properties are not set.
        ensurePropertyNotSet(CORE_NATIVE_CLASSES);
        ensurePropertyNotSet(ICU_DATA_PATH);
        ensurePropertyNotSet(KEYBOARD_PATHS);
        ensurePropertyNotSet(GRAPHICS_NATIVE_CLASSES);

        // Load the libraries, if needed.
        final var libanrdoidClasses = getClassesWithNativeMethods(sLibandroidClasses);
        final var libhwuiClasses = getClassesWithNativeMethods(sLibhwuiClasses);
        if (libanrdoidClasses.isEmpty() && libhwuiClasses.isEmpty()) {
            log("No classes require JNI methods, skip loading native runtime.");
            return;
        }
        setProperty(CORE_NATIVE_CLASSES, libanrdoidClasses);
        setProperty(GRAPHICS_NATIVE_CLASSES, libhwuiClasses + GRAPHICS_EXTRA_INIT_PARAMS);

        log("Loading " + LIBANDROID_RUNTIME_NAME + " for '" + libanrdoidClasses + "' and '"
                + libhwuiClasses + "'");
        RavenwoodUtils.loadJniLibrary(LIBANDROID_RUNTIME_NAME);
    }

    /**
     * Classes with native methods that are backed by libandroid_runtime.
     *
     * See frameworks/base/core/jni/platform/host/HostRuntime.cpp
     */
    private static final Class<?>[] sLibandroidClasses = {
            android.util.Log.class,
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
    };

    /**
     * @return if a given class has any native method or not.
     */
    private static boolean hasNativeMethod(Class<?> clazz) {
        for (var method : clazz.getDeclaredMethods()) {
            if (Modifier.isNative(method.getModifiers())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Create a list of classes as comma-separated that require JNI methods to be set up from
     * a given class list, ignoring classes with no native methods.
     */
    private static String getClassesWithNativeMethods(Class<?>[] classes) {
        final var coreNativeClassesToLoad = new ArrayList<String>();

        for (var clazz : classes) {
            if (hasNativeMethod(clazz)) {
                log("Class %s has native methods", clazz.getCanonicalName());
                coreNativeClassesToLoad.add(clazz.getName());
            }
        }

        return String.join(",", coreNativeClassesToLoad);
    }
}
