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


/**
 * This class is used to get Geolocation permissions from, and set them on the
 * WebView. For example, it could be used to allow a user to manage Geolocation
 * permissions from a browser's UI.
 *
 * Permissions are managed on a per-origin basis, as required by the
 * Geolocation spec - http://dev.w3.org/geo/api/spec-source.html. An origin
 * specifies the scheme, host and port of particular frame. An origin is
 * represented here as a string, using the output of
 * WebCore::SecurityOrigin::toString.
 *
 * This class is the Java counterpart of the WebKit C++ GeolocationPermissions
 * class. It simply marshalls calls from the UI thread to the WebKit thread.
 *
 * Within WebKit, Geolocation permissions may be applied either temporarily
 * (for the duration of the page) or permanently. This class deals only with
 * permanent permissions.
 */
public final class GeolocationPermissions {
    /**
     * Callback interface used by the browser to report a Geolocation permission
     * state set by the user in response to a permissions prompt.
     */
    public interface Callback {
        public void invoke(String origin, boolean allow, boolean remember);
    };

    // Log tag
    private static final String TAG = "geolocationPermissions";

    // Global instance
    private static GeolocationPermissions sInstance;

    private Handler mHandler;
    private Handler mUIHandler;

    // Members used to transfer the origins and permissions between threads.
    private Set<String> mOrigins;
    private boolean mAllowed;
    private Set<String> mOriginsToClear;
    private Set<String> mOriginsToAllow;

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
     * Gets the singleton instance of the class.
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
    public void createHandler() {
        if (mHandler == null) {
            mHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    // Runs on the WebKit thread.
                    switch (msg.what) {
                        case GET_ORIGINS: {
                            getOriginsImpl();
                            ValueCallback callback = (ValueCallback) msg.obj;
                            Map values = new HashMap<String, Object>();
                            values.put(CALLBACK, callback);
                            values.put(ORIGINS, mOrigins);
                            postUIMessage(Message.obtain(null, RETURN_ORIGINS, values));
                            } break;
                        case GET_ALLOWED: {
                            Map values = (Map) msg.obj;
                            String origin = (String) values.get(ORIGIN);
                            ValueCallback callback = (ValueCallback) values.get(CALLBACK);
                            getAllowedImpl(origin);
                            Map retValues = new HashMap<String, Object>();
                            retValues.put(CALLBACK, callback);
                            retValues.put(ALLOWED, new Boolean(mAllowed));
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

            if (mOriginsToClear != null) {
                for (String origin : mOriginsToClear) {
                    nativeClear(origin);
                }
            }
            if (mOriginsToAllow != null) {
                for (String origin : mOriginsToAllow) {
                    nativeAllow(origin);
                }
            }
        }
    }

    /**
     * Utility function to send a message to our handler.
     */
    private void postMessage(Message msg) {
        assert(mHandler != null);
        mHandler.sendMessage(msg);
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
     * Gets the set of origins for which Geolocation permissions are stored.
     * Note that we represent the origins as strings. These are created using
     * WebCore::SecurityOrigin::toString(). As long as all 'HTML 5 modules'
     * (Database, Geolocation etc) do so, it's safe to match up origins based
     * on this string.
     *
     * Callback is a ValueCallback object whose onReceiveValue method will be
     * called asynchronously with the set of origins.
     */
    public void getOrigins(ValueCallback<Set<String> > callback) {
        if (callback != null) {
            if (WebViewCore.THREAD_NAME.equals(Thread.currentThread().getName())) {
                getOriginsImpl();
                callback.onReceiveValue(mOrigins);
            } else {
                postMessage(Message.obtain(null, GET_ORIGINS, callback));
            }
        }
    }

    /**
     * Helper method to get the set of origins.
     */
    private void getOriginsImpl() {
        // Called on the WebKit thread.
        mOrigins = nativeGetOrigins();
    }

    /**
     * Gets the permission state for the specified origin.
     *
     * Callback is a ValueCallback object whose onReceiveValue method will be
     * called asynchronously with the permission state for the origin.
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
            getAllowedImpl(origin);
            callback.onReceiveValue(new Boolean(mAllowed));
        } else {
            Map values = new HashMap<String, Object>();
            values.put(ORIGIN, origin);
            values.put(CALLBACK, callback);
            postMessage(Message.obtain(null, GET_ALLOWED, values));
        }
    }

    /**
     * Helper method to get the permission state for the specified origin.
     */
    private void getAllowedImpl(String origin) {
        // Called on the WebKit thread.
        mAllowed = nativeGetAllowed(origin);
    }

    /**
     * Clears the permission state for the specified origin. This method may be
     * called before the WebKit thread has intialized the message handler.
     * Messages will be queued until this time.
     */
    public void clear(String origin) {
        // Called on the UI thread.
        if (mHandler == null) {
            if (mOriginsToClear == null) {
                mOriginsToClear = new HashSet<String>();
            }
            mOriginsToClear.add(origin);
            if (mOriginsToAllow != null) {
                mOriginsToAllow.remove(origin);
            }
        } else {
            postMessage(Message.obtain(null, CLEAR, origin));
        }
    }

    /**
     * Allows the specified origin. This method may be called before the WebKit
     * thread has intialized the message handler. Messages will be queued until
     * this time.
     */
    public void allow(String origin) {
        // Called on the UI thread.
        if (mHandler == null) {
            if (mOriginsToAllow == null) {
                mOriginsToAllow = new HashSet<String>();
            }
            mOriginsToAllow.add(origin);
            if (mOriginsToClear != null) {
                mOriginsToClear.remove(origin);
            }
        } else {
            postMessage(Message.obtain(null, ALLOW, origin));
        }
    }

    /**
     * Clears the permission state for all origins.
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
