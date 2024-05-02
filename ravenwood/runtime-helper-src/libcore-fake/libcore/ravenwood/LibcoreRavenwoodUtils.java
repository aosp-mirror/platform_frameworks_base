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
package libcore.ravenwood;

public class LibcoreRavenwoodUtils {
    private LibcoreRavenwoodUtils() {
    }

    public static void loadRavenwoodNativeRuntime() {
        // TODO Stop using reflections.
        // We need to call RavenwoodUtils.loadRavenwoodNativeRuntime(), but due to the build
        // structure complexity, we can't refer to to this method directly from here,
        // so let's use reflections for now...
        try {
            final var clazz = Class.forName("android.platform.test.ravenwood.RavenwoodUtils");
            final var method = clazz.getMethod("loadRavenwoodNativeRuntime");
            method.invoke(null);
        } catch (Throwable th) {
            throw new IllegalStateException("Failed to load Ravenwood native runtime", th);
        }
    }
}
