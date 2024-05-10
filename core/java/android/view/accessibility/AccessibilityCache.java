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


import static android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY;

import android.os.Build;
import android.os.SystemClock;
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
 * @hide
 */
public class AccessibilityCache {

    private static final String LOG_TAG = "AccessibilityCache";

    private static final boolean DEBUG = Log.isLoggable(LOG_TAG, Log.DEBUG) && Build.IS_DEBUGGABLE;

    private static final boolean VERBOSE =
            Log.isLoggable(LOG_TAG, Log.VERBOSE) && Build.IS_DEBUGGABLE;

    private static final boolean CHECK_INTEGRITY = Build.IS_ENG;

    private boolean mEnabled = true;

    /**
     * {@link AccessibilityEvent} types that are critical for the cache to stay up to date
     *
     * When adding new event types in {@link #onAccessibilityEvent}, please add it here also, to
     * make sure that the events are delivered to cache regardless of
     * {@link android.accessibilityservice.AccessibilityServiceInfo#eventTypes}
     */
    public static final int CACHE_CRITICAL_EVENTS_MASK =
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
                    | AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED
                    | AccessibilityEvent.TYPE_VIEW_FOCUSED
                    | AccessibilityEvent.TYPE_VIEW_SELECTED
                    | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_CLICKED
                    | AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_SCROLLED
                    | AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;

    private final Object mLock = new Object();

    private final AccessibilityNodeRefresher mAccessibilityNodeRefresher;

    private OnNodeAddedListener mOnNodeAddedListener;

    private long mAccessibilityFocus = AccessibilityNodeInfo.UNDEFINED_ITEM_ID;
    private long mInputFocus = AccessibilityNodeInfo.UNDEFINED_ITEM_ID;
    /**
     * The event time of the {@link AccessibilityEvent} which presents the populated windows cache
     * before it is stale.
     */
    private long mValidWindowCacheTimeStamp = 0;

    private int mAccessibilityFocusedWindow = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
    private int mInputFocusWindow = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;

    private boolean mIsAllWindowsCached;

    // The SparseArray of all {@link AccessibilityWindowInfo}s on all displays.
    // The key of outer SparseArray is display ID and the key of inner SparseArray is window ID.
    private final SparseArray<SparseArray<AccessibilityWindowInfo>> mWindowCacheByDisplay =
            new SparseArray<>();

    private final SparseArray<LongSparseArray<AccessibilityNodeInfo>> mNodeCache =
            new SparseArray<>();

    private final SparseArray<AccessibilityWindowInfo> mTempWindowArray =
            new SparseArray<>();

    public AccessibilityCache(AccessibilityNodeRefresher nodeRefresher) {
        mAccessibilityNodeRefresher = nodeRefresher;
    }

    /** Returns if the cache is enabled. */
    public boolean isEnabled() {
        synchronized (mLock) {
            return mEnabled;
        }
    }

    /** Sets enabled status. */
    public void setEnabled(boolean enabled) {
        synchronized (mLock) {
            mEnabled = enabled;
            clear();
        }
    }

    /**
     * Sets all {@link AccessibilityWindowInfo}s of all displays into the cache.
     * The key of SparseArray is display ID.
     *
     * @param windowsOnAllDisplays The accessibility windows of all displays.
     * @param populationTimeStamp The timestamp from {@link SystemClock#uptimeMillis()} when the
     *                            client requests the data.
     */
    public void setWindowsOnAllDisplays(
            SparseArray<List<AccessibilityWindowInfo>> windowsOnAllDisplays,
            long populationTimeStamp) {
        synchronized (mLock) {
            if (!mEnabled) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "Cache is disabled");
                }
                return;
            }
            if (DEBUG) {
                Log.i(LOG_TAG, "Set windows");
            }
            if (mValidWindowCacheTimeStamp > populationTimeStamp) {
                // Discard the windows because it might be stale.
                return;
            }
            clearWindowCacheLocked();
            if (windowsOnAllDisplays == null) {
                return;
            }

            final int displayCounts = windowsOnAllDisplays.size();
            for (int i = 0; i < displayCounts; i++) {
                final List<AccessibilityWindowInfo> windowsOfDisplay =
                        windowsOnAllDisplays.valueAt(i);

                if (windowsOfDisplay == null) {
                    continue;
                }

                final int displayId = windowsOnAllDisplays.keyAt(i);
                final int windowCount = windowsOfDisplay.size();
                for (int j = 0; j < windowCount; j++) {
                    addWindowByDisplayLocked(displayId, windowsOfDisplay.get(j));
                }
            }
            mIsAllWindowsCached = true;
        }
    }

    /**
     * Sets an {@link AccessibilityWindowInfo} into the cache.
     *
     * @param window The accessibility window.
     */
    public void addWindow(AccessibilityWindowInfo window) {
        synchronized (mLock) {
            if (!mEnabled) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "Cache is disabled");
                }
                return;
            }
            if (DEBUG) {
                Log.i(LOG_TAG, "Caching window: " + window.getId() + " at display Id [ "
                        + window.getDisplayId() + " ]");
            }
            addWindowByDisplayLocked(window.getDisplayId(), window);
        }
    }

    private void addWindowByDisplayLocked(int displayId, AccessibilityWindowInfo window) {
        SparseArray<AccessibilityWindowInfo> windows = mWindowCacheByDisplay.get(displayId);
        if (windows == null) {
            windows = new SparseArray<>();
            mWindowCacheByDisplay.put(displayId, windows);
        }
        final int windowId = window.getId();
        windows.put(windowId, new AccessibilityWindowInfo(window));
    }
    /**
     * Notifies the cache that the something in the UI changed. As a result
     * the cache will either refresh some nodes or evict some nodes.
     *
     * Note: any event that ends up affecting the cache should also be present in
     * {@link #CACHE_CRITICAL_EVENTS_MASK}
     *
     * @param event An event.
     */
    public void onAccessibilityEvent(AccessibilityEvent event) {
        AccessibilityNodeInfo nodeToRefresh = null;
        synchronized (mLock) {
            if (!mEnabled) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "Cache is disabled");
                }
                return;
            }
            if (DEBUG) {
                Log.i(LOG_TAG, "onAccessibilityEvent(" + event + ")");
            }
            final int eventType = event.getEventType();
            switch (eventType) {
                case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED: {
                    if (mAccessibilityFocus != AccessibilityNodeInfo.UNDEFINED_ITEM_ID) {
                        removeCachedNodeLocked(mAccessibilityFocusedWindow, mAccessibilityFocus);
                    }
                    mAccessibilityFocus = event.getSourceNodeId();
                    mAccessibilityFocusedWindow = event.getWindowId();
                    nodeToRefresh = removeCachedNodeLocked(mAccessibilityFocusedWindow,
                            mAccessibilityFocus);
                } break;

                case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED: {
                    if (mAccessibilityFocus == event.getSourceNodeId()
                            && mAccessibilityFocusedWindow == event.getWindowId()) {
                        nodeToRefresh = removeCachedNodeLocked(mAccessibilityFocusedWindow,
                                mAccessibilityFocus);
                        mAccessibilityFocus = AccessibilityNodeInfo.UNDEFINED_ITEM_ID;
                        mAccessibilityFocusedWindow = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
                    }
                } break;

                case AccessibilityEvent.TYPE_VIEW_FOCUSED: {
                    if (mInputFocus != AccessibilityNodeInfo.UNDEFINED_ITEM_ID) {
                        removeCachedNodeLocked(event.getWindowId(), mInputFocus);
                    }
                    mInputFocus = event.getSourceNodeId();
                    mInputFocusWindow = event.getWindowId();
                    nodeToRefresh = removeCachedNodeLocked(event.getWindowId(), mInputFocus);
                } break;

                case AccessibilityEvent.TYPE_VIEW_SELECTED:
                case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED:
                case AccessibilityEvent.TYPE_VIEW_CLICKED:
                case AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED: {
                    nodeToRefresh = removeCachedNodeLocked(event.getWindowId(),
                            event.getSourceNodeId());
                } break;

                case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: {
                    synchronized (mLock) {
                        final int windowId = event.getWindowId();
                        final long sourceId = event.getSourceNodeId();
                        if ((event.getContentChangeTypes()
                                & AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE) != 0) {
                            clearSubTreeLocked(windowId, sourceId);
                        } else {
                            nodeToRefresh = removeCachedNodeLocked(windowId, sourceId);
                        }
                    }
                } break;

                case AccessibilityEvent.TYPE_VIEW_SCROLLED: {
                    clearSubTreeLocked(event.getWindowId(), event.getSourceNodeId());
                } break;

                case AccessibilityEvent.TYPE_WINDOWS_CHANGED:
                    mValidWindowCacheTimeStamp = event.getEventTime();
                    if (event.getWindowChanges()
                            == AccessibilityEvent.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED) {
                        // Don't need to clear all cache. Unless the changes are related to
                        // content, we won't clear all cache here with clear().
                        clearWindowCacheLocked();
                        break;
                    }
                case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED: {
                    mValidWindowCacheTimeStamp = event.getEventTime();
                    clear();
                } break;
            }
        }

        if (nodeToRefresh != null) {
            if (DEBUG) {
                Log.i(LOG_TAG, "Refreshing and re-adding cached node.");
            }
            if (mAccessibilityNodeRefresher.refreshNode(nodeToRefresh, true)) {
                add(nodeToRefresh);
            }
        }
        if (CHECK_INTEGRITY) {
            checkIntegrity();
        }
    }

    private AccessibilityNodeInfo removeCachedNodeLocked(int windowId, long sourceId) {
        if (DEBUG) {
            Log.i(LOG_TAG, "Removing cached node.");
        }
        LongSparseArray<AccessibilityNodeInfo> nodes = mNodeCache.get(windowId);
        if (nodes == null) {
            return null;
        }
        AccessibilityNodeInfo cachedInfo = nodes.get(sourceId);
        // If the source is not in the cache - nothing to do.
        if (cachedInfo == null) {
            return null;
        }
        nodes.remove(sourceId);
        return cachedInfo;
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
            if (!mEnabled) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "Cache is disabled");
                }
                return null;
            }
            LongSparseArray<AccessibilityNodeInfo> nodes = mNodeCache.get(windowId);
            if (nodes == null) {
                return null;
            }
            AccessibilityNodeInfo info = nodes.get(accessibilityNodeId);
            if (info != null) {
                // Return a copy since the client calls to AccessibilityNodeInfo#recycle()
                // will wipe the data of the cached info.
                info = new AccessibilityNodeInfo(info);
            }
            if (VERBOSE) {
                Log.i(LOG_TAG, "get(0x" + Long.toHexString(accessibilityNodeId) + ") = " + info);
            }
            return info;
        }
    }

    /** Returns {@code true} if {@code info} is in the cache. */
    public boolean isNodeInCache(AccessibilityNodeInfo info) {
        if (info == null) {
            return false;
        }
        int windowId = info.getWindowId();
        long accessibilityNodeId = info.getSourceNodeId();
        synchronized (mLock) {
            if (!mEnabled) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "Cache is disabled");
                }
                return false;
            }
            LongSparseArray<AccessibilityNodeInfo> nodes = mNodeCache.get(windowId);
            if (nodes == null) {
                return false;
            }
            return nodes.get(accessibilityNodeId) != null;
        }
    }

    /**
     * Gets all {@link AccessibilityWindowInfo}s of all displays from the cache.
     *
     * @return All cached {@link AccessibilityWindowInfo}s of all displays
     *         or null if such not found. The key of SparseArray is display ID.
     */
    public SparseArray<List<AccessibilityWindowInfo>> getWindowsOnAllDisplays() {
        synchronized (mLock) {
            if (!mEnabled) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "Cache is disabled");
                }
                return null;
            }
            if (!mIsAllWindowsCached) {
                return null;
            }
            final SparseArray<List<AccessibilityWindowInfo>> returnWindows = new SparseArray<>();
            final int displayCounts = mWindowCacheByDisplay.size();

            if (displayCounts > 0) {
                for (int i = 0; i < displayCounts; i++) {
                    final int displayId = mWindowCacheByDisplay.keyAt(i);
                    final SparseArray<AccessibilityWindowInfo> windowsOfDisplay =
                            mWindowCacheByDisplay.valueAt(i);

                    if (windowsOfDisplay == null) {
                        continue;
                    }

                    final int windowCount = windowsOfDisplay.size();
                    if (windowCount > 0) {
                        // Careful to return the windows in a decreasing layer order.
                        SparseArray<AccessibilityWindowInfo> sortedWindows = mTempWindowArray;
                        sortedWindows.clear();

                        for (int j = 0; j < windowCount; j++) {
                            AccessibilityWindowInfo window = windowsOfDisplay.valueAt(j);
                            sortedWindows.put(window.getLayer(), window);
                        }

                        // It's possible in transient conditions for two windows to share the same
                        // layer, which results in sortedWindows being smaller than
                        // mWindowCacheByDisplay
                        final int sortedWindowCount = sortedWindows.size();
                        List<AccessibilityWindowInfo> windows =
                                new ArrayList<>(sortedWindowCount);
                        for (int j = sortedWindowCount - 1; j >= 0; j--) {
                            AccessibilityWindowInfo window = sortedWindows.valueAt(j);
                            windows.add(new AccessibilityWindowInfo(window));
                            sortedWindows.removeAt(j);
                        }
                        returnWindows.put(displayId, windows);
                    }
                }
                return returnWindows;
            }
            return null;
        }
    }

    /**
     * Gets an {@link AccessibilityWindowInfo} by windowId.
     *
     * @param windowId The id of the window.
     *
     * @return The {@link AccessibilityWindowInfo} or null if such not found.
     */
    public AccessibilityWindowInfo getWindow(int windowId) {
        synchronized (mLock) {
            if (!mEnabled) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "Cache is disabled");
                }
                return null;
            }
            final int displayCounts = mWindowCacheByDisplay.size();
            for (int i = 0; i < displayCounts; i++) {
                final SparseArray<AccessibilityWindowInfo> windowsOfDisplay =
                        mWindowCacheByDisplay.valueAt(i);
                if (windowsOfDisplay == null) {
                    continue;
                }

                AccessibilityWindowInfo window = windowsOfDisplay.get(windowId);
                if (window != null) {
                    return new AccessibilityWindowInfo(window);
                }
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
            if (!mEnabled) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "Cache is disabled");
                }
                return;
            }
            if (VERBOSE) {
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
                    if (nodes.get(sourceId) == null) {
                        // We've removed (and thus recycled) this node because it was its own
                        // ancestor (the app gave us bad data), we can't continue using it.
                        // Clear the cache for this window and give up on adding the node.
                        clearNodesForWindowLocked(windowId);
                        return;
                    }
                }

                // Also be careful if the parent has changed since the new
                // parent may be a predecessor of the old parent which will
                // add cycles to the cache.
                final long oldParentId = oldInfo.getParentNodeId();
                if (info.getParentNodeId() != oldParentId) {
                    clearSubTreeLocked(windowId, oldParentId);
                }
           }

            // Cache a copy since the client calls to AccessibilityNodeInfo#recycle()
            // will wipe the data of the cached info.
            AccessibilityNodeInfo clone = new AccessibilityNodeInfo(info);
            nodes.put(sourceId, clone);
            if (clone.isAccessibilityFocused()) {
                if (mAccessibilityFocus != AccessibilityNodeInfo.UNDEFINED_ITEM_ID
                        && mAccessibilityFocus != sourceId) {
                    removeCachedNodeLocked(windowId, mAccessibilityFocus);
                }
                mAccessibilityFocus = sourceId;
                mAccessibilityFocusedWindow = windowId;
            } else if (mAccessibilityFocus == sourceId) {
                mAccessibilityFocus = AccessibilityNodeInfo.UNDEFINED_ITEM_ID;
                mAccessibilityFocusedWindow = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
            }
            if (clone.isFocused()) {
                mInputFocus = sourceId;
                mInputFocusWindow = windowId;
            }

            if (mOnNodeAddedListener != null) {
                mOnNodeAddedListener.onNodeAdded(clone);
            }
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
            clearWindowCacheLocked();
            final int nodesForWindowCount = mNodeCache.size();
            for (int i = nodesForWindowCount - 1; i >= 0; i--) {
                final int windowId = mNodeCache.keyAt(i);
                clearNodesForWindowLocked(windowId);
            }

            mAccessibilityFocus = AccessibilityNodeInfo.UNDEFINED_ITEM_ID;
            mInputFocus = AccessibilityNodeInfo.UNDEFINED_ITEM_ID;

            mAccessibilityFocusedWindow = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
            mInputFocusWindow = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
        }
    }

    private void clearWindowCacheLocked() {
        if (DEBUG) {
            Log.i(LOG_TAG, "clearWindowCacheLocked");
        }
        final int displayCounts = mWindowCacheByDisplay.size();

        if (displayCounts > 0) {
            for (int i = displayCounts - 1; i >= 0; i--) {
                final int displayId = mWindowCacheByDisplay.keyAt(i);
                final SparseArray<AccessibilityWindowInfo> windows =
                        mWindowCacheByDisplay.get(displayId);
                if (windows != null) {
                    windows.clear();
                }
                mWindowCacheByDisplay.remove(displayId);
            }
        }
        mIsAllWindowsCached = false;
    }

    /**
     * Gets a cached {@link AccessibilityNodeInfo} with focus according to focus type.
     *
     * Note: {@link android.view.accessibility.AccessibilityWindowInfo#ACTIVE_WINDOW_ID} will return
     * null.
     *
     * @param focusType The focus type.
     * @param windowId A unique window id.
     * @param initialNodeId A unique view id or virtual descendant id from where to start the
     *                      search.
     * @return The cached {@link AccessibilityNodeInfo} if it has a11y focus or null if such not
     * found.
     */
    public AccessibilityNodeInfo getFocus(int focusType, long initialNodeId, int windowId) {
        synchronized (mLock) {
            if (!mEnabled) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "Cache is disabled");
                }
                return null;
            }
            int currentFocusWindowId;
            long currentFocusId;
            if (focusType == FOCUS_ACCESSIBILITY) {
                currentFocusWindowId = mAccessibilityFocusedWindow;
                currentFocusId = mAccessibilityFocus;
            } else {
                currentFocusWindowId = mInputFocusWindow;
                currentFocusId = mInputFocus;
            }

            if (currentFocusWindowId == AccessibilityWindowInfo.UNDEFINED_WINDOW_ID) {
                return null;
            }

            if (windowId != AccessibilityWindowInfo.ANY_WINDOW_ID
                    && windowId != currentFocusWindowId) {
                return null;
            }

            LongSparseArray<AccessibilityNodeInfo> nodes =
                    mNodeCache.get(currentFocusWindowId);
            if (nodes == null) {
                return null;
            }

            final AccessibilityNodeInfo currentFocusedNode = nodes.get(currentFocusId);
            if (currentFocusedNode == null) {
                return null;
            }

            if (initialNodeId == currentFocusId || (isCachedNodeOrDescendantLocked(
                    currentFocusedNode.getParentNodeId(), initialNodeId, nodes))) {
                if (VERBOSE) {
                    Log.i(LOG_TAG, "getFocus(0x" + Long.toHexString(currentFocusId) + ") = "
                            + currentFocusedNode + " with type: "
                            + (focusType == FOCUS_ACCESSIBILITY
                            ? "FOCUS_ACCESSIBILITY"
                            : "FOCUS_INPUT"));
                }
                // Return a copy since the client calls to AccessibilityNodeInfo#recycle()
                // will wipe the data of the cached info.
                return new AccessibilityNodeInfo(currentFocusedNode);
            }

            if (VERBOSE) {
                Log.i(LOG_TAG, "getFocus is null with type: "
                        + (focusType == FOCUS_ACCESSIBILITY
                        ? "FOCUS_ACCESSIBILITY"
                        : "FOCUS_INPUT"));
            }
            return null;
        }
    }

    private boolean isCachedNodeOrDescendantLocked(long nodeId, long ancestorId,
            LongSparseArray<AccessibilityNodeInfo> nodes) {
        if (ancestorId == nodeId) {
            return true;
        }
        AccessibilityNodeInfo node = nodes.get(nodeId);
        if (node == null) {
            return false;
        }
        return isCachedNodeOrDescendantLocked(node.getParentNodeId(), ancestorId,  nodes);
    }

    /**
     * Clears nodes for the window with the given id
     */
    private void clearNodesForWindowLocked(int windowId) {
        if (DEBUG) {
            Log.i(LOG_TAG, "clearNodesForWindowLocked(" + windowId + ")");
        }
        LongSparseArray<AccessibilityNodeInfo> nodes = mNodeCache.get(windowId);
        if (nodes == null) {
            return;
        }
        mNodeCache.remove(windowId);
    }

    /** Clears a subtree rooted at {@code info}. */
    public boolean clearSubTree(AccessibilityNodeInfo info) {
        if (info == null) {
            return false;
        }
        synchronized (mLock) {
            if (!mEnabled) {
                if (DEBUG) {
                    Log.i(LOG_TAG, "Cache is disabled");
                }
                return false;
            }
            clearSubTreeLocked(info.getWindowId(), info.getSourceNodeId());
            return true;
        }
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
     *
     * @return {@code true} if the cache was cleared
     */
    private boolean clearSubTreeRecursiveLocked(LongSparseArray<AccessibilityNodeInfo> nodes,
            long rootNodeId) {
        AccessibilityNodeInfo current = nodes.get(rootNodeId);
        if (current == null) {
            // The node isn't in the cache, but its descendents might be.
            clear();
            return true;
        }
        nodes.remove(rootNodeId);
        final int childCount = current.getChildCount();
        for (int i = 0; i < childCount; i++) {
            final long childNodeId = current.getChildId(i);
            if (clearSubTreeRecursiveLocked(nodes, childNodeId)) {
                return true;
            }
        }
        return false;
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
            if (mWindowCacheByDisplay.size() <= 0 && mNodeCache.size() == 0) {
                return;
            }

            AccessibilityWindowInfo focusedWindow = null;
            AccessibilityWindowInfo activeWindow = null;

            final int displayCounts = mWindowCacheByDisplay.size();
            for (int i = 0; i < displayCounts; i++) {
                final SparseArray<AccessibilityWindowInfo> windowsOfDisplay =
                        mWindowCacheByDisplay.valueAt(i);

                if (windowsOfDisplay == null) {
                    continue;
                }

                final int windowCount = windowsOfDisplay.size();
                for (int j = 0; j < windowCount; j++) {
                    final AccessibilityWindowInfo window = windowsOfDisplay.valueAt(j);

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

    /**
     * Registers a listener to receive callbacks whenever nodes are added to cache.
     *
     * @param listener the listener to be registered.
     */
    public void registerOnNodeAddedListener(OnNodeAddedListener listener) {
        synchronized (mLock) {
            mOnNodeAddedListener = listener;
        }
    }

    /**
     * Clears the current reference to an OnNodeAddedListener, if one exists.
     */
    public void clearOnNodeAddedListener() {
        synchronized (mLock) {
            mOnNodeAddedListener = null;
        }
    }

    // Layer of indirection included to break dependency chain for testing
    public static class AccessibilityNodeRefresher {
        /** Refresh the given AccessibilityNodeInfo object. */
        public boolean refreshNode(AccessibilityNodeInfo info, boolean bypassCache) {
            return info.refresh(null, bypassCache);
        }

        /** Refresh the given AccessibilityWindowInfo object. */
        public boolean refreshWindow(AccessibilityWindowInfo info) {
            return info.refresh();
        }
    }

    /**
     * Listener interface that receives callbacks when nodes are added to cache.
     */
    public interface OnNodeAddedListener {
        /** Called when a node is added to cache. */
        void onNodeAdded(AccessibilityNodeInfo node);
    }
}
