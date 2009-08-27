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
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.HashSet;
import java.util.Set;


/**
 * Implements the Java side of GeolocationPermissions. Simply marshalls calls
 * from the UI thread to the WebKit thread.
 * @hide
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

    // Members used to transfer the origins and permissions between threads.
    private Set<String> mOrigins;
    private boolean mAllowed;
    private Set<String> mOriginsToClear;
    private Set<String> mOriginsToAllow;
    private static Lock mLock = new ReentrantLock();
    private static boolean mUpdated;
    private static Condition mUpdatedCondition = mLock.newCondition();

    // Message ids
    static final int GET_ORIGINS = 0;
    static final int GET_ALLOWED = 1;
    static final int CLEAR = 2;
    static final int ALLOW = 3;
    static final int CLEAR_ALL = 4;

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
     * Creates the message handler. Must be called on the WebKit thread.
     */
    public void createHandler() {
        mLock.lock();
        if (mHandler == null) {
            mHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    // Runs on the WebKit thread.
                    switch (msg.what) {
                        case GET_ORIGINS:
                            getOriginsImpl();
                            break;
                        case GET_ALLOWED:
                            getAllowedImpl((String) msg.obj);
                            break;
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
        mLock.unlock();
    }

    /**
     * Utility function to send a message to our handler.
     */
    private void postMessage(Message msg) {
        assert(mHandler != null);
        mHandler.sendMessage(msg);
    }

    /**
     * Gets the set of origins for which Geolocation permissions are stored.
     * Note that we represent the origins as strings. These are created using
     * WebCore::SecurityOrigin::toString(). As long as all 'HTML 5 modules'
     * (Database, Geolocation etc) do so, it's safe to match up origins for the
     * purposes of displaying UI.
     */
    public Set getOrigins() {
        // Called on the UI thread.
        Set origins = null;
        mLock.lock();
        try {
            mUpdated = false;
            postMessage(Message.obtain(null, GET_ORIGINS));
            while (!mUpdated) {
                mUpdatedCondition.await();
            }
            origins = mOrigins;
        } catch (InterruptedException e) {
            Log.e(TAG, "Exception while waiting for update", e);
        } finally {
            mLock.unlock();
        }
        return origins;
    }

    /**
     * Helper method to get the set of origins.
     */
    private void getOriginsImpl() {
        // Called on the WebKit thread.
        mLock.lock();
        mOrigins = nativeGetOrigins();
        mUpdated = true;
        mUpdatedCondition.signal();
        mLock.unlock();
    }

    /**
     * Gets the permission state for the specified origin.
     */
    public boolean getAllowed(String origin) {
        // Called on the UI thread.
        boolean allowed = false;
        mLock.lock();
        try {
            mUpdated = false;
            postMessage(Message.obtain(null, GET_ALLOWED, origin));
            while (!mUpdated) {
                mUpdatedCondition.await();
            }
            allowed = mAllowed;
        } catch (InterruptedException e) {
            Log.e(TAG, "Exception while waiting for update", e);
        } finally {
            mLock.unlock();
        }
        return allowed;
    }

    /**
     * Helper method to get the permission state.
     */
    private void getAllowedImpl(String origin) {
        // Called on the WebKit thread.
        mLock.lock();
        mAllowed = nativeGetAllowed(origin);
        mUpdated = true;
        mUpdatedCondition.signal();
        mLock.unlock();
    }

    /**
     * Clears the permission state for the specified origin. This method may be
     * called before the WebKit thread has intialized the message handler.
     * Messages will be queued until this time.
     */
    public void clear(String origin) {
        // Called on the UI thread.
        mLock.lock();
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
        mLock.unlock();
    }

    /**
     * Allows the specified origin. This method may be called before the WebKit
     * thread has intialized the message handler. Messages will be queued until
     * this time.
     */
    public void allow(String origin) {
        // Called on the UI thread.
        mLock.lock();
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
        mLock.unlock();
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
