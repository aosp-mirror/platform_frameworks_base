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
import android.util.Config;
import android.util.Log;
import android.webkit.CookieManager.Cookie;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * The class CookieSyncManager is used to synchronize the browser cookies
 * between RAM and FLASH. To get the best performance, browser cookie is saved
 * in RAM. We use a separate thread to sync the cookies between RAM and FLASH on
 * a timer base.
 * <p>
 * To use the CookieSyncManager, the host application has to call the following
 * when the application starts.
 * <p>
 * CookieSyncManager.createInstance(context)
 * <p>
 * To set up for sync, the host application has to call
 * <p>
 * CookieSyncManager.getInstance().startSync()
 * <p>
 * in its Activity.onResume(), and call
 * <p>
 * CookieSyncManager.getInstance().stopSync()
 * <p>
 * in its Activity.onStop().
 * <p>
 * To get instant sync instead of waiting for the timer to trigger, the host can
 * call
 * <p>
 * CookieSyncManager.getInstance().sync()
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
        if (sRef == null) {
            throw new IllegalStateException(
                    "CookieSyncManager::createInstance() needs to be called "
                            + "before CookieSyncManager::getInstance()");
        }
        return sRef;
    }

    /**
     * Create a singleton CookieSyncManager within a context
     * @param context
     * @return CookieSyncManager
     */
    public static synchronized CookieSyncManager createInstance(
            Context context) {
        if (sRef == null) {
            sRef = new CookieSyncManager(context);
        }
        return sRef;
    }

    /**
     * Package level api, called from CookieManager Get all the cookies which
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
        if (Config.LOGV) {
            Log.v(LOGTAG, "CookieSyncManager::syncFromRamToFlash STARTS");
        }

        if (!CookieManager.getInstance().acceptCookie()) {
            return;
        }

        ArrayList<Cookie> cookieList = CookieManager.getInstance()
                .getUpdatedCookiesSince(mLastUpdate);
        mLastUpdate = System.currentTimeMillis();
        syncFromRamToFlash(cookieList);

        ArrayList<Cookie> lruList =
                CookieManager.getInstance().deleteLRUDomain();
        syncFromRamToFlash(lruList);

        if (Config.LOGV) {
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
}
