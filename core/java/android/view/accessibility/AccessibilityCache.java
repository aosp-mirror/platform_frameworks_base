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
import android.util.ArraySet;
import android.util.Log;
import android.util.LongArray;
import android.util.LongSparseArray;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Cache for AccessibilityWindowInfos and AccessibilityNodeInfos.
 * It is updated when windows change or nodes change.
 */
final class AccessibilityCache {

    private static final String LOG_TAG = "AccessibilityCache";

    private static final boolean DEBUG = false;

    private static final boolean CHECK_INTEGRITY = "eng".equals(Build.TYPE);

    private final Object mLock = new Object();

    private final SparseArray<AccessibilityWindowInfo> mWindowCache =
            new SparseArray<>();

    private final SparseArray<LongSparseArray<AccessibilityNodeInfo>> mNodeCache =
            new SparseArray<>();

    private final SparseArray<AccessibilityWindowInfo> mTempWindowArray =
            new SparseArray<>();

    public void addWindow(AccessibilityWindowInfo window) {
        synchronized (mLock) {
            if (DEBUG) {
                Log.i(LOG_TAG, "Caching window: " + window.getId());
            }
            final int windowId = window.getId();
            AccessibilityWindowInfo oldWindow = mWindowCache.get(windowId);
            if (oldWindow != null) {
                oldWindow.recycle();
            }
            mWindowCache.put(windowId, AccessibilityWindowInfo.obtain(window));
        }
    }

    /**
     * Notifies the cache that the something in the UI changed. As a result
     * the cache will either refresh some nodes or evict some nodes.
     *
     * @param event An event.
     */
    public void onAccessibilityEvent(AccessibilityEvent event) {
        synchronized (mLock) {
            final int eventType = event.getEventType();
            switch (eventType) {
                case AccessibilityEvent.TYPE_VIEW_FOCUSED:
                case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED:
                case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED:
                case AccessibilityEvent.TYPE_VIEW_SELECTED:
                case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                case AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED: {
                    refreshCachedNodeLocked(event.getWindowId(), event.getSourceNodeId());
                } break;

                case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: {
                    synchronized (mLock) {
                        final int windowId = event.getWindowId();
                        final long sourceId = event.getSourceNodeId();
                        if ((event.getContentChangeTypes()
                                & AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE) != 0) {
                            clearSubTreeLocked(windowId, sourceId);
                        } else {
                            refreshCachedNodeLocked(windowId, sourceId);
                        }
                    }
                } break;

                case AccessibilityEvent.TYPE_VIEW_SCROLLED: {
                    clearSubTreeLocked(event.getWindowId(), event.getSourceNodeId());
                } break;

                case AccessibilityEvent.TYPE_WINDOWS_CHANGED:
                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: {
                    clear();
                } break;
            }
        }

        if (CHECK_INTEGRITY) {
            checkIntegrity();
        }
    }

    private void refreshCachedNodeLocked(int windowId, long sourceId) {
        if (DEBUG) {
            Log.i(LOG_TAG, "Refreshing cached node.");
        }

        LongSparseArray<AccessibilityNodeInfo> nodes = mNodeCache.get(windowId);
        if (nodes == null) {
            return;
        }
        AccessibilityNodeInfo cachedInfo = nodes.get(sourceId);
        // If the source is not in the cache - nothing to do.
        if (cachedInfo == null) {
            return;
        }
        // The node changed so we will just refresh it right now.
        if (cachedInfo.refresh(true)) {
            return;
        }
        // Weird, we could not refresh. Just evict the entire sub-tree.
        clearSubTreeLocked(windowId, sourceId);
    }

    /**
     * Gets a cached {@link AccessibilityNodeInfo} given the id of the hosting
     * window and the accessibility id of the node.
     *
     * @param windowId The id of the window hosting the node.
     * @param accessibilityNodeId The info accessibility node id.
     * @return The cached {@link AccessibilityNodeInfo} or null if such not found.
     */
    public AccessibilityNodeInfo getNode(int windowId, long accessibilityNodeId) {
        synchronized(mLock) {
            LongSparseArray<AccessibilityNodeInfo> nodes = mNodeCache.get(windowId);
            if (nodes == null) {
                return null;
            }
            AccessibilityNodeInfo info = nodes.get(accessibilityNodeId);
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
    }

    public List<AccessibilityWindowInfo> getWindows() {
        synchronized (mLock) {
            final int windowCount = mWindowCache.size();
            if (windowCount > 0) {
                // Careful to return the windows in a decreasing layer order.
                SparseArray<AccessibilityWindowInfo> sortedWindows = mTempWindowArray;
                sortedWindows.clear();

                for (int i = 0; i < windowCount; i++) {
                    AccessibilityWindowInfo window = mWindowCache.valueAt(i);
                    sortedWindows.put(window.getLayer(), window);
                }

                List<AccessibilityWindowInfo> windows = new ArrayList<>(windowCount);
                for (int i = windowCount - 1; i >= 0; i--) {
                    AccessibilityWindowInfo window = sortedWindows.valueAt(i);
                    windows.add(AccessibilityWindowInfo.obtain(window));
                    sortedWindows.removeAt(i);
                }

                return windows;
            }
            return null;
        }
    }

    public AccessibilityWindowInfo getWindow(int windowId) {
        synchronized (mLock) {
            AccessibilityWindowInfo window = mWindowCache.get(windowId);
            if (window != null) {
               return AccessibilityWindowInfo.obtain(window);
            }
            return null;
        }
    }

    /**
     * Caches an {@link AccessibilityNodeInfo}.
     *
     * @param info The node to cache.
     */
    public void add(AccessibilityNodeInfo info) {
        synchronized(mLock) {
            if (DEBUG) {
                Log.i(LOG_TAG, "add(" + info + ")");
            }

            final int windowId = info.getWindowId();
            LongSparseArray<AccessibilityNodeInfo> nodes = mNodeCache.get(windowId);
            if (nodes == null) {
                nodes = new LongSparseArray<>();
                mNodeCache.put(windowId, nodes);
            }

            final long sourceId = info.getSourceNodeId();
            AccessibilityNodeInfo oldInfo = nodes.get(sourceId);
            if (oldInfo != null) {
                // If the added node is in the cache we have to be careful if
                // the new one represents a source state where some of the
                // children have been removed to remove the descendants that
                // are no longer present.
                final LongArray newChildrenIds = info.getChildNodeIds();

                final int oldChildCount = oldInfo.getChildCount();
                for (int i = 0; i < oldChildCount; i++) {
                    final long oldChildId = oldInfo.getChildId(i);
                    // If the child is no longer present, remove the sub-tree.
                    if (newChildrenIds == null || newChildrenIds.indexOf(oldChildId) < 0) {
                        clearSubTreeLocked(windowId, oldChildId);
                    }
                }

                // Also be careful if the parent has changed since the new
                // parent may be a predecessor of the old parent which will
                // add cyclse to the cache.
                final long oldParentId = oldInfo.getParentNodeId();
                if (info.getParentNodeId() != oldParentId) {
                    clearSubTreeLocked(windowId, oldParentId);
                }
           }

            // Cache a copy since the client calls to AccessibilityNodeInfo#recycle()
            // will wipe the data of the cached info.
            AccessibilityNodeInfo clone = AccessibilityNodeInfo.obtain(info);
            nodes.put(sourceId, clone);
        }
    }

    /**
     * Clears the cache.
     */
    public void clear() {
        synchronized(mLock) {
            if (DEBUG) {
                Log.i(LOG_TAG, "clear()");
            }
            final int windowCount = mWindowCache.size();
            for (int i = windowCount - 1; i >= 0; i--) {
                AccessibilityWindowInfo window = mWindowCache.valueAt(i);
                window.recycle();
                mWindowCache.removeAt(i);
            }
            final int nodesForWindowCount = mNodeCache.size();
            for (int i = 0; i < nodesForWindowCount; i++) {
                final int windowId = mNodeCache.keyAt(i);
                clearNodesForWindowLocked(windowId);
            }
        }
    }

    private void clearNodesForWindowLocked(int windowId) {
        if (DEBUG) {
            Log.i(LOG_TAG, "clearNodesForWindowLocked(" + windowId + ")");
        }
        LongSparseArray<AccessibilityNodeInfo> nodes = mNodeCache.get(windowId);
        if (nodes == null) {
            return;
        }
        // Recycle the nodes before clearing the cache.
        final int nodeCount = nodes.size();
        for (int i = nodeCount - 1; i >= 0; i--) {
            AccessibilityNodeInfo info = nodes.valueAt(i);
            nodes.removeAt(i);
            info.recycle();
        }
        mNodeCache.remove(windowId);
    }

    /**
     * Clears a subtree rooted at the node with the given id that is
     * hosted in a given window.
     *
     * @param windowId The id of the hosting window.
     * @param rootNodeId The root id.
     */
    private void clearSubTreeLocked(int windowId, long rootNodeId) {
        if (DEBUG) {
            Log.i(LOG_TAG, "Clearing cached subtree.");
        }
        LongSparseArray<AccessibilityNodeInfo> nodes = mNodeCache.get(windowId);
        if (nodes != null) {
            clearSubTreeRecursiveLocked(nodes, rootNodeId);
        }
    }

    /**
     * Clears a subtree given a pointer to the root id and the nodes
     * in the hosting window.
     *
     * @param nodes The nodes in the hosting window.
     * @param rootNodeId The id of the root to evict.
     */
    private void clearSubTreeRecursiveLocked(LongSparseArray<AccessibilityNodeInfo> nodes,
            long rootNodeId) {
        AccessibilityNodeInfo current = nodes.get(rootNodeId);
        if (current == null) {
            return;
        }
        nodes.remove(rootNodeId);
        final int childCount = current.getChildCount();
        for (int i = 0; i < childCount; i++) {
            final long childNodeId = current.getChildId(i);
            clearSubTreeRecursiveLocked(nodes, childNodeId);
        }
    }

    /**
     * Check the integrity of the cache which is nodes from different windows
     * are not mixed, there is a single active window, there is a single focused
     * window, for every window there are no duplicates nodes, all nodes for a
     * window are connected, for every window there is a single input focused
     * node, and for every window there is a single accessibility focused node.
     */
    public void checkIntegrity() {
        synchronized (mLock) {
            // Get the root.
            if (mWindowCache.size() <= 0 && mNodeCache.size() == 0) {
                return;
            }

            AccessibilityWindowInfo focusedWindow = null;
            AccessibilityWindowInfo activeWindow = null;

            final int windowCount = mWindowCache.size();
            for (int i = 0; i < windowCount; i++) {
                AccessibilityWindowInfo window = mWindowCache.valueAt(i);

                // Check for one active window.
                if (window.isActive()) {
                    if (activeWindow != null) {
                        Log.e(LOG_TAG, "Duplicate active window:" + window);
                    } else {
                        activeWindow = window;
                    }
                }

                // Check for one focused window.
                if (window.isFocused()) {
                    if (focusedWindow != null) {
                        Log.e(LOG_TAG, "Duplicate focused window:" + window);
                    } else {
                        focusedWindow = window;
                    }
                }
            }

            // Traverse the tree and do some checks.
            AccessibilityNodeInfo accessFocus = null;
            AccessibilityNodeInfo inputFocus = null;

            final int nodesForWindowCount = mNodeCache.size();
            for (int i = 0; i < nodesForWindowCount; i++) {
                LongSparseArray<AccessibilityNodeInfo> nodes = mNodeCache.valueAt(i);
                if (nodes.size() <= 0) {
                    continue;
                }

                ArraySet<AccessibilityNodeInfo> seen = new ArraySet<>();
                final int windowId = mNodeCache.keyAt(i);

                final int nodeCount = nodes.size();
                for (int j = 0; j < nodeCount; j++) {
                    AccessibilityNodeInfo node = nodes.valueAt(j);

                    // Check for duplicates
                    if (!seen.add(node)) {
                        Log.e(LOG_TAG, "Duplicate node: " + node
                                + " in window:" + windowId);
                        // Stop now as we potentially found a loop.
                        continue;
                    }

                    // Check for one accessibility focus.
                    if (node.isAccessibilityFocused()) {
                        if (accessFocus != null) {
                            Log.e(LOG_TAG, "Duplicate accessibility focus:" + node
                                    + " in window:" + windowId);
                        } else {
                            accessFocus = node;
                        }
                    }

                    // Check for one input focus.
                    if (node.isFocused()) {
                        if (inputFocus != null) {
                            Log.e(LOG_TAG, "Duplicate input focus: " + node
                                    + " in window:" + windowId);
                        } else {
                            inputFocus = node;
                        }
                    }

                    // The node should be a child of its parent if we have the parent.
                    AccessibilityNodeInfo nodeParent = nodes.get(node.getParentNodeId());
                    if (nodeParent != null) {
                        boolean childOfItsParent = false;
                        final int childCount = nodeParent.getChildCount();
                        for (int k = 0; k < childCount; k++) {
                            AccessibilityNodeInfo child = nodes.get(nodeParent.getChildId(k));
                            if (child == node) {
                                childOfItsParent = true;
                                break;
                            }
                        }
                        if (!childOfItsParent) {
                            Log.e(LOG_TAG, "Invalid parent-child relation between parent: "
                                    + nodeParent + " and child: " + node);
                        }
                    }

                    // The node should be the parent of its child if we have the child.
                    final int childCount = node.getChildCount();
                    for (int k = 0; k < childCount; k++) {
                        AccessibilityNodeInfo child = nodes.get(node.getChildId(k));
                        if (child != null) {
                            AccessibilityNodeInfo parent = nodes.get(child.getParentNodeId());
                            if (parent != node) {
                                Log.e(LOG_TAG, "Invalid child-parent relation between child: "
                                        + node + " and parent: " + nodeParent);
                            }
                        }
                    }
                }
            }
        }
    }
}
