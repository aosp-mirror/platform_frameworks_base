/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.annotation.Nullable;
import android.webkit.CacheManager.CacheResult;
import android.webkit.PluginData;
import android.webkit.UrlInterceptHandler;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;

/**
 * @hide
 * @deprecated This class was intended to be used by Gears. Since Gears was
 * deprecated, so is this class.
 */
@Deprecated
public final class UrlInterceptRegistry {

    private final static String LOGTAG = "intercept";

    private static boolean mDisabled = false;

    private static LinkedList mHandlerList;

    private static synchronized LinkedList getHandlers() {
        if(mHandlerList == null)
            mHandlerList = new LinkedList<UrlInterceptHandler>();
        return mHandlerList;
    }

    /**
     * set the flag to control whether url intercept is enabled or disabled
     * 
     * @param disabled {@code true} to disable the cache
     *
     * @hide
     * @deprecated This class was intended to be used by Gears. Since Gears was
     * deprecated, so is this class.
     */
    @Deprecated
    public static synchronized void setUrlInterceptDisabled(boolean disabled) {
        mDisabled = disabled;
    }

    /**
     * get the state of the url intercept, enabled or disabled
     * 
     * @return return if it is disabled
     *
     * @hide
     * @deprecated This class was intended to be used by Gears. Since Gears was
     * deprecated, so is this class.
     */
    @Deprecated
    public static synchronized boolean urlInterceptDisabled() {
        return mDisabled;
    }

    /**
     * Register a new UrlInterceptHandler. This handler will be called
     * before any that were previously registered.
     *
     * @param handler The new UrlInterceptHandler object
     * @return {@code true} if the handler was not previously registered.
     *
     * @hide
     * @deprecated This class was intended to be used by Gears. Since Gears was
     * deprecated, so is this class.
     */
    @Deprecated
    public static synchronized boolean registerHandler(
            UrlInterceptHandler handler) {
        if (!getHandlers().contains(handler)) {
            getHandlers().addFirst(handler);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Unregister a previously registered UrlInterceptHandler.
     *
     * @param handler A previously registered UrlInterceptHandler.
     * @return {@code true} if the handler was found and removed from the list.
     *
     * @hide
     * @deprecated This class was intended to be used by Gears. Since Gears was
     * deprecated, so is this class.
     */
    @Deprecated
    public static synchronized boolean unregisterHandler(
            UrlInterceptHandler handler) {
        return getHandlers().remove(handler);
    }

    /**
     * Given an url, returns the CacheResult of the first
     * UrlInterceptHandler interested, or {@code null} if none are.
     *
     * @return A CacheResult containing surrogate content.
     *
     * @hide
     * @deprecated This class was intended to be used by Gears. Since Gears was
     * deprecated, so is this class.
     */
    @Deprecated
    @Nullable
    public static synchronized CacheResult getSurrogate(
            String url, Map<String, String> headers) {
        if (urlInterceptDisabled()) {
            return null;
        }
        Iterator iter = getHandlers().listIterator();
        while (iter.hasNext()) {
            UrlInterceptHandler handler = (UrlInterceptHandler) iter.next();
            CacheResult result = handler.service(url, headers);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * Given an url, returns the PluginData of the first
     * UrlInterceptHandler interested, or {@code null} if none are or if
     * intercepts are disabled.
     *
     * @return A PluginData instance containing surrogate content.
     *
     * @hide
     * @deprecated This class was intended to be used by Gears. Since Gears was
     * deprecated, so is this class.
     */
    @Deprecated
    @Nullable
    public static synchronized PluginData getPluginData(
            String url, Map<String, String> headers) {
        if (urlInterceptDisabled()) {
            return null;
        }
        Iterator iter = getHandlers().listIterator();
        while (iter.hasNext()) {
            UrlInterceptHandler handler = (UrlInterceptHandler) iter.next();
            PluginData data = handler.getPluginData(url, headers);
            if (data != null) {
                return data;
            }
        }
        return null;
    }
}
