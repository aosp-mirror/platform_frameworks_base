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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * Functionality for manipulating the webstorage databases.
 */
public final class WebStorage {

    /**
     * Encapsulates a callback function to be executed when a new quota is made
     * available. We primarily want this to allow us to call back the sleeping
     * WebCore thread from outside the WebViewCore class (as the native call
     * is private). It is imperative that this the setDatabaseQuota method is
     * executed once a decision to either allow or deny new quota is made,
     * otherwise the WebCore thread will remain asleep.
     */
    public interface QuotaUpdater {
        public void updateQuota(long newQuota);
    };

    // Log tag
    private static final String TAG = "webstorage";

    // Global instance of a WebStorage
    private static WebStorage sWebStorage;

    // We keep a copy of the origins, quotas and usages
    // that we protect via a lock and update in syncValues()
    private static Lock mLock = new ReentrantLock();
    private static Condition mCacheUpdated = mLock.newCondition();

    // Message ids
    static final int UPDATE = 0;
    static final int SET_QUOTA_ORIGIN = 1;
    static final int DELETE_ORIGIN = 2;
    static final int DELETE_ALL = 3;

    private Set <String> mOrigins;
    private HashMap <String, Long> mQuotas = new HashMap<String, Long>();
    private HashMap <String, Long> mUsages = new HashMap<String, Long>();

    private Handler mHandler = null;

    private class Origin {
        String mOrigin = null;
        long mQuota = 0;

        public Origin(String origin, long quota) {
            mOrigin = origin;
            mQuota = quota;
        }

        public Origin(String origin) {
            mOrigin = origin;
        }

        public String getOrigin() {
            return mOrigin;
        }

        public long getQuota() {
            return mQuota;
        }
    }

    /**
     * @hide
     * Message handler
     */
    public void createHandler() {
        if (mHandler == null) {
            mHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case SET_QUOTA_ORIGIN: {
                            Origin website = (Origin) msg.obj;
                            nativeSetQuotaForOrigin(website.getOrigin(),
                                                    website.getQuota());
                            syncValues();
                            } break;

                        case DELETE_ORIGIN: {
                            Origin website = (Origin) msg.obj;
                            nativeDeleteOrigin(website.getOrigin());
                            syncValues();
                            } break;

                        case DELETE_ALL:
                            nativeDeleteAllData();
                            syncValues();
                            break;

                        case UPDATE:
                            syncValues();
                            break;
                    }
                }
            };
        }
    }

    /**
     * @hide
     * Returns a list of origins having a database
     */
    public Set getOrigins() {
        Set ret = null;
        mLock.lock();
        try {
            update();
            mCacheUpdated.await();
            ret = mOrigins;
        } catch (InterruptedException e) {
            Log.e(TAG, "Exception while waiting on the updated origins", e);
        } finally {
            mLock.unlock();
        }
        return ret;
    }

    /**
     * @hide
     * Returns the use for a given origin
     */
    public long getUsageForOrigin(String origin) {
        long ret = 0;
        if (origin == null) {
          return ret;
        }
        mLock.lock();
        try {
            update();
            mCacheUpdated.await();
            Long usage = mUsages.get(origin);
            if (usage != null) {
                ret = usage.longValue();
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Exception while waiting on the updated origins", e);
        } finally {
            mLock.unlock();
        }
        return ret;
    }

    /**
     * @hide
     * Returns the quota for a given origin
     */
    public long getQuotaForOrigin(String origin) {
        long ret = 0;
        if (origin == null) {
          return ret;
        }
        mLock.lock();
        try {
            update();
            mCacheUpdated.await();
            Long quota = mQuotas.get(origin);
            if (quota != null) {
                ret = quota.longValue();
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Exception while waiting on the updated origins", e);
        } finally {
            mLock.unlock();
        }
        return ret;
    }

    /**
     * @hide
     * Set the quota for a given origin
     */
    public void setQuotaForOrigin(String origin, long quota) {
        if (origin != null) {
            if (WebViewCore.THREAD_NAME.equals(Thread.currentThread().getName())) {
                nativeSetQuotaForOrigin(origin, quota);
                syncValues();
            } else {
                postMessage(Message.obtain(null, SET_QUOTA_ORIGIN,
                    new Origin(origin, quota)));
            }
        }
    }

    /**
     * @hide
     * Delete a given origin
     */
    public void deleteOrigin(String origin) {
        if (origin != null) {
            if (WebViewCore.THREAD_NAME.equals(Thread.currentThread().getName())) {
                nativeDeleteOrigin(origin);
                syncValues();
            } else {
                postMessage(Message.obtain(null, DELETE_ORIGIN,
                    new Origin(origin)));
            }
        }
    }

    /**
     * @hide
     * Delete all databases
     */
    public void deleteAllData() {
        if (WebViewCore.THREAD_NAME.equals(Thread.currentThread().getName())) {
            nativeDeleteAllData();
            syncValues();
        } else {
            postMessage(Message.obtain(null, DELETE_ALL));
        }
    }

    /**
     * Utility function to send a message to our handler
     */
    private void postMessage(Message msg) {
        if (mHandler != null) {
            mHandler.sendMessage(msg);
        }
    }

    /**
     * @hide
     * Get the global instance of WebStorage.
     * @return A single instance of WebStorage.
     */
    public static WebStorage getInstance() {
      if (sWebStorage == null) {
          sWebStorage = new WebStorage();
      }
      return sWebStorage;
    }

    /**
     * @hide
     * Post a Sync request
     */
    public void update() {
        if (WebViewCore.THREAD_NAME.equals(Thread.currentThread().getName())) {
            syncValues();
        } else {
            postMessage(Message.obtain(null, UPDATE));
        }
    }

    /**
     * Run on the webcore thread
     * sync the local cached values with the real ones
     */
    private void syncValues() {
        mLock.lock();
        Set tmp = nativeGetOrigins();
        mOrigins = new HashSet<String>();
        mQuotas.clear();
        mUsages.clear();
        Iterator<String> iter = tmp.iterator();
        while (iter.hasNext()) {
            String origin = iter.next();
            mOrigins.add(origin);
            mQuotas.put(origin, new Long(nativeGetQuotaForOrigin(origin)));
            mUsages.put(origin, new Long(nativeGetUsageForOrigin(origin)));
        }
        mCacheUpdated.signal();
        mLock.unlock();
    }

    // Native functions
    private static native Set nativeGetOrigins();
    private static native long nativeGetUsageForOrigin(String origin);
    private static native long nativeGetQuotaForOrigin(String origin);
    private static native void nativeSetQuotaForOrigin(String origin, long quota);
    private static native void nativeDeleteOrigin(String origin);
    private static native void nativeDeleteAllData();
}
