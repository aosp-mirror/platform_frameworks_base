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
package android.platform.test.ravenwood;

import com.android.ravenwood.common.RavenwoodCommonUtils;

/**
 * Utilities for writing (bivalent) ravenwood tests.
 */
public class RavenwoodUtils {
    private RavenwoodUtils() {
    }

    /**
     * Load a JNI library respecting {@code java.library.path}
     * (which reflects {@code LD_LIBRARY_PATH}).
     *
     * <p>{@code libname} must be the library filename without:
     * - directory
     * - "lib" prefix
     * - and the ".so" extension
     *
     * <p>For example, in order to load "libmyjni.so", then pass "myjni".
     *
     * <p>This is basically the same thing as Java's {@link System#loadLibrary(String)},
     * but this API works slightly different on ART and on the desktop Java, namely
     * the desktop Java version uses a different entry point method name
     * {@code JNI_OnLoad_libname()} (note the included "libname")
     * while ART always seems to use {@code JNI_OnLoad()}.
     *
     * <p>This method provides the same behavior on both the device side and on Ravenwood --
     * it uses {@code JNI_OnLoad()} as the entry point name on both.
     */
    public static void loadJniLibrary(String libname) {
        RavenwoodCommonUtils.loadJniLibrary(libname);
    }
}
