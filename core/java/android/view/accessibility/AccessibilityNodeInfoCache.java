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

import android.os.Build;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.SparseLongArray;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;

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

    private static final boolean CHECK_INTEGRITY = true;

    private final Object mLock = new Object();

    private final LongSparseArray<AccessibilityNodeInfo> mCacheImpl;

    private int mWindowId;

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
        if (ENABLED) {
            final int eventType = event.getEventType();
            switch (eventType) {
                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: {
                    // New window so we clear the cache.
                    mWindowId = event.getWindowId();
                    clear();
                } break;
                case AccessibilityEvent.TYPE_VIEW_HOVER_ENTER:
                case AccessibilityEvent.TYPE_VIEW_HOVER_EXIT: {
                    final int windowId = event.getWindowId();
                    if (mWindowId != windowId) {
                        // New window so we clear the cache.
                        mWindowId = windowId;
                        clear();
                    }
                } break;
                case AccessibilityEvent.TYPE_VIEW_FOCUSED:
                case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED:
                case AccessibilityEvent.TYPE_VIEW_SELECTED:
                case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                case AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED: {
                    // Since we prefetch the descendants of a node we
                    // just remove the entire subtree since when the node
                    // is fetched we will gets its descendant anyway.
                    synchronized (mLock) {
                        final long sourceId = event.getSourceNodeId();
                        clearSubTreeLocked(sourceId);
                        if (eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                            clearSubtreeWithOldInputFocusLocked(sourceId);
                        }
                        if (eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
                            clearSubtreeWithOldAccessibilityFocusLocked(sourceId);
                        }
                    }
                } break;
                case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                case AccessibilityEvent.TYPE_VIEW_SCROLLED: {
                    synchronized (mLock) {
                        final long accessibilityNodeId = event.getSourceNodeId();
                        clearSubTreeLocked(accessibilityNodeId);
                    }
                } break;
            }
            if (Build.IS_DEBUGGABLE && CHECK_INTEGRITY) {
                checkIntegrity();
            }
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
                AccessibilityNodeInfo info = mCacheImpl.get(accessibilityNodeId);
                if (info != null) {
                    // Return a copy since the client calls to AccessibilityNodeInfo#recycle()
                    // will wipe the data of the cached info.
                    info = AccessibilityNodeInfo.obtain(info);
                }
                if (DEBUG) {
                    Log.i(LOG_TAG, "get(" + accessibilityNodeId + ") = " + info);
                }
                return info;
            }
        } else {
            return null;
        }
    }

    /**
     * Caches an {@link AccessibilityNodeInfo} given its accessibility node id.
     *
     * @param info The {@link AccessibilityNodeInfo} to cache.
     */
    public void add(AccessibilityNodeInfo info) {
        if (ENABLED) {
            synchronized(mLock) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "add(" + info + ")");
                }

                final long sourceId = info.getSourceNodeId();
                AccessibilityNodeInfo oldInfo = mCacheImpl.get(sourceId);
                if (oldInfo != null) {
                    // If the added node is in the cache we have to be careful if
                    // the new one represents a source state where some of the
                    // children have been removed to avoid having disconnected
                    // subtrees in the cache.
                    SparseLongArray oldChildrenIds = oldInfo.getChildNodeIds();
                    SparseLongArray newChildrenIds = info.getChildNodeIds();
                    final int oldChildCount = oldChildrenIds.size();
                    for (int i = 0; i < oldChildCount; i++) {
                        final long oldChildId = oldChildrenIds.valueAt(i);
                        if (newChildrenIds.indexOfValue(oldChildId) < 0) {
                            clearSubTreeLocked(oldChildId);
                        }
                    }

                    // Also be careful if the parent has changed since the new
                    // parent may be a predecessor of the old parent which will
                    // make the cached tree cyclic.
                    final long oldParentId = oldInfo.getParentNodeId();
                    if (info.getParentNodeId() != oldParentId) {
                        clearSubTreeLocked(oldParentId);
                    }
                }

                // Cache a copy since the client calls to AccessibilityNodeInfo#recycle()
                // will wipe the data of the cached info.
                AccessibilityNodeInfo clone = AccessibilityNodeInfo.obtain(info);
                mCacheImpl.put(sourceId, clone);
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
                    Log.i(LOG_TAG, "clear()");
                }
                // Recycle the nodes before clearing the cache.
                final int nodeCount = mCacheImpl.size();
                for (int i = 0; i < nodeCount; i++) {
                    AccessibilityNodeInfo info = mCacheImpl.valueAt(i);
                    info.recycle();
                }
                mCacheImpl.clear();
            }
        }
    }

    /**
     * Clears a subtree rooted at the node with the given id.
     *
     * @param rootNodeId The root id.
     */
    private void clearSubTreeLocked(long rootNodeId) {
        AccessibilityNodeInfo current = mCacheImpl.get(rootNodeId);
        if (current == null) {
            return;
        }
        mCacheImpl.remove(rootNodeId);
        SparseLongArray childNodeIds = current.getChildNodeIds();
        final int childCount = childNodeIds.size();
        for (int i = 0; i < childCount; i++) {
            final long childNodeId = childNodeIds.valueAt(i);
            clearSubTreeLocked(childNodeId);
        }
    }

    /**
     * We are enforcing the invariant for a single input focus.
     *
     * @param currentInputFocusId The current input focused node.
     */
    private void clearSubtreeWithOldInputFocusLocked(long currentInputFocusId) {
        final int cacheSize = mCacheImpl.size();
        for (int i = 0; i < cacheSize; i++) {
            AccessibilityNodeInfo info = mCacheImpl.valueAt(i);
            final long infoSourceId = info.getSourceNodeId();
            if (infoSourceId != currentInputFocusId && info.isFocused()) {
                clearSubTreeLocked(infoSourceId);
                return;
            }
        }
    }

    /**
     * We are enforcing the invariant for a single accessibility focus.
     *
     * @param currentAccessibilityFocusId The current input focused node.
     */
    private void clearSubtreeWithOldAccessibilityFocusLocked(long currentAccessibilityFocusId) {
        final int cacheSize = mCacheImpl.size();
        for (int i = 0; i < cacheSize; i++) {
            AccessibilityNodeInfo info = mCacheImpl.valueAt(i);
            final long infoSourceId = info.getSourceNodeId();
            if (infoSourceId != currentAccessibilityFocusId && info.isAccessibilityFocused()) {
                clearSubTreeLocked(infoSourceId);
                return;
            }
        }
    }

    /**
     * Check the integrity of the cache which is it does not have nodes
     * from more than one window, there are no duplicates, all nodes are
     * connected, there is a single input focused node, and there is a
     * single accessibility focused node.
     */
    private void checkIntegrity() {
        synchronized (mLock) {
            // Get the root.
            if (mCacheImpl.size() <= 0) {
                return;
            }

            // If the cache is a tree it does not matter from
            // which node we start to search for the root.
            AccessibilityNodeInfo root = mCacheImpl.valueAt(0);
            AccessibilityNodeInfo parent = root;
            while (parent != null) {
                root = parent;
                parent = mCacheImpl.get(parent.getParentNodeId());
            }

            // Traverse the tree and do some checks.
            final int windowId = root.getWindowId();
            AccessibilityNodeInfo accessFocus = null;
            AccessibilityNodeInfo inputFocus = null;
            HashSet<AccessibilityNodeInfo> seen = new HashSet<AccessibilityNodeInfo>();
            Queue<AccessibilityNodeInfo> fringe = new LinkedList<AccessibilityNodeInfo>();
            fringe.add(root);

            while (!fringe.isEmpty()) {
                AccessibilityNodeInfo current = fringe.poll();
                // Check for duplicates
                if (!seen.add(current)) {
                    Log.e(LOG_TAG, "Duplicate node: " + current);
                    return;
                }

                // Check for one accessibility focus.
                if (current.isAccessibilityFocused()) {
                    if (accessFocus != null) {
                        Log.e(LOG_TAG, "Duplicate accessibility focus:" + current);
                    } else {
                        accessFocus = current;
                    }
                }

                // Check for one input focus.
                if (current.isFocused()) {
                    if (inputFocus != null) {
                        Log.e(LOG_TAG, "Duplicate input focus: " + current);
                    } else {
                        inputFocus = current;
                    }
                }

                SparseLongArray childIds = current.getChildNodeIds();
                final int childCount = childIds.size();
                for (int i = 0; i < childCount; i++) {
                    final long childId = childIds.valueAt(i);
                    AccessibilityNodeInfo child = mCacheImpl.get(childId);
                    if (child != null) {
                        fringe.add(child);
                    }
                }
            }

            // Check for disconnected nodes or ones from another window.
            final int cacheSize = mCacheImpl.size();
            for (int i = 0; i < cacheSize; i++) {
                AccessibilityNodeInfo info = mCacheImpl.valueAt(i);
                if (!seen.contains(info)) {
                    if (info.getWindowId() == windowId) {
                        Log.e(LOG_TAG, "Disconneced node: ");
                    } else {
                        Log.e(LOG_TAG, "Node from: " + info.getWindowId() + " not from:"
                                + windowId + " " + info);
                    }
                }
            }
        }
    }
}
