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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.http.*;
import android.os.*;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;

import junit.framework.Assert;

class Network {

    private static final String LOGTAG = "network";

    /**
     * Static instance of a Network object.
     */
    private static Network sNetwork;
    
    /**
     * Flag to store the state of platform notifications, for the case
     * when the Network object has not been constructed yet
     */
    private static boolean sPlatformNotifications;
    
    /**
     * Reference count for platform notifications as the network class is a 
     * static and can exist over multiple activities, thus over multiple 
     * onPause/onResume pairs. 
     */
    private static int sPlatformNotificationEnableRefCount;

    /**
     * Proxy username if known (used for pre-emptive proxy authentication).
     */
    private String mProxyUsername;

    /**
     * Proxy password if known (used for pre-emptive proxy authentication).
     */
    private String mProxyPassword;

    /**
     * Network request queue (requests are added from the browser thread).
     */
    private RequestQueue mRequestQueue;

    /**
     * SSL error handler: takes care of synchronization of multiple async
     * loaders with SSL-related problems.
     */
    private SslErrorHandlerImpl mSslErrorHandler;

    /**
     * HTTP authentication handler: takes care of synchronization of HTTP
     * authentication requests.
     */
    private HttpAuthHandlerImpl mHttpAuthHandler;

    private Context mContext;

    /**
     * True if the currently used network connection is a roaming phone
     * connection.
     */
    private boolean mRoaming;

    /**
     * Tracks if we are roaming.
     */
    private RoamingMonitor mRoamingMonitor;

    /**
     * @return The singleton instance of the network.
     */
    public static synchronized Network getInstance(Context context) {
        if (sNetwork == null) {
            // Note Context of the Application is used here, rather than
            // the what is passed in (usually a Context derived from an 
            // Activity) so the intent receivers belong to the application
            // rather than an activity - this fixes the issue where 
            // Activities are created and destroyed during the lifetime of
            // an Application
            sNetwork = new Network(context.getApplicationContext());
            if (sPlatformNotifications) {
                // Adjust the ref count before calling enable as it is already
                // taken into account when the static function was called 
                // directly
                --sPlatformNotificationEnableRefCount;
                enablePlatformNotifications();
            }
        }
        return sNetwork;
    }


    /**
     * Enables data state and proxy tracking
     */
    public static void enablePlatformNotifications() {
        if (++sPlatformNotificationEnableRefCount == 1) {
            if (sNetwork != null) {
                sNetwork.mRequestQueue.enablePlatformNotifications();
                sNetwork.monitorRoaming();
            } else {
                sPlatformNotifications = true;
            }
        }
    }

    /**
     * If platform notifications are enabled, this should be called
     * from onPause() or onStop()
     */
    public static void disablePlatformNotifications() {
        if (--sPlatformNotificationEnableRefCount == 0) {
            if (sNetwork != null) {
                sNetwork.mRequestQueue.disablePlatformNotifications();
                sNetwork.stopMonitoringRoaming();
            } else {
                sPlatformNotifications = false;
            }
        }
    }

    /**
     * Creates a new Network object.
     * XXX: Must be created in the same thread as WebCore!!!!!
     */
    private Network(Context context) {
        if (DebugFlags.NETWORK) {
            Assert.assertTrue(Thread.currentThread().
                    getName().equals(WebViewCore.THREAD_NAME));
        }
        mContext = context;
        mSslErrorHandler = new SslErrorHandlerImpl();
        mHttpAuthHandler = new HttpAuthHandlerImpl(this);

        mRequestQueue = new RequestQueue(context);
    }

    private class RoamingMonitor extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction()))
                return;

            NetworkInfo info = (NetworkInfo)intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
            if (info != null)
                mRoaming = info.isRoaming();
        };
    };

    private void monitorRoaming() {
        mRoamingMonitor = new RoamingMonitor();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        mContext.registerReceiver(sNetwork.mRoamingMonitor, filter);
    }

    private void stopMonitoringRoaming() {
        if (mRoamingMonitor != null) {
            mContext.unregisterReceiver(mRoamingMonitor);
            mRoamingMonitor = null;
        }
    }

    /**
     * Request a url from either the network or the file system.
     * @param url The url to load.
     * @param method The http method.
     * @param headers The http headers.
     * @param postData The body of the request.
     * @param loader A LoadListener for receiving the results of the request.
     * @return True if the request was successfully queued.
     */
    public boolean requestURL(String method,
                              Map<String, String> headers,
                              byte [] postData,
                              LoadListener loader) {

        String url = loader.url();

        // Not a valid url, return false because we won't service the request!
        if (!URLUtil.isValidUrl(url)) {
            return false;
        }

        // asset, res, file system or data stream are handled in the other code
        // path. This only handles network request.
        if (URLUtil.isAssetUrl(url) || URLUtil.isResourceUrl(url)
                || URLUtil.isFileUrl(url) || URLUtil.isDataUrl(url)) {
            return false;
        }

        // If this is a prefetch, abort it if we're roaming.
        if (mRoaming && headers.containsKey("X-Moz") && "prefetch".equals(headers.get("X-Moz"))) {
            return false;
        }

        /* FIXME: this is lame.  Pass an InputStream in, rather than
           making this lame one here */
        InputStream bodyProvider = null;
        int bodyLength = 0;
        if (postData != null) {
            bodyLength = postData.length;
            bodyProvider = new ByteArrayInputStream(postData);
        }

        RequestQueue q = mRequestQueue;
        RequestHandle handle = null;
        if (loader.isSynchronous()) {
            handle = q.queueSynchronousRequest(url, loader.getWebAddress(),
                    method, headers, loader, bodyProvider, bodyLength);
            loader.attachRequestHandle(handle);
            handle.processRequest();
            loader.loadSynchronousMessages();
        } else {
            handle = q.queueRequest(url, loader.getWebAddress(), method,
                    headers, loader, bodyProvider, bodyLength);
            // FIXME: Although this is probably a rare condition, normal network
            // requests are processed in a separate thread. This means that it
            // is possible to process part of the request before setting the
            // request handle on the loader. We should probably refactor this to
            // ensure the handle is attached before processing begins.
            loader.attachRequestHandle(handle);
        }

        return true;
    }

    /**
     * @return True iff there is a valid proxy set.
     */
    public boolean isValidProxySet() {
        // The proxy host and port can be set within a different thread during
        // an Intent broadcast.
        synchronized (mRequestQueue) {
            return mRequestQueue.getProxyHost() != null;
        }
    }

    /**
     * Get the proxy hostname.
     * @return The proxy hostname obtained from the network queue and proxy
     *         settings.
     */
    public String getProxyHostname() {
        return mRequestQueue.getProxyHost().getHostName();
    }

    /**
     * @return The proxy username or null if none.
     */
    public synchronized String getProxyUsername() {
        return mProxyUsername;
    }

    /**
     * Sets the proxy username.
     * @param proxyUsername Username to use when
     * connecting through the proxy.
     */
    public synchronized void setProxyUsername(String proxyUsername) {
        if (DebugFlags.NETWORK) {
            Assert.assertTrue(isValidProxySet());
        }

        mProxyUsername = proxyUsername;
    }

    /**
     * @return The proxy password or null if none.
     */
    public synchronized String getProxyPassword() {
        return mProxyPassword;
    }

    /**
     * Sets the proxy password.
     * @param proxyPassword Password to use when
     * connecting through the proxy.
     */
    public synchronized void setProxyPassword(String proxyPassword) {
        if (DebugFlags.NETWORK) {
            Assert.assertTrue(isValidProxySet());
        }

        mProxyPassword = proxyPassword;
    }

    /**
     * Saves the state of network handlers (user SSL and HTTP-authentication
     * preferences).
     * @param outState The out-state to save (write) to.
     * @return True iff succeeds.
     */
    public boolean saveState(Bundle outState) {
        if (DebugFlags.NETWORK) {
            Log.v(LOGTAG, "Network.saveState()");
        }

        return mSslErrorHandler.saveState(outState);
    }

    /**
     * Restores the state of network handlers (user SSL and HTTP-authentication
     * preferences).
     * @param inState The in-state to load (read) from.
     * @return True iff succeeds.
     */
    public boolean restoreState(Bundle inState) {
        if (DebugFlags.NETWORK) {
            Log.v(LOGTAG, "Network.restoreState()");
        }

        return mSslErrorHandler.restoreState(inState);
    }

    /**
     * Clears user SSL-error preference table.
     */
    public void clearUserSslPrefTable() {
        mSslErrorHandler.clear();
    }

    /**
     * Handles SSL error(s) on the way up to the user: the user must decide
     * whether errors should be ignored or not.
     * @param loader The loader that resulted in SSL errors.
     */
    public void handleSslErrorRequest(LoadListener loader) {
        if (DebugFlags.NETWORK) Assert.assertNotNull(loader);
        if (loader != null) {
            mSslErrorHandler.handleSslErrorRequest(loader);
        }
    }

    /* package */ boolean checkSslPrefTable(LoadListener loader,
            SslError error) {
        if (loader != null && error != null) {
            return mSslErrorHandler.checkSslPrefTable(loader, error);
        }
        return false;
    }

     /**
     * Handles authentication requests on their way up to the user (the user
     * must provide credentials).
     * @param loader The loader that resulted in an HTTP
     * authentication request.
     */
    public void handleAuthRequest(LoadListener loader) {
        if (DebugFlags.NETWORK) Assert.assertNotNull(loader);
        if (loader != null) {
            mHttpAuthHandler.handleAuthRequest(loader);
        }
    }

    // Performance probe
    public void startTiming() {
        mRequestQueue.startTiming();
    }

    public void stopTiming() {
        mRequestQueue.stopTiming();
    }
}
