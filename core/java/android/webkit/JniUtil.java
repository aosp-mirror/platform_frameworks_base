/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.webkit;

import android.content.Context;

class JniUtil {
    private JniUtil() {} // Utility class, do not instantiate.

    // Used by the Chromium HTTP stack.
    private static String sDatabaseDirectory;
    private static String sCacheDirectory;
    private static Boolean sUseChromiumHttpStack;

    private static boolean initialized = false;

    private static void checkIntialized() {
        if (!initialized) {
            throw new IllegalStateException("Call CookieSyncManager::createInstance() or create a webview before using this class");
        }
    }

    protected static synchronized void setContext(Context context) {
        if (initialized)
            return;

        Context appContext = context.getApplicationContext();
        sDatabaseDirectory = appContext.getDatabasePath("dummy").getParent();
        sCacheDirectory = appContext.getCacheDir().getAbsolutePath();
        initialized = true;
    }

    /**
     * Called by JNI. Gets the application's database directory, excluding the trailing slash.
     * @return String The application's database directory
     */
    private static synchronized String getDatabaseDirectory() {
        checkIntialized();
        return sDatabaseDirectory;
    }

    /**
     * Called by JNI. Gets the application's cache directory, excluding the trailing slash.
     * @return String The application's cache directory
     */
    private static synchronized String getCacheDirectory() {
        checkIntialized();
        return sCacheDirectory;
    }

    /**
     * Returns true if we're using the Chromium HTTP stack.
     *
     * TODO: Remove this if/when we permanently switch to the Chromium HTTP stack
     * http:/b/3118772
     */
    static boolean useChromiumHttpStack() {
        if (sUseChromiumHttpStack == null) {
            sUseChromiumHttpStack = nativeUseChromiumHttpStack();
        }
        return sUseChromiumHttpStack;
    }

    private static native boolean nativeUseChromiumHttpStack();
}
