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

import android.app.ActivityManager;
import android.content.Context;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.InputStream;

class JniUtil {

    static {
        System.loadLibrary("webcore");
        System.loadLibrary("chromium_net");
    }
    private static final String LOGTAG = "webkit";
    private JniUtil() {} // Utility class, do not instantiate.

    // Used by the Chromium HTTP stack.
    private static String sDatabaseDirectory;
    private static String sCacheDirectory;
    private static Context sContext;

    private static void checkInitialized() {
        if (sContext == null) {
            throw new IllegalStateException("Call CookieSyncManager::createInstance() or create a webview before using this class");
        }
    }

    protected static synchronized void setContext(Context context) {
        if (sContext != null) {
            return;
        }

        sContext = context.getApplicationContext();
    }

    protected static synchronized Context getContext() {
        return sContext;
    }

    /**
     * Called by JNI. Gets the application's database directory, excluding the trailing slash.
     * @return String The application's database directory
     */
    private static synchronized String getDatabaseDirectory() {
        checkInitialized();

        if (sDatabaseDirectory == null) {
            sDatabaseDirectory = sContext.getDatabasePath("dummy").getParent();
        }

        return sDatabaseDirectory;
    }

    /**
     * Called by JNI. Gets the application's cache directory, excluding the trailing slash.
     * @return String The application's cache directory
     */
    private static synchronized String getCacheDirectory() {
        checkInitialized();

        if (sCacheDirectory == null) {
            File cacheDir = sContext.getCacheDir();
            if (cacheDir == null) {
                sCacheDirectory = "";
            } else {
                sCacheDirectory = cacheDir.getAbsolutePath();
            }
        }

        return sCacheDirectory;
    }

    /**
     * Called by JNI. Gets the application's package name.
     * @return String The application's package name
     */
    private static synchronized String getPackageName() {
        checkInitialized();

        return sContext.getPackageName();
    }

    private static final String ANDROID_CONTENT = URLUtil.CONTENT_BASE;

    /**
     * Called by JNI. Calculates the size of an input stream by reading it.
     * @return long The size of the stream
     */
    private static synchronized long contentUrlSize(String url) {
        // content://
        if (url.startsWith(ANDROID_CONTENT)) {
            try {
                // Strip off MIME type. If we don't do this, we can fail to
                // load Gmail attachments, because the URL being loaded doesn't
                // exactly match the URL we have permission to read.
                int mimeIndex = url.lastIndexOf('?');
                if (mimeIndex != -1) {
                    url = url.substring(0, mimeIndex);
                }
                Uri uri = Uri.parse(url);
                InputStream is = sContext.getContentResolver().openInputStream(uri);
                byte[] buffer = new byte[1024];
                int n;
                long size = 0;
                try {
                    while ((n = is.read(buffer)) != -1) {
                        size += n;
                    }
                } finally {
                    is.close();
                }
                return size;
            } catch (Exception e) {
                Log.e(LOGTAG, "Exception: " + url);
                return 0;
            }
        } else {
            return 0;
        }
    }

    /**
     * Called by JNI.
     *
     * @return  Opened input stream to content
     * TODO: Make all content loading use this instead of BrowserFrame.java
     */
    private static synchronized InputStream contentUrlStream(String url) {
        // content://
        if (url.startsWith(ANDROID_CONTENT)) {
            try {
                // Strip off mimetype, for compatibility with ContentLoader.java
                // (used with Android HTTP stack, now removed).
                // If we don't do this, we can fail to load Gmail attachments,
                // because the URL being loaded doesn't exactly match the URL we
                // have permission to read.
                int mimeIndex = url.lastIndexOf('?');
                if (mimeIndex != -1) {
                    url = url.substring(0, mimeIndex);
                }
                Uri uri = Uri.parse(url);
                return sContext.getContentResolver().openInputStream(uri);
            } catch (Exception e) {
                Log.e(LOGTAG, "Exception: " + url);
                return null;
            }
        } else {
            return null;
        }
    }

    private static synchronized String getAutofillQueryUrl() {
        checkInitialized();
        // If the device has not checked in it won't have pulled down the system setting for the
        // Autofill Url. In that case we will not make autofill server requests.
        return Settings.Secure.getString(sContext.getContentResolver(),
                Settings.Secure.WEB_AUTOFILL_QUERY_URL);
    }

    private static boolean canSatisfyMemoryAllocation(long bytesRequested) {
        checkInitialized();
        ActivityManager manager = (ActivityManager) sContext.getSystemService(
                Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memInfo);
        long leftToAllocate = memInfo.availMem - memInfo.threshold;
        return !memInfo.lowMemory && bytesRequested < leftToAllocate;
    }
}
