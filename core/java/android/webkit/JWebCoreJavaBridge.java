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

import android.os.Handler;
import android.os.Message;
import android.security.CertTool;
import android.util.Log;

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
    // Reference count the pause/resume of timers
    private int mPauseTimerRefCount;

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
                PerfChecker checker = new PerfChecker();
                // clear the flag so that sharedTimerFired() can set a new timer
                mHasInstantTimer = false;
                sharedTimerFired();
                checker.responseAlert("sharedTimer");
                break;
            }
            case FUNCPTR_MESSAGE:
                nativeServiceFuncPtrQueue();
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
        if (--mPauseTimerRefCount == 0) {
            setDeferringTimers(true);
        }
    }

    /**
     * Resume all timers.
     */
    public void resume() {
        if (++mPauseTimerRefCount == 1) {
            setDeferringTimers(false);
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
     * @param docUrl The policy base url used by WebCore.
     * @param value The cookie string to be stored.
     */
    private void setCookies(String url, String docUrl, String value) {
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
     * setSharedTimer
     * @param timemillis The relative time when the timer should fire
     */
    private void setSharedTimer(long timemillis) {
        if (WebView.LOGV_ENABLED) Log.v(LOGTAG, "setSharedTimer " + timemillis);

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
        if (WebView.LOGV_ENABLED) {
            Log.v(LOGTAG, "stopSharedTimer removing all timers");
        }
        removeMessages(TIMER_MESSAGE);
        mHasInstantTimer = false;
    }

    private String[] getKeyStrengthList() {
        return CertTool.getInstance().getSupportedKeyStrenghs();
    }

    private String getSignedPublicKey(int index, String challenge, String url) {
        // generateKeyPair expects organizations which we don't have. Ignore url.
        return CertTool.getInstance().generateKeyPair(index, challenge, null);
    }

    private native void nativeConstructor();
    private native void nativeFinalize();
    private native void sharedTimerFired();
    private native void setDeferringTimers(boolean defer);
    public native void setNetworkOnLine(boolean online);
}
