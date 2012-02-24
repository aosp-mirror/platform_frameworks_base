/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.view.accessibility;

import android.os.Process;
import android.util.Log;
import android.util.LongSparseArray;

/**
 * Simple cache for AccessibilityNodeInfos. The cache is mapping an
 * accessibility id to an info. The cache allows storing of
 * <code>null</code> values. It also tracks accessibility events
 * and invalidates accordingly.
 *
 * @hide
 */
public class AccessibilityNodeInfoCache {

    private static final String LOG_TAG = AccessibilityNodeInfoCache.class.getSimpleName();

    private static final boolean ENABLED = true;

    private static final boolean DEBUG = false;

    private final Object mLock = new Object();

    private final LongSparseArray<AccessibilityNodeInfo> mCacheImpl;

    public AccessibilityNodeInfoCache() {
        if (ENABLED) {
            mCacheImpl = new LongSparseArray<AccessibilityNodeInfo>();
        } else {
            mCacheImpl = null;
        }
    }

    /**
     * The cache keeps track of {@link AccessibilityEvent}s and invalidates
     * cached nodes as appropriate.
     *
     * @param event An event.
     */
    public void onAccessibilityEvent(AccessibilityEvent event) {
        final int eventType = event.getEventType();
        switch (eventType) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
            case AccessibilityEvent.TYPE_VIEW_SCROLLED:
                clear();
                break;
            case AccessibilityEvent.TYPE_VIEW_FOCUSED:
            case AccessibilityEvent.TYPE_VIEW_SELECTED:
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
            case AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED:
                final long accessibilityNodeId = event.getSourceNodeId();
                remove(accessibilityNodeId);
                break;
        }
    }

    /**
     * Gets a cached {@link AccessibilityNodeInfo} given its accessibility node id.
     *
     * @param accessibilityNodeId The info accessibility node id.
     * @return The cached {@link AccessibilityNodeInfo} or null if such not found.
     */
    public AccessibilityNodeInfo get(long accessibilityNodeId) {
        if (ENABLED) {
            synchronized(mLock) {
                if (DEBUG) {
                    AccessibilityNodeInfo info = mCacheImpl.get(accessibilityNodeId);
                    Log.i(LOG_TAG, "Process: " + Process.myPid() +
                            " get(" + accessibilityNodeId + ") = " + info);
                    return info;
                } else {
                    return mCacheImpl.get(accessibilityNodeId);
                }
            }
        } else {
            return null;
        }
    }

    /**
     * Caches an {@link AccessibilityNodeInfo} given its accessibility node id.
     *
     * @param accessibilityNodeId The info accessibility node id.
     * @param info The {@link AccessibilityNodeInfo} to cache.
     */
    public void put(long accessibilityNodeId, AccessibilityNodeInfo info) {
        if (ENABLED) {
            synchronized(mLock) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "Process: " + Process.myPid()
                            + " put(" + accessibilityNodeId + ", " + info + ")");
                }
                mCacheImpl.put(accessibilityNodeId, info);
            }
        }
    }

    /**
     * Returns whether the cache contains an accessibility node id key.
     *
     * @param accessibilityNodeId The key for which to check.
     * @return True if the key is in the cache.
     */
    public boolean containsKey(long accessibilityNodeId) {
        if (ENABLED) {
            synchronized(mLock) {
                return (mCacheImpl.indexOfKey(accessibilityNodeId) >= 0);
            }
        } else {
            return false;
        }
    }

    /**
     * Removes a cached {@link AccessibilityNodeInfo}.
     *
     * @param accessibilityNodeId The info accessibility node id.
     */
    public void remove(long accessibilityNodeId) {
        if (ENABLED) {
            synchronized(mLock) {
                if (DEBUG) {
                    Log.i(LOG_TAG,  "Process: " + Process.myPid()
                            + " remove(" + accessibilityNodeId + ")");
                }
                mCacheImpl.remove(accessibilityNodeId);
            }
        }
    }

    /**
     * Clears the cache.
     */
    public void clear() {
        if (ENABLED) {
            synchronized(mLock) {
                if (DEBUG) {
                    Log.i(LOG_TAG,  "Process: " + Process.myPid() + "clear()");
                }
                mCacheImpl.clear();
            }
        }
    }
}
