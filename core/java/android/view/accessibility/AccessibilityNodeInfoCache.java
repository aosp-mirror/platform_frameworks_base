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

    private static final boolean CHECK_INTEGRITY_IF_DEBUGGABLE_BUILD = true;

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
                case AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END:
                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                case AccessibilityEvent.TYPE_VIEW_HOVER_ENTER:
                case AccessibilityEvent.TYPE_VIEW_HOVER_EXIT: {
                    // If the active window changes, clear the cache.
                    final int windowId = event.getWindowId();
                    if (mWindowId != windowId) {
                        mWindowId = windowId;
                        clear();
                    }
                } break;
                case AccessibilityEvent.TYPE_VIEW_FOCUSED:
                case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED:
                case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED:
                case AccessibilityEvent.TYPE_VIEW_SELECTED:
                case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                case AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED: {
                    refreshCachedNode(event.getSourceNodeId());
                } break;
                case AccessibilityEvent.TYPE_VIEW_SCROLLED: {
                    synchronized (mLock) {
                        clearSubTreeLocked(event.getSourceNodeId());
                    }
                } break;
                case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: {
                    synchronized (mLock) {
                        final long sourceId = event.getSourceNodeId();
                        if ((event.getContentChangeTypes()
                                & AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE) != 0) {
                            clearSubTreeLocked(sourceId);
                        } else {
                            refreshCachedNode(sourceId);
                        }
                    }
                } break;
            }
            if (CHECK_INTEGRITY_IF_DEBUGGABLE_BUILD && Build.IS_DEBUGGABLE) {
                checkIntegrity();
            }
        }
    }

    private void refreshCachedNode(long sourceId) {
        if (DEBUG) {
            Log.i(LOG_TAG, "Refreshing cached node.");
        }
        synchronized (mLock) {
            AccessibilityNodeInfo cachedInfo = mCacheImpl.get(sourceId);
            // If the source is not in the cache - nothing to do.
            if (cachedInfo == null) {
                return;
            }
            // The node changed so we will just refresh it right now.
            if (cachedInfo.refresh(true)) {
                return;
            }
            // Weird, we could not refresh. Just evict the entire sub-tree.
            clearSubTreeLocked(sourceId);
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
        if (DEBUG) {
            Log.i(LOG_TAG, "Clearing cached subtree.");
        }
        clearSubTreeRecursiveLocked(rootNodeId);
    }

    private void clearSubTreeRecursiveLocked(long rootNodeId) {
        AccessibilityNodeInfo current = mCacheImpl.get(rootNodeId);
        if (current == null) {
            return;
        }
        mCacheImpl.remove(rootNodeId);
        SparseLongArray childNodeIds = current.getChildNodeIds();
        final int childCount = childNodeIds.size();
        for (int i = 0; i < childCount; i++) {
            final long childNodeId = childNodeIds.valueAt(i);
            clearSubTreeRecursiveLocked(childNodeId);
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
            for (int i = 0; i < mCacheImpl.size(); i++) {
                AccessibilityNodeInfo info = mCacheImpl.valueAt(i);
                if (!seen.contains(info)) {
                    if (info.getWindowId() == windowId) {
                        Log.e(LOG_TAG, "Disconneced node: " + info);
                    } else {
                        Log.e(LOG_TAG, "Node from: " + info.getWindowId() + " not from:"
                                + windowId + " " + info);
                    }
                }
            }
        }
    }
}
