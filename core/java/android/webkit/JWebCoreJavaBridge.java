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

import android.net.ProxyProperties;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Set;

final class JWebCoreJavaBridge extends Handler {
    // Identifier for the timer message.
    private static final int TIMER_MESSAGE = 1;
    // ID for servicing functionptr queue
    private static final int FUNCPTR_MESSAGE = 2;
    // Log system identifier.
    private static final String LOGTAG = "webkit-timers";

    // Native object pointer for interacting in native code.
    private int mNativeBridge;
    // Instant timer is used to implement a timer that needs to fire almost
    // immediately.
    private boolean mHasInstantTimer;

    private boolean mTimerPaused;
    private boolean mHasDeferredTimers;

    // keep track of the main WebView attached to the current window so that we
    // can get the proper Context.
    private static WeakReference<WebView> sCurrentMainWebView =
            new WeakReference<WebView>(null);

    /* package */
    static final int REFRESH_PLUGINS = 100;

    private HashMap<String, String> mContentUriToFilePathMap;

    /**
     * Construct a new JWebCoreJavaBridge to interface with
     * WebCore timers and cookies.
     */
    public JWebCoreJavaBridge() {
        nativeConstructor();

    }

    @Override
    protected void finalize() {
        nativeFinalize();
    }

    static synchronized void setActiveWebView(WebView webview) {
        if (sCurrentMainWebView.get() != null) {
            // it is possible if there is a sub-WebView. Do nothing.
            return;
        }
        sCurrentMainWebView = new WeakReference<WebView>(webview);
    }

    static synchronized void removeActiveWebView(WebView webview) {
        if (sCurrentMainWebView.get() != webview) {
            // it is possible if there is a sub-WebView. Do nothing.
            return;
        }
        sCurrentMainWebView.clear();
    }

    /**
     * Call native timer callbacks.
     */
    private void fireSharedTimer() { 
        // clear the flag so that sharedTimerFired() can set a new timer
        mHasInstantTimer = false;
        sharedTimerFired();
    }

    /**
     * handleMessage
     * @param msg The dispatched message.
     *
     * The only accepted message currently is TIMER_MESSAGE
     */
    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case TIMER_MESSAGE: {
                if (mTimerPaused) {
                    mHasDeferredTimers = true;
                } else {
                    fireSharedTimer();
                }
                break;
            }
            case FUNCPTR_MESSAGE:
                nativeServiceFuncPtrQueue();
                break;
            case REFRESH_PLUGINS:
                nativeUpdatePluginDirectories(PluginManager.getInstance(null)
                        .getPluginDirectories(), ((Boolean) msg.obj)
                        .booleanValue());
                break;
        }
    }
    
    // called from JNI side
    private void signalServiceFuncPtrQueue() {
        Message msg = obtainMessage(FUNCPTR_MESSAGE);
        sendMessage(msg);
    }
    
    private native void nativeServiceFuncPtrQueue();

    /**
     * Pause all timers.
     */
    public void pause() {
        if (!mTimerPaused) {
            mTimerPaused = true;
            mHasDeferredTimers = false;
        }
    }

    /**
     * Resume all timers.
     */
    public void resume() {
        if (mTimerPaused) {
           mTimerPaused = false;
           if (mHasDeferredTimers) {
               mHasDeferredTimers = false;
               fireSharedTimer();
           }
        }
    }

    /**
     * Set WebCore cache size.
     * @param bytes The cache size in bytes.
     */
    public native void setCacheSize(int bytes);

    /**
     * Store a cookie string associated with a url.
     * @param url The url to be used as a key for the cookie.
     * @param value The cookie string to be stored.
     */
    private void setCookies(String url, String value) {
        if (value.contains("\r") || value.contains("\n")) {
            // for security reason, filter out '\r' and '\n' from the cookie
            int size = value.length();
            StringBuilder buffer = new StringBuilder(size);
            int i = 0;
            while (i != -1 && i < size) {
                int ir = value.indexOf('\r', i);
                int in = value.indexOf('\n', i);
                int newi = (ir == -1) ? in : (in == -1 ? ir : (ir < in ? ir
                        : in));
                if (newi > i) {
                    buffer.append(value.subSequence(i, newi));
                } else if (newi == -1) {
                    buffer.append(value.subSequence(i, size));
                    break;
                }
                i = newi + 1;
            }
            value = buffer.toString();
        }
        CookieManager.getInstance().setCookie(url, value);
    }

    /**
     * Retrieve the cookie string for the given url.
     * @param url The resource's url.
     * @return A String representing the cookies for the given resource url.
     */
    private String cookies(String url) {
        return CookieManager.getInstance().getCookie(url);
    }

    /**
     * Returns whether cookies are enabled or not.
     */
    private boolean cookiesEnabled() {
        return CookieManager.getInstance().acceptCookie();
    }

    /**
     * Returns an array of plugin directoies
     */
    private String[] getPluginDirectories() {
        return PluginManager.getInstance(null).getPluginDirectories();
    }

    /**
     * Returns the path of the plugin data directory
     */
    private String getPluginSharedDataDirectory() {
        return PluginManager.getInstance(null).getPluginSharedDataDirectory();
    }

    /**
     * setSharedTimer
     * @param timemillis The relative time when the timer should fire
     */
    private void setSharedTimer(long timemillis) {
        if (DebugFlags.J_WEB_CORE_JAVA_BRIDGE) Log.v(LOGTAG, "setSharedTimer " + timemillis);

        if (timemillis <= 0) {
            // we don't accumulate the sharedTimer unless it is a delayed
            // request. This way we won't flood the message queue with
            // WebKit messages. This should improve the browser's
            // responsiveness to key events.
            if (mHasInstantTimer) {
                return;
            } else {
                mHasInstantTimer = true;
                Message msg = obtainMessage(TIMER_MESSAGE);
                sendMessageDelayed(msg, timemillis);
            }
        } else {
            Message msg = obtainMessage(TIMER_MESSAGE);
            sendMessageDelayed(msg, timemillis);
        }
    }

    /**
     * Stop the shared timer.
     */
    private void stopSharedTimer() {
        if (DebugFlags.J_WEB_CORE_JAVA_BRIDGE) {
            Log.v(LOGTAG, "stopSharedTimer removing all timers");
        }
        removeMessages(TIMER_MESSAGE);
        mHasInstantTimer = false;
        mHasDeferredTimers = false;
    }

    private String[] getKeyStrengthList() {
        return CertTool.getKeyStrengthList();
    }

    synchronized private String getSignedPublicKey(int index, String challenge,
            String url) {
        WebView current = sCurrentMainWebView.get();
        if (current != null) {
            // generateKeyPair expects organizations which we don't have. Ignore
            // url.
            return CertTool.getSignedPublicKey(
                    current.getContext(), index, challenge);
        } else {
            Log.e(LOGTAG, "There is no active WebView for getSignedPublicKey");
            return "";
        }
    }

    // Called on the WebCore thread through JNI.
    private String resolveFilePathForContentUri(String uri) {
        if (mContentUriToFilePathMap != null) {
            String fileName = mContentUriToFilePathMap.get(uri);
            if (fileName != null) {
                return fileName;
            }
        }

        // Failsafe fallback to just use the last path segment.
        // (See OpenableColumns documentation in the SDK)
        Uri jUri = Uri.parse(uri);
        return jUri.getLastPathSegment();
    }

    public void storeFilePathForContentUri(String path, String contentUri) {
        if (mContentUriToFilePathMap == null) {
            mContentUriToFilePathMap = new HashMap<String, String>();
        }
        mContentUriToFilePathMap.put(contentUri, path);
    }

    public void updateProxy(ProxyProperties proxyProperties) {
        if (proxyProperties == null) {
            nativeUpdateProxy("", "");
            return;
        }

        String host = proxyProperties.getHost();
        int port = proxyProperties.getPort();
        if (port != 0)
            host += ":" + port;

        nativeUpdateProxy(host, proxyProperties.getExclusionList());
    }

    private native void nativeConstructor();
    private native void nativeFinalize();
    private native void sharedTimerFired();
    private native void nativeUpdatePluginDirectories(String[] directories,
            boolean reload);
    public native void setNetworkOnLine(boolean online);
    public native void setNetworkType(String type, String subtype);
    public native void addPackageNames(Set<String> packageNames);
    public native void addPackageName(String packageName);
    public native void removePackageName(String packageName);
    public native void nativeUpdateProxy(String newProxy, String exclusionList);
}
