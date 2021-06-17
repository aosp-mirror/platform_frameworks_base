/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.annotation.SystemApi;
import android.content.ContentResolver;
import android.graphics.Bitmap;

/**
 * Functions for manipulating the icon database used by WebView.
 * These functions require that a WebView be constructed before being invoked
 * and WebView.getIconDatabase() will return a WebIconDatabase object. This
 * WebIconDatabase object is a single instance and all methods operate on that
 * single object.
 * The main use-case for this class is calling {@link #open}
 * to enable favicon functionality on all WebView instances in this process.
 *
 * @deprecated This class is only required when running on devices
 *             up to {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2}
 */
@Deprecated
public abstract class WebIconDatabase {
    /**
     * Interface for receiving icons from the database.
     * @deprecated This interface is obsolete.
     */
    @Deprecated
    public interface IconListener {
        /**
         * Called when the icon has been retrieved from the database and the
         * result is non-null.
         * @param url The url passed in the request.
         * @param icon The favicon for the given url.
         */
        public void onReceivedIcon(String url, Bitmap icon);
    }

    /**
     * Open a the icon database and store the icons in the given path.
     * @param path The directory path where the icon database will be stored.
     */
    public abstract void open(String path);

    /**
     * Close the shared instance of the icon database.
     */
    public abstract void close();

    /**
     * Removes all the icons in the database.
     */
    public abstract void removeAllIcons();

    /**
     * Request the Bitmap representing the icon for the given page
     * url. If the icon exists, the listener will be called with the result.
     * @param url The page's url.
     * @param listener An implementation on IconListener to receive the result.
     */
    public abstract void requestIconForPageUrl(String url, IconListener listener);

    /** {@hide}
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    public abstract void bulkRequestIconForPageUrl(ContentResolver cr, String where,
            IconListener listener);

    /**
     * Retain the icon for the given page url.
     * @param url The page's url.
     */
    public abstract void retainIconForPageUrl(String url);

    /**
     * Release the icon for the given page url.
     * @param url The page's url.
     */
    public abstract void releaseIconForPageUrl(String url);

    /**
     * Get the global instance of WebIconDatabase.
     * @return A single instance of WebIconDatabase. It will be the same
     *         instance for the current process each time this method is
     *         called.
     */
    public static WebIconDatabase getInstance() {
        // XXX: Must be created in the UI thread.
        return WebViewFactory.getProvider().getWebIconDatabase();
    }
}
