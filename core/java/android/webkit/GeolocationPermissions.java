/*
 * Copyright (C) 2009 The Android Open Source Project
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
import android.util.Log;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Vector;


/**
 * This class is used to manage permissions for the WebView's Geolocation
 * JavaScript API.
 *
 * Geolocation permissions are applied to an origin, which consists of the
 * host, scheme and port of a URI. In order for web content to use the
 * Geolocation API, permission must be granted for that content's origin.
 *
 * This class stores Geolocation permissions. An origin's permission state can
 * be either allowed or denied. This class uses Strings to represent
 * an origin.
 *
 * When an origin attempts to use the Geolocation API, but no permission state
 * is currently set for that origin,
 * {@link WebChromeClient#onGeolocationPermissionsShowPrompt(String,GeolocationPermissions.Callback) WebChromeClient.onGeolocationPermissionsShowPrompt()}
 * is called. This allows the permission state to be set for that origin.
 *
 * The methods of this class can be used to modify and interrogate the stored
 * Geolocation permissions at any time.
 */
// This class is the Java counterpart of the WebKit C++ GeolocationPermissions
// class. It simply marshalls calls from the UI thread to the WebKit thread.
//
// Within WebKit, Geolocation permissions may be applied either temporarily
// (for the duration of the page) or permanently. This class deals only with
// permanent permissions.
public final class GeolocationPermissions {
    /**
     * A callback interface used by the host application to set the Geolocation
     * permission state for an origin.
     */
    public interface Callback {
        /**
         * Set the Geolocation permission state for the supplied origin.
         * @param origin The origin for which permissions are set.
         * @param allow Whether or not the origin should be allowed to use the
         *              Geolocation API.
         * @param retain Whether the permission should be retained beyond the
         *               lifetime of a page currently being displayed by a
         *               WebView.
         */
        public void invoke(String origin, boolean allow, boolean retain);
    };

    // Log tag
    private static final String TAG = "geolocationPermissions";

    // Global instance
    private static GeolocationPermissions sInstance;

    private Handler mHandler;
    private Handler mUIHandler;

    // A queue to store messages until the handler is ready.
    private Vector<Message> mQueuedMessages;

    // Message ids
    static final int GET_ORIGINS = 0;
    static final int GET_ALLOWED = 1;
    static final int CLEAR = 2;
    static final int ALLOW = 3;
    static final int CLEAR_ALL = 4;

    // Message ids on the UI thread
    static final int RETURN_ORIGINS = 0;
    static final int RETURN_ALLOWED = 1;

    private static final String ORIGINS = "origins";
    private static final String ORIGIN = "origin";
    private static final String CALLBACK = "callback";
    private static final String ALLOWED = "allowed";

    /**
     * Get the singleton instance of this class.
     * @return The singleton {@link GeolocationPermissions} instance.
     */
    public static GeolocationPermissions getInstance() {
      if (sInstance == null) {
          sInstance = new GeolocationPermissions();
      }
      return sInstance;
    }

    /**
     * Creates the UI message handler. Must be called on the UI thread.
     * @hide
     */
    public void createUIHandler() {
        if (mUIHandler == null) {
            mUIHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    // Runs on the UI thread.
                    switch (msg.what) {
                        case RETURN_ORIGINS: {
                            Map values = (Map) msg.obj;
                            Set<String> origins = (Set<String>) values.get(ORIGINS);
                            ValueCallback<Set<String> > callback = (ValueCallback<Set<String> >) values.get(CALLBACK);
                            callback.onReceiveValue(origins);
                        } break;
                        case RETURN_ALLOWED: {
                            Map values = (Map) msg.obj;
                            Boolean allowed = (Boolean) values.get(ALLOWED);
                            ValueCallback<Boolean> callback = (ValueCallback<Boolean>) values.get(CALLBACK);
                            callback.onReceiveValue(allowed);
                        } break;
                    }
                }
            };
        }
    }

    /**
     * Creates the message handler. Must be called on the WebKit thread.
     * @hide
     */
    public synchronized void createHandler() {
        if (mHandler == null) {
            mHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    // Runs on the WebKit thread.
                    switch (msg.what) {
                        case GET_ORIGINS: {
                            Set origins = nativeGetOrigins();
                            ValueCallback callback = (ValueCallback) msg.obj;
                            Map values = new HashMap<String, Object>();
                            values.put(CALLBACK, callback);
                            values.put(ORIGINS, origins);
                            postUIMessage(Message.obtain(null, RETURN_ORIGINS, values));
                            } break;
                        case GET_ALLOWED: {
                            Map values = (Map) msg.obj;
                            String origin = (String) values.get(ORIGIN);
                            ValueCallback callback = (ValueCallback) values.get(CALLBACK);
                            boolean allowed = nativeGetAllowed(origin);
                            Map retValues = new HashMap<String, Object>();
                            retValues.put(CALLBACK, callback);
                            retValues.put(ALLOWED, Boolean.valueOf(allowed));
                            postUIMessage(Message.obtain(null, RETURN_ALLOWED, retValues));
                            } break;
                        case CLEAR:
                            nativeClear((String) msg.obj);
                            break;
                        case ALLOW:
                            nativeAllow((String) msg.obj);
                            break;
                        case CLEAR_ALL:
                            nativeClearAll();
                            break;
                    }
                }
            };

            // Handle the queued messages
            if (mQueuedMessages != null) {
                while (!mQueuedMessages.isEmpty()) {
                    mHandler.sendMessage(mQueuedMessages.remove(0));
                }
                mQueuedMessages = null;
            }
        }
    }

    /**
     * Utility function to send a message to our handler.
     */
    private synchronized void postMessage(Message msg) {
        if (mHandler == null) {
            if (mQueuedMessages == null) {
                mQueuedMessages = new Vector<Message>();
            }
            mQueuedMessages.add(msg);
        } else {
            mHandler.sendMessage(msg);
        }
    }

    /**
     * Utility function to send a message to the handler on the UI thread
     */
    private void postUIMessage(Message msg) {
        if (mUIHandler != null) {
            mUIHandler.sendMessage(msg);
        }
    }

    /**
     * Get the set of origins for which Geolocation permissions are stored.
     * @param callback A {@link ValueCallback} to receive the result of this
     *                 request. This object's
     *                 {@link ValueCallback#onReceiveValue(T) onReceiveValue()}
     *                 method will be invoked asynchronously with a set of
     *                 Strings containing the origins for which Geolocation
     *                 permissions are stored.
     */
    // Note that we represent the origins as strings. These are created using
    // WebCore::SecurityOrigin::toString(). As long as all 'HTML 5 modules'
    // (Database, Geolocation etc) do so, it's safe to match up origins based
    // on this string.
    public void getOrigins(ValueCallback<Set<String> > callback) {
        if (callback != null) {
            if (WebViewCore.THREAD_NAME.equals(Thread.currentThread().getName())) {
                Set origins = nativeGetOrigins();
                callback.onReceiveValue(origins);
            } else {
                postMessage(Message.obtain(null, GET_ORIGINS, callback));
            }
        }
    }

    /**
     * Get the Geolocation permission state for the specified origin.
     * @param origin The origin for which Geolocation permission is requested.
     * @param callback A {@link ValueCallback} to receive the result of this
     *                 request. This object's
     *                 {@link ValueCallback#onReceiveValue(T) onReceiveValue()}
     *                 method will be invoked asynchronously with a boolean
     *                 indicating whether or not the origin can use the
     *                 Geolocation API.
     */
    public void getAllowed(String origin, ValueCallback<Boolean> callback) {
        if (callback == null) {
            return;
        }
        if (origin == null) {
            callback.onReceiveValue(null);
            return;
        }
        if (WebViewCore.THREAD_NAME.equals(Thread.currentThread().getName())) {
            boolean allowed = nativeGetAllowed(origin);
            callback.onReceiveValue(new Boolean(allowed));
        } else {
            Map values = new HashMap<String, Object>();
            values.put(ORIGIN, origin);
            values.put(CALLBACK, callback);
            postMessage(Message.obtain(null, GET_ALLOWED, values));
        }
    }

    /**
     * Clear the Geolocation permission state for the specified origin.
     * @param origin The origin for which Geolocation permissions are cleared.
     */
    // This method may be called before the WebKit
    // thread has intialized the message handler. Messages will be queued until
    // this time.
    public void clear(String origin) {
        // Called on the UI thread.
        postMessage(Message.obtain(null, CLEAR, origin));
    }

    /**
     * Allow the specified origin to use the Geolocation API.
     * @param origin The origin for which Geolocation API use is allowed.
     */
    // This method may be called before the WebKit
    // thread has intialized the message handler. Messages will be queued until
    // this time.
    public void allow(String origin) {
        // Called on the UI thread.
        postMessage(Message.obtain(null, ALLOW, origin));
    }

    /**
     * Clear the Geolocation permission state for all origins.
     */
    public void clearAll() {
        // Called on the UI thread.
        postMessage(Message.obtain(null, CLEAR_ALL));
    }

    // Native functions, run on the WebKit thread.
    private static native Set nativeGetOrigins();
    private static native boolean nativeGetAllowed(String origin);
    private static native void nativeClear(String origin);
    private static native void nativeAllow(String origin);
    private static native void nativeClearAll();
}
