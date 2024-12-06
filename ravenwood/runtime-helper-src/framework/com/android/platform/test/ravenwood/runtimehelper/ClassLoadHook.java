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

/**
 * Standard class loader hook.
 */
public class ClassLoadHook {
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

        // Always try to initialize the environment in case classes are loaded before
        // RavenwoodAwareTestRunner is initialized
        try {
            Class.forName("android.platform.test.ravenwood.RavenwoodRuntimeEnvironmentController")
                    .getMethod("globalInitOnce").invoke(null);
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
