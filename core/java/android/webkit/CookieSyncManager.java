/*
 * Copyright (C) 2007 The Android Open Source Project
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
import android.util.Log;
import android.webkit.CookieManager.Cookie;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * The CookieSyncManager is used to synchronize the browser cookie store
 * between RAM and permanent storage. To get the best performance, browser cookies are
 * saved in RAM. A separate thread saves the cookies between, driven by a timer.
 * <p>
 *
 * To use the CookieSyncManager, the host application has to call the following
 * when the application starts:
 * <p>
 *
 * <pre class="prettyprint">CookieSyncManager.createInstance(context)</pre><p>
 *
 * To set up for sync, the host application has to call<p>
 * <pre class="prettyprint">CookieSyncManager.getInstance().startSync()</pre><p>
 *
 * in Activity.onResume(), and call
 * <p>
 *
 * <pre class="prettyprint">
 * CookieSyncManager.getInstance().stopSync()
 * </pre><p>
 *
 * in Activity.onPause().<p>
 *
 * To get instant sync instead of waiting for the timer to trigger, the host can
 * call
 * <p>
 * <pre class="prettyprint">CookieSyncManager.getInstance().sync()</pre><p>
 *
 * The sync interval is 5 minutes, so you will want to force syncs
 * manually anyway, for instance in {@link
 * WebViewClient#onPageFinished}. Note that even sync() happens
 * asynchronously, so don't do it just as your activity is shutting
 * down.
 */
public final class CookieSyncManager extends WebSyncManager {

    private static CookieSyncManager sRef;

    // time when last update happened
    private long mLastUpdate;

    private CookieSyncManager(Context context) {
        super(context, "CookieSyncManager");
    }

    /**
     * Singleton access to a {@link CookieSyncManager}. An
     * IllegalStateException will be thrown if
     * {@link CookieSyncManager#createInstance(Context)} is not called before.
     * 
     * @return CookieSyncManager
     */
    public static synchronized CookieSyncManager getInstance() {
        checkInstanceIsCreated();
        return sRef;
    }

    /**
     * Create a singleton CookieSyncManager within a context
     * @param context
     * @return CookieSyncManager
     */
    public static synchronized CookieSyncManager createInstance(
            Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Invalid context argument");
        }

        JniUtil.setContext(context);
        Context appContext = context.getApplicationContext();
        if (sRef == null) {
            sRef = new CookieSyncManager(appContext);
        }
        return sRef;
    }

    /**
     * Package level api, called from CookieManager. Get all the cookies which
     * matches a given base domain.
     * @param domain
     * @return A list of Cookie
     */
    ArrayList<Cookie> getCookiesForDomain(String domain) {
        // null mDataBase implies that the host application doesn't support
        // persistent cookie. No sync needed.
        if (mDataBase == null) {
            return new ArrayList<Cookie>();
        }

        return mDataBase.getCookiesForDomain(domain);
    }

    /**
     * Package level api, called from CookieManager Clear all cookies in the
     * database
     */
    void clearAllCookies() {
        // null mDataBase implies that the host application doesn't support
        // persistent cookie.
        if (mDataBase == null) {
            return;
        }

        mDataBase.clearCookies();
    }

    /**
     * Returns true if there are any saved cookies.
     */
    boolean hasCookies() {
        // null mDataBase implies that the host application doesn't support
        // persistent cookie.
        if (mDataBase == null) {
            return false;
        }

        return mDataBase.hasCookies();
    }

    /**
     * Package level api, called from CookieManager Clear all session cookies in
     * the database
     */
    void clearSessionCookies() {
        // null mDataBase implies that the host application doesn't support
        // persistent cookie.
        if (mDataBase == null) {
            return;
        }

        mDataBase.clearSessionCookies();
    }

    /**
     * Package level api, called from CookieManager Clear all expired cookies in
     * the database
     */
    void clearExpiredCookies(long now) {
        // null mDataBase implies that the host application doesn't support
        // persistent cookie.
        if (mDataBase == null) {
            return;
        }

        mDataBase.clearExpiredCookies(now);
    }

    protected void syncFromRamToFlash() {
        if (DebugFlags.COOKIE_SYNC_MANAGER) {
            Log.v(LOGTAG, "CookieSyncManager::syncFromRamToFlash STARTS");
        }

        CookieManager manager = CookieManager.getInstance();

        if (!manager.acceptCookie()) {
            return;
        }

        if (JniUtil.useChromiumHttpStack()) {
            manager.flushCookieStore();
        } else {
            ArrayList<Cookie> cookieList = manager.getUpdatedCookiesSince(mLastUpdate);
            mLastUpdate = System.currentTimeMillis();
            syncFromRamToFlash(cookieList);

            ArrayList<Cookie> lruList = manager.deleteLRUDomain();
            syncFromRamToFlash(lruList);
        }

        if (DebugFlags.COOKIE_SYNC_MANAGER) {
            Log.v(LOGTAG, "CookieSyncManager::syncFromRamToFlash DONE");
        }
    }

    private void syncFromRamToFlash(ArrayList<Cookie> list) {
        Iterator<Cookie> iter = list.iterator();
        while (iter.hasNext()) {
            Cookie cookie = iter.next();
            if (cookie.mode != Cookie.MODE_NORMAL) {
                if (cookie.mode != Cookie.MODE_NEW) {
                    mDataBase.deleteCookies(cookie.domain, cookie.path,
                            cookie.name);
                }
                if (cookie.mode != Cookie.MODE_DELETED) {
                    mDataBase.addCookie(cookie);
                    CookieManager.getInstance().syncedACookie(cookie);
                } else {
                    CookieManager.getInstance().deleteACookie(cookie);
                }
            }
        }
    }

    private static void checkInstanceIsCreated() {
        if (sRef == null) {
            throw new IllegalStateException(
                    "CookieSyncManager::createInstance() needs to be called "
                            + "before CookieSyncManager::getInstance()");
        }
    }
}
