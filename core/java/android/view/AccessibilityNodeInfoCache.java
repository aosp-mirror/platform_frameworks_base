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

package android.view;

import android.util.LongSparseArray;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Simple cache for AccessibilityNodeInfos. The cache is mapping an
 * accessibility id to an info. The cache allows storing of
 * <code>null</code> values. It also tracks accessibility events
 * and invalidates accordingly.
 *
 * @hide
 */
public class AccessibilityNodeInfoCache {

    private final boolean ENABLED = true;

    /**
     * @return A new <strong>not synchronized</strong> AccessibilityNodeInfoCache.
     */
    public static AccessibilityNodeInfoCache newAccessibilityNodeInfoCache() {
        return new AccessibilityNodeInfoCache();
    }

    /**
     * @return A new <strong>synchronized</strong> AccessibilityNodeInfoCache.
     */
    public static AccessibilityNodeInfoCache newSynchronizedAccessibilityNodeInfoCache() {
        return new AccessibilityNodeInfoCache() {
            private final Object mLock = new Object();

            @Override
            public void clear() {
                synchronized(mLock) {
                    super.clear();
                }
            }

            @Override
            public AccessibilityNodeInfo get(long accessibilityNodeId) {
                synchronized(mLock) {
                    return super.get(accessibilityNodeId);
                }
            }

            @Override
            public void put(long accessibilityNodeId, AccessibilityNodeInfo info) {
                synchronized(mLock) {
                   super.put(accessibilityNodeId, info);
                }
            }

            @Override
            public void remove(long accessibilityNodeId) {
                synchronized(mLock) {
                   super.remove(accessibilityNodeId);
                }
            }
        };
    }

    private final LongSparseArray<AccessibilityNodeInfo> mCacheImpl;

    private AccessibilityNodeInfoCache() {
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
            return mCacheImpl.get(accessibilityNodeId);
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
            mCacheImpl.put(accessibilityNodeId, info);
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
            return (mCacheImpl.indexOfKey(accessibilityNodeId) >= 0);
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
            mCacheImpl.remove(accessibilityNodeId);
        }
    }

    /**
     * Clears the cache.
     */
    public void clear() {
        if (ENABLED) {
            mCacheImpl.clear();
        }
    }
}
