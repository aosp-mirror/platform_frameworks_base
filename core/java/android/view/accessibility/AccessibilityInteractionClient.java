/*
 ** Copyright 2011, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

package android.view.accessibility;

import static android.accessibilityservice.AccessibilityTrace.FLAGS_ACCESSIBILITY_INTERACTION_CLIENT;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_ACCESSIBILITY_INTERACTION_CONNECTION_CALLBACK;

import android.accessibilityservice.IAccessibilityServiceConnection;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.SparseArray;
import android.util.SparseLongArray;
import android.view.Display;
import android.view.ViewConfiguration;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class is a singleton that performs accessibility interaction
 * which is it queries remote view hierarchies about snapshots of their
 * views as well requests from these hierarchies to perform certain
 * actions on their views.
 *
 * Rationale: The content retrieval APIs are synchronous from a client's
 *     perspective but internally they are asynchronous. The client thread
 *     calls into the system requesting an action and providing a callback
 *     to receive the result after which it waits up to a timeout for that
 *     result. The system enforces security and the delegates the request
 *     to a given view hierarchy where a message is posted (from a binder
 *     thread) describing what to be performed by the main UI thread the
 *     result of which it delivered via the mentioned callback. However,
 *     the blocked client thread and the main UI thread of the target view
 *     hierarchy can be the same thread, for example an accessibility service
 *     and an activity run in the same process, thus they are executed on the
 *     same main thread. In such a case the retrieval will fail since the UI
 *     thread that has to process the message describing the work to be done
 *     is blocked waiting for a result is has to compute! To avoid this scenario
 *     when making a call the client also passes its process and thread ids so
 *     the accessed view hierarchy can detect if the client making the request
 *     is running in its main UI thread. In such a case the view hierarchy,
 *     specifically the binder thread performing the IPC to it, does not post a
 *     message to be run on the UI thread but passes it to the singleton
 *     interaction client through which all interactions occur and the latter is
 *     responsible to execute the message before starting to wait for the
 *     asynchronous result delivered via the callback. In this case the expected
 *     result is already received so no waiting is performed.
 *
 * @hide
 */
public final class AccessibilityInteractionClient
        extends IAccessibilityInteractionConnectionCallback.Stub {

    public static final int NO_ID = -1;

    public static final String CALL_STACK = "call_stack";
    public static final String IGNORE_CALL_STACK = "ignore_call_stack";

    private static final String LOG_TAG = "AccessibilityInteractionClient";

    private static final boolean DEBUG = false;

    private static final boolean CHECK_INTEGRITY = true;

    private static final long TIMEOUT_INTERACTION_MILLIS = 5000;

    private static final long DISABLE_PREFETCHING_FOR_SCROLLING_MILLIS =
            (long) (ViewConfiguration.getSendRecurringAccessibilityEventsInterval() * 1.5);

    private static final Object sStaticLock = new Object();

    private static final LongSparseArray<AccessibilityInteractionClient> sClients =
        new LongSparseArray<>();

    private static final SparseArray<IAccessibilityServiceConnection> sConnectionCache =
            new SparseArray<>();

    /** List of timestamps which indicate the latest time an a11y service receives a scroll event
        from a window, mapping from windowId -> timestamp. */
    private static final SparseLongArray sScrollingWindows = new SparseLongArray();

    private static AccessibilityCache sAccessibilityCache =
            new AccessibilityCache(new AccessibilityCache.AccessibilityNodeRefresher());

    private final AtomicInteger mInteractionIdCounter = new AtomicInteger();

    private final Object mInstanceLock = new Object();

    private final AccessibilityManager  mAccessibilityManager;

    private volatile int mInteractionId = -1;
    private volatile int mCallingUid = Process.INVALID_UID;
    // call stack for IAccessibilityInteractionConnectionCallback APIs. These callback APIs are
    // shared by multiple requests APIs in IAccessibilityServiceConnection. To correctly log the
    // request API which triggers the callback, we log trace entries for callback after the
    // request API thread waiting for the callback returns. To log the correct callback stack in
    // the request API thread, we save the callback stack in this member variables.
    private List<StackTraceElement> mCallStackOfCallback;

    private AccessibilityNodeInfo mFindAccessibilityNodeInfoResult;

    private List<AccessibilityNodeInfo> mFindAccessibilityNodeInfosResult;

    private boolean mPerformAccessibilityActionResult;

    private Message mSameThreadMessage;

    private int mInteractionIdWaitingForPrefetchResult = -1;
    private int mConnectionIdWaitingForPrefetchResult;
    private String[] mPackageNamesForNextPrefetchResult;

    /**
     * @return The client for the current thread.
     */
    @UnsupportedAppUsage()
    public static AccessibilityInteractionClient getInstance() {
        final long threadId = Thread.currentThread().getId();
        return getInstanceForThread(threadId);
    }

    /**
     * <strong>Note:</strong> We keep one instance per interrogating thread since
     * the instance contains state which can lead to undesired thread interleavings.
     * We do not have a thread local variable since other threads should be able to
     * look up the correct client knowing a thread id. See ViewRootImpl for details.
     *
     * @return The client for a given <code>threadId</code>.
     */
    public static AccessibilityInteractionClient getInstanceForThread(long threadId) {
        synchronized (sStaticLock) {
            AccessibilityInteractionClient client = sClients.get(threadId);
            if (client == null) {
                client = new AccessibilityInteractionClient();
                sClients.put(threadId, client);
            }
            return client;
        }
    }

    /**
     * @return The client for the current thread.
     */
    public static AccessibilityInteractionClient getInstance(Context context) {
        final long threadId = Thread.currentThread().getId();
        if (context != null) {
            return getInstanceForThread(threadId, context);
        }
        return getInstanceForThread(threadId);
    }

    /**
     * <strong>Note:</strong> We keep one instance per interrogating thread since
     * the instance contains state which can lead to undesired thread interleavings.
     * We do not have a thread local variable since other threads should be able to
     * look up the correct client knowing a thread id. See ViewRootImpl for details.
     *
     * @return The client for a given <code>threadId</code>.
     */
    public static AccessibilityInteractionClient getInstanceForThread(
            long threadId, Context context) {
        synchronized (sStaticLock) {
            AccessibilityInteractionClient client = sClients.get(threadId);
            if (client == null) {
                client = new AccessibilityInteractionClient(context);
                sClients.put(threadId, client);
            }
            return client;
        }
    }

    /**
     * Gets a cached accessibility service connection.
     *
     * @param connectionId The connection id.
     * @return The cached connection if such.
     */
    public static IAccessibilityServiceConnection getConnection(int connectionId) {
        synchronized (sConnectionCache) {
            return sConnectionCache.get(connectionId);
        }
    }

    /**
     * Adds a cached accessibility service connection.
     *
     * @param connectionId The connection id.
     * @param connection The connection.
     */
    public static void addConnection(int connectionId, IAccessibilityServiceConnection connection) {
        synchronized (sConnectionCache) {
            sConnectionCache.put(connectionId, connection);
        }
    }

    /**
     * Removes a cached accessibility service connection.
     *
     * @param connectionId The connection id.
     */
    public static void removeConnection(int connectionId) {
        synchronized (sConnectionCache) {
            sConnectionCache.remove(connectionId);
        }
    }

    /**
     * This method is only for testing. Replacing the cache is a generally terrible idea, but
     * tests need to be able to verify this class's interactions with the cache
     */
    @VisibleForTesting
    public static void setCache(AccessibilityCache cache) {
        sAccessibilityCache = cache;
    }

    private AccessibilityInteractionClient() {
        /* reducing constructor visibility */
        mAccessibilityManager = null;
    }

    private AccessibilityInteractionClient(Context context) {
        mAccessibilityManager = context.getSystemService(AccessibilityManager.class);
    }

    /**
     * Sets the message to be processed if the interacted view hierarchy
     * and the interacting client are running in the same thread.
     *
     * @param message The message.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setSameThreadMessage(Message message) {
        synchronized (mInstanceLock) {
            mSameThreadMessage = message;
            mInstanceLock.notifyAll();
        }
    }

    /**
     * Gets the root {@link AccessibilityNodeInfo} in the currently active window.
     *
     * @param connectionId The id of a connection for interacting with the system.
     * @return The root {@link AccessibilityNodeInfo} if found, null otherwise.
     */
    public AccessibilityNodeInfo getRootInActiveWindow(int connectionId) {
        return findAccessibilityNodeInfoByAccessibilityId(connectionId,
                AccessibilityWindowInfo.ACTIVE_WINDOW_ID, AccessibilityNodeInfo.ROOT_NODE_ID,
                false, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS, null);
    }

    /**
     * Gets the info for a window.
     *
     * @param connectionId The id of a connection for interacting with the system.
     * @param accessibilityWindowId A unique window id. Use
     *     {@link android.view.accessibility.AccessibilityWindowInfo#ACTIVE_WINDOW_ID}
     *     to query the currently active window.
     * @return The {@link AccessibilityWindowInfo}.
     */
    public AccessibilityWindowInfo getWindow(int connectionId, int accessibilityWindowId) {
        return getWindow(connectionId, accessibilityWindowId, /* bypassCache */ false);
    }

    /**
     * Gets the info for a window.
     *
     * @param connectionId The id of a connection for interacting with the system.
     * @param accessibilityWindowId A unique window id. Use
     *     {@link android.view.accessibility.AccessibilityWindowInfo#ACTIVE_WINDOW_ID}
     *     to query the currently active window.
     * @param bypassCache Whether to bypass the cache.
     * @return The {@link AccessibilityWindowInfo}.
     */
    public AccessibilityWindowInfo getWindow(int connectionId, int accessibilityWindowId,
            boolean bypassCache) {
        try {
            IAccessibilityServiceConnection connection = getConnection(connectionId);
            if (connection != null) {
                AccessibilityWindowInfo window;
                if (!bypassCache) {
                    window = sAccessibilityCache.getWindow(accessibilityWindowId);
                    if (window != null) {
                        if (DEBUG) {
                            Log.i(LOG_TAG, "Window cache hit");
                        }
                        if (shouldTraceClient()) {
                            logTraceClient(connection, "getWindow cache",
                                    "connectionId=" + connectionId + ";accessibilityWindowId="
                                    + accessibilityWindowId + ";bypassCache=false");
                        }
                        return window;
                    }
                    if (DEBUG) {
                        Log.i(LOG_TAG, "Window cache miss");
                    }
                }

                final long identityToken = Binder.clearCallingIdentity();
                try {
                    window = connection.getWindow(accessibilityWindowId);
                } finally {
                    Binder.restoreCallingIdentity(identityToken);
                }
                if (shouldTraceClient()) {
                    logTraceClient(connection, "getWindow", "connectionId=" + connectionId
                            + ";accessibilityWindowId=" + accessibilityWindowId + ";bypassCache="
                            + bypassCache);
                }

                if (window != null) {
                    if (!bypassCache) {
                        sAccessibilityCache.addWindow(window);
                    }
                    return window;
                }
            } else {
                if (DEBUG) {
                    Log.w(LOG_TAG, "No connection for connection id: " + connectionId);
                }
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while calling remote getWindow", re);
        }
        return null;
    }

    /**
     * Gets the info for all windows of the default display.
     *
     * @param connectionId The id of a connection for interacting with the system.
     * @return The {@link AccessibilityWindowInfo} list.
     */
    public List<AccessibilityWindowInfo> getWindows(int connectionId) {
        final SparseArray<List<AccessibilityWindowInfo>> windows =
                getWindowsOnAllDisplays(connectionId);
        if (windows.size() > 0) {
            return windows.valueAt(Display.DEFAULT_DISPLAY);
        }
        return Collections.emptyList();
    }

    /**
     * Gets the info for all windows of all displays.
     *
     * @param connectionId The id of a connection for interacting with the system.
     * @return The SparseArray of {@link AccessibilityWindowInfo} list.
     *         The key of SparseArray is display ID.
     */
    public SparseArray<List<AccessibilityWindowInfo>> getWindowsOnAllDisplays(int connectionId) {
        try {
            IAccessibilityServiceConnection connection = getConnection(connectionId);
            if (connection != null) {
                SparseArray<List<AccessibilityWindowInfo>> windows =
                        sAccessibilityCache.getWindowsOnAllDisplays();
                if (windows != null) {
                    if (DEBUG) {
                        Log.i(LOG_TAG, "Windows cache hit");
                    }
                    if (shouldTraceClient()) {
                        logTraceClient(
                                connection, "getWindows cache", "connectionId=" + connectionId);
                    }
                    return windows;
                }
                if (DEBUG) {
                    Log.i(LOG_TAG, "Windows cache miss");
                }
                final long identityToken = Binder.clearCallingIdentity();
                try {
                    windows = connection.getWindows();
                } finally {
                    Binder.restoreCallingIdentity(identityToken);
                }
                if (shouldTraceClient()) {
                    logTraceClient(connection, "getWindows", "connectionId=" + connectionId);
                }
                if (windows != null) {
                    sAccessibilityCache.setWindowsOnAllDisplays(windows);
                    return windows;
                }
            } else {
                if (DEBUG) {
                    Log.w(LOG_TAG, "No connection for connection id: " + connectionId);
                }
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while calling remote getWindowsOnAllDisplays", re);
        }

        final SparseArray<List<AccessibilityWindowInfo>> emptyWindows = new SparseArray<>();
        return emptyWindows;
    }


    /**
     * Finds an {@link AccessibilityNodeInfo} by accessibility id and given leash token instead of
     * window id. This method is used to find the leashed node on the embedded view hierarchy.
     *
     * @param connectionId The id of a connection for interacting with the system.
     * @param leashToken The token of the embedded hierarchy.
     * @param accessibilityNodeId A unique view id or virtual descendant id from
     *     where to start the search. Use
     *     {@link android.view.accessibility.AccessibilityNodeInfo#ROOT_NODE_ID}
     *     to start from the root.
     * @param bypassCache Whether to bypass the cache while looking for the node.
     * @param prefetchFlags flags to guide prefetching.
     * @param arguments Optional action arguments.
     * @return An {@link AccessibilityNodeInfo} if found, null otherwise.
     */
    public @Nullable AccessibilityNodeInfo findAccessibilityNodeInfoByAccessibilityId(
            int connectionId, @NonNull IBinder leashToken, long accessibilityNodeId,
            boolean bypassCache, int prefetchFlags, Bundle arguments) {
        if (leashToken == null) {
            return null;
        }
        int windowId = -1;
        try {
            IAccessibilityServiceConnection connection = getConnection(connectionId);
            if (connection != null) {
                windowId = connection.getWindowIdForLeashToken(leashToken);
            } else {
                if (DEBUG) {
                    Log.w(LOG_TAG, "No connection for connection id: " + connectionId);
                }
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while calling remote getWindowIdForLeashToken", re);
        }
        if (windowId == -1) {
            return null;
        }
        return findAccessibilityNodeInfoByAccessibilityId(connectionId, windowId,
                accessibilityNodeId, bypassCache, prefetchFlags, arguments);
    }

    /**
     * Finds an {@link AccessibilityNodeInfo} by accessibility id.
     *
     * @param connectionId The id of a connection for interacting with the system.
     * @param accessibilityWindowId A unique window id. Use
     *     {@link android.view.accessibility.AccessibilityWindowInfo#ACTIVE_WINDOW_ID}
     *     to query the currently active window.
     * @param accessibilityNodeId A unique view id or virtual descendant id from
     *     where to start the search. Use
     *     {@link android.view.accessibility.AccessibilityNodeInfo#ROOT_NODE_ID}
     *     to start from the root.
     * @param bypassCache Whether to bypass the cache while looking for the node.
     * @param prefetchFlags flags to guide prefetching.
     * @return An {@link AccessibilityNodeInfo} if found, null otherwise.
     */
    public AccessibilityNodeInfo findAccessibilityNodeInfoByAccessibilityId(int connectionId,
            int accessibilityWindowId, long accessibilityNodeId, boolean bypassCache,
            int prefetchFlags, Bundle arguments) {
        if ((prefetchFlags & AccessibilityNodeInfo.FLAG_PREFETCH_SIBLINGS) != 0
                && (prefetchFlags & AccessibilityNodeInfo.FLAG_PREFETCH_PREDECESSORS) == 0) {
            throw new IllegalArgumentException("FLAG_PREFETCH_SIBLINGS"
                + " requires FLAG_PREFETCH_PREDECESSORS");
        }
        try {
            IAccessibilityServiceConnection connection = getConnection(connectionId);
            if (connection != null) {
                if (!bypassCache) {
                    AccessibilityNodeInfo cachedInfo = sAccessibilityCache.getNode(
                            accessibilityWindowId, accessibilityNodeId);
                    if (cachedInfo != null) {
                        if (DEBUG) {
                            Log.i(LOG_TAG, "Node cache hit for "
                                    + idToString(accessibilityWindowId, accessibilityNodeId));
                        }
                        if (shouldTraceClient()) {
                            logTraceClient(connection,
                                    "findAccessibilityNodeInfoByAccessibilityId cache",
                                    "connectionId=" + connectionId + ";accessibilityWindowId="
                                    + accessibilityWindowId + ";accessibilityNodeId="
                                    + accessibilityNodeId + ";bypassCache=" + bypassCache
                                    + ";prefetchFlags=" + prefetchFlags + ";arguments="
                                    + arguments);
                        }
                        return cachedInfo;
                    }
                    if (DEBUG) {
                        Log.i(LOG_TAG, "Node cache miss for "
                                + idToString(accessibilityWindowId, accessibilityNodeId));
                    }
                } else {
                    // No need to prefech nodes in bypass cache case.
                    prefetchFlags &= ~AccessibilityNodeInfo.FLAG_PREFETCH_MASK;
                }
                // Skip prefetching if window is scrolling.
                if ((prefetchFlags & AccessibilityNodeInfo.FLAG_PREFETCH_MASK) != 0
                        && isWindowScrolling(accessibilityWindowId)) {
                    prefetchFlags &= ~AccessibilityNodeInfo.FLAG_PREFETCH_MASK;
                }
                final int interactionId = mInteractionIdCounter.getAndIncrement();
                if (shouldTraceClient()) {
                    logTraceClient(connection, "findAccessibilityNodeInfoByAccessibilityId",
                            "InteractionId:" + interactionId + "connectionId=" + connectionId
                            + ";accessibilityWindowId=" + accessibilityWindowId
                            + ";accessibilityNodeId=" + accessibilityNodeId + ";bypassCache="
                            + bypassCache + ";prefetchFlags=" + prefetchFlags + ";arguments="
                            + arguments);
                }
                final String[] packageNames;
                final long identityToken = Binder.clearCallingIdentity();
                try {
                    packageNames = connection.findAccessibilityNodeInfoByAccessibilityId(
                            accessibilityWindowId, accessibilityNodeId, interactionId, this,
                            prefetchFlags, Thread.currentThread().getId(), arguments);
                } finally {
                    Binder.restoreCallingIdentity(identityToken);
                }
                if (packageNames != null) {
                    AccessibilityNodeInfo info =
                            getFindAccessibilityNodeInfoResultAndClear(interactionId);
                    if (shouldTraceCallback()) {
                        logTraceCallback(connection, "findAccessibilityNodeInfoByAccessibilityId",
                                "InteractionId:" + interactionId + ";connectionId="
                                + connectionId + ";Result: " + info);
                    }
                    if ((prefetchFlags & AccessibilityNodeInfo.FLAG_PREFETCH_MASK) != 0
                            && info != null) {
                        setInteractionWaitingForPrefetchResult(interactionId, connectionId,
                                packageNames);
                    }
                    finalizeAndCacheAccessibilityNodeInfo(info, connectionId,
                            bypassCache, packageNames);
                    return info;
                }
            } else {
                if (DEBUG) {
                    Log.w(LOG_TAG, "No connection for connection id: " + connectionId);
                }
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while calling remote"
                    + " findAccessibilityNodeInfoByAccessibilityId", re);
        }
        return null;
    }

    private void setInteractionWaitingForPrefetchResult(int interactionId, int connectionId,
            String[] packageNames) {
        synchronized (mInstanceLock) {
            mInteractionIdWaitingForPrefetchResult = interactionId;
            mConnectionIdWaitingForPrefetchResult = connectionId;
            mPackageNamesForNextPrefetchResult = packageNames;
        }
    }

    private static String idToString(int accessibilityWindowId, long accessibilityNodeId) {
        return accessibilityWindowId + "/"
                + AccessibilityNodeInfo.idToString(accessibilityNodeId);
    }

    /**
     * Finds an {@link AccessibilityNodeInfo} by View id. The search is performed in
     * the window whose id is specified and starts from the node whose accessibility
     * id is specified.
     *
     * @param connectionId The id of a connection for interacting with the system.
     * @param accessibilityWindowId A unique window id. Use
     *     {@link android.view.accessibility.AccessibilityWindowInfo#ACTIVE_WINDOW_ID}
     *     to query the currently active window.
     * @param accessibilityNodeId A unique view id or virtual descendant id from
     *     where to start the search. Use
     *     {@link android.view.accessibility.AccessibilityNodeInfo#ROOT_NODE_ID}
     *     to start from the root.
     * @param viewId The fully qualified resource name of the view id to find.
     * @return An list of {@link AccessibilityNodeInfo} if found, empty list otherwise.
     */
    public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByViewId(int connectionId,
            int accessibilityWindowId, long accessibilityNodeId, String viewId) {
        try {
            IAccessibilityServiceConnection connection = getConnection(connectionId);
            if (connection != null) {
                final int interactionId = mInteractionIdCounter.getAndIncrement();
                final String[] packageNames;
                final long identityToken = Binder.clearCallingIdentity();
                try {
                    if (shouldTraceClient()) {
                        logTraceClient(connection, "findAccessibilityNodeInfosByViewId",
                                "InteractionId=" + interactionId + ";connectionId=" + connectionId
                                + ";accessibilityWindowId=" + accessibilityWindowId
                                + ";accessibilityNodeId=" + accessibilityNodeId + ";viewId="
                                + viewId);
                    }

                    packageNames = connection.findAccessibilityNodeInfosByViewId(
                            accessibilityWindowId, accessibilityNodeId, viewId, interactionId, this,
                            Thread.currentThread().getId());
                } finally {
                    Binder.restoreCallingIdentity(identityToken);
                }

                if (packageNames != null) {
                    List<AccessibilityNodeInfo> infos = getFindAccessibilityNodeInfosResultAndClear(
                            interactionId);
                    if (shouldTraceCallback()) {
                        logTraceCallback(connection, "findAccessibilityNodeInfosByViewId",
                                "InteractionId=" + interactionId + ";connectionId=" + connectionId
                                + ":Result: " + infos);
                    }
                    if (infos != null) {
                        finalizeAndCacheAccessibilityNodeInfos(infos, connectionId,
                                false, packageNames);
                        return infos;
                    }
                }
            } else {
                if (DEBUG) {
                    Log.w(LOG_TAG, "No connection for connection id: " + connectionId);
                }
            }
        } catch (RemoteException re) {
            Log.w(LOG_TAG, "Error while calling remote"
                    + " findAccessibilityNodeInfoByViewIdInActiveWindow", re);
        }
        return Collections.emptyList();
    }

    /**
     * Finds {@link AccessibilityNodeInfo}s by View text. The match is case
     * insensitive containment. The search is performed in the window whose
     * id is specified and starts from the node whose accessibility id is
     * specified.
     *
     * @param connectionId The id of a connection for interacting with the system.
     * @param accessibilityWindowId A unique window id. Use
     *     {@link android.view.accessibility.AccessibilityWindowInfo#ACTIVE_WINDOW_ID}
     *     to query the currently active window.
     * @param accessibilityNodeId A unique view id or virtual descendant id from
     *     where to start the search. Use
     *     {@link android.view.accessibility.AccessibilityNodeInfo#ROOT_NODE_ID}
     *     to start from the root.
     * @param text The searched text.
     * @return A list of found {@link AccessibilityNodeInfo}s.
     */
    public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByText(int connectionId,
            int accessibilityWindowId, long accessibilityNodeId, String text) {
        try {
            IAccessibilityServiceConnection connection = getConnection(connectionId);
            if (connection != null) {
                final int interactionId = mInteractionIdCounter.getAndIncrement();
                if (shouldTraceClient()) {
                    logTraceClient(connection, "findAccessibilityNodeInfosByText",
                            "InteractionId:" + interactionId + "connectionId=" + connectionId
                            + ";accessibilityWindowId=" + accessibilityWindowId
                            + ";accessibilityNodeId=" + accessibilityNodeId + ";text=" + text);
                }
                final String[] packageNames;
                final long identityToken = Binder.clearCallingIdentity();
                try {
                    packageNames = connection.findAccessibilityNodeInfosByText(
                            accessibilityWindowId, accessibilityNodeId, text, interactionId, this,
                            Thread.currentThread().getId());
                } finally {
                    Binder.restoreCallingIdentity(identityToken);
                }

                if (packageNames != null) {
                    List<AccessibilityNodeInfo> infos = getFindAccessibilityNodeInfosResultAndClear(
                            interactionId);
                    if (shouldTraceCallback()) {
                        logTraceCallback(connection, "findAccessibilityNodeInfosByText",
                                "InteractionId=" + interactionId + ";connectionId=" + connectionId
                                + ";Result: " + infos);
                    }
                    if (infos != null) {
                        finalizeAndCacheAccessibilityNodeInfos(infos, connectionId,
                                false, packageNames);
                        return infos;
                    }
                }
            } else {
                if (DEBUG) {
                    Log.w(LOG_TAG, "No connection for connection id: " + connectionId);
                }
            }
        } catch (RemoteException re) {
            Log.w(LOG_TAG, "Error while calling remote"
                    + " findAccessibilityNodeInfosByViewText", re);
        }
        return Collections.emptyList();
    }

    /**
     * Finds the {@link android.view.accessibility.AccessibilityNodeInfo} that has the
     * specified focus type. The search is performed in the window whose id is specified
     * and starts from the node whose accessibility id is specified.
     *
     * @param connectionId The id of a connection for interacting with the system.
     * @param accessibilityWindowId A unique window id. Use
     *     {@link android.view.accessibility.AccessibilityWindowInfo#ACTIVE_WINDOW_ID}
     *     to query the currently active window.
     * @param accessibilityNodeId A unique view id or virtual descendant id from
     *     where to start the search. Use
     *     {@link android.view.accessibility.AccessibilityNodeInfo#ROOT_NODE_ID}
     *     to start from the root.
     * @param focusType The focus type.
     * @return The accessibility focused {@link AccessibilityNodeInfo}.
     */
    public AccessibilityNodeInfo findFocus(int connectionId, int accessibilityWindowId,
            long accessibilityNodeId, int focusType) {
        try {
            IAccessibilityServiceConnection connection = getConnection(connectionId);
            if (connection != null) {
                final int interactionId = mInteractionIdCounter.getAndIncrement();
                if (shouldTraceClient()) {
                    logTraceClient(connection, "findFocus",
                            "InteractionId:" + interactionId + "connectionId=" + connectionId
                            + ";accessibilityWindowId=" + accessibilityWindowId
                            + ";accessibilityNodeId=" + accessibilityNodeId + ";focusType="
                            + focusType);
                }
                final String[] packageNames;
                final long identityToken = Binder.clearCallingIdentity();
                try {
                    packageNames = connection.findFocus(accessibilityWindowId,
                            accessibilityNodeId, focusType, interactionId, this,
                            Thread.currentThread().getId());
                } finally {
                    Binder.restoreCallingIdentity(identityToken);
                }

                if (packageNames != null) {
                    AccessibilityNodeInfo info = getFindAccessibilityNodeInfoResultAndClear(
                            interactionId);
                    if (shouldTraceCallback()) {
                        logTraceCallback(connection, "findFocus", "InteractionId=" + interactionId
                                + ";connectionId=" + connectionId + ";Result:" + info);
                    }
                    finalizeAndCacheAccessibilityNodeInfo(info, connectionId, false, packageNames);
                    return info;
                }
            } else {
                if (DEBUG) {
                    Log.w(LOG_TAG, "No connection for connection id: " + connectionId);
                }
            }
        } catch (RemoteException re) {
            Log.w(LOG_TAG, "Error while calling remote findFocus", re);
        }
        return null;
    }

    /**
     * Finds the accessibility focused {@link android.view.accessibility.AccessibilityNodeInfo}.
     * The search is performed in the window whose id is specified and starts from the
     * node whose accessibility id is specified.
     *
     * @param connectionId The id of a connection for interacting with the system.
     * @param accessibilityWindowId A unique window id. Use
     *     {@link android.view.accessibility.AccessibilityWindowInfo#ACTIVE_WINDOW_ID}
     *     to query the currently active window.
     * @param accessibilityNodeId A unique view id or virtual descendant id from
     *     where to start the search. Use
     *     {@link android.view.accessibility.AccessibilityNodeInfo#ROOT_NODE_ID}
     *     to start from the root.
     * @param direction The direction in which to search for focusable.
     * @return The accessibility focused {@link AccessibilityNodeInfo}.
     */
    public AccessibilityNodeInfo focusSearch(int connectionId, int accessibilityWindowId,
            long accessibilityNodeId, int direction) {
        try {
            IAccessibilityServiceConnection connection = getConnection(connectionId);
            if (connection != null) {
                final int interactionId = mInteractionIdCounter.getAndIncrement();
                if (shouldTraceClient()) {
                    logTraceClient(connection, "focusSearch",
                            "InteractionId:" + interactionId + "connectionId=" + connectionId
                            + ";accessibilityWindowId=" + accessibilityWindowId
                            + ";accessibilityNodeId=" + accessibilityNodeId + ";direction="
                            + direction);
                }
                final String[] packageNames;
                final long identityToken = Binder.clearCallingIdentity();
                try {
                    packageNames = connection.focusSearch(accessibilityWindowId,
                            accessibilityNodeId, direction, interactionId, this,
                            Thread.currentThread().getId());
                } finally {
                    Binder.restoreCallingIdentity(identityToken);
                }

                if (packageNames != null) {
                    AccessibilityNodeInfo info = getFindAccessibilityNodeInfoResultAndClear(
                            interactionId);
                    finalizeAndCacheAccessibilityNodeInfo(info, connectionId, false, packageNames);
                    if (shouldTraceCallback()) {
                        logTraceCallback(connection, "focusSearch", "InteractionId=" + interactionId
                                + ";connectionId=" + connectionId + ";Result:" + info);
                    }
                    return info;
                }
            } else {
                if (DEBUG) {
                    Log.w(LOG_TAG, "No connection for connection id: " + connectionId);
                }
            }
        } catch (RemoteException re) {
            Log.w(LOG_TAG, "Error while calling remote accessibilityFocusSearch", re);
        }
        return null;
    }

    /**
     * Performs an accessibility action on an {@link AccessibilityNodeInfo}.
     *
     * @param connectionId The id of a connection for interacting with the system.
     * @param accessibilityWindowId A unique window id. Use
     *     {@link android.view.accessibility.AccessibilityWindowInfo#ACTIVE_WINDOW_ID}
     *     to query the currently active window.
     * @param accessibilityNodeId A unique view id or virtual descendant id from
     *     where to start the search. Use
     *     {@link android.view.accessibility.AccessibilityNodeInfo#ROOT_NODE_ID}
     *     to start from the root.
     * @param action The action to perform.
     * @param arguments Optional action arguments.
     * @return Whether the action was performed.
     */
    public boolean performAccessibilityAction(int connectionId, int accessibilityWindowId,
            long accessibilityNodeId, int action, Bundle arguments) {
        try {
            IAccessibilityServiceConnection connection = getConnection(connectionId);
            if (connection != null) {
                final int interactionId = mInteractionIdCounter.getAndIncrement();
                if (shouldTraceClient()) {
                    logTraceClient(connection, "performAccessibilityAction",
                            "InteractionId:" + interactionId + "connectionId=" + connectionId
                            + ";accessibilityWindowId=" + accessibilityWindowId
                            + ";accessibilityNodeId=" + accessibilityNodeId + ";action=" + action
                            + ";arguments=" + arguments);
                }
                final boolean success;
                final long identityToken = Binder.clearCallingIdentity();
                try {
                    success = connection.performAccessibilityAction(
                            accessibilityWindowId, accessibilityNodeId, action, arguments,
                            interactionId, this, Thread.currentThread().getId());
                } finally {
                    Binder.restoreCallingIdentity(identityToken);
                }

                if (success) {
                    final boolean result =
                            getPerformAccessibilityActionResultAndClear(interactionId);
                    if (shouldTraceCallback()) {
                        logTraceCallback(connection, "performAccessibilityAction",
                                "InteractionId=" + interactionId + ";connectionId=" + connectionId
                                + ";Result: " + result);
                    }
                    return result;
                }
            } else {
                if (DEBUG) {
                    Log.w(LOG_TAG, "No connection for connection id: " + connectionId);
                }
            }
        } catch (RemoteException re) {
            Log.w(LOG_TAG, "Error while calling remote performAccessibilityAction", re);
        }
        return false;
    }

    /**
     * Clears the accessibility cache.
     */
    @UnsupportedAppUsage()
    public void clearCache() {
        sAccessibilityCache.clear();
    }

    public void onAccessibilityEvent(AccessibilityEvent event) {
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_VIEW_SCROLLED:
                updateScrollingWindow(event.getWindowId(), SystemClock.uptimeMillis());
                break;
            case AccessibilityEvent.TYPE_WINDOWS_CHANGED:
                if (event.getWindowChanges() == AccessibilityEvent.WINDOWS_CHANGE_REMOVED) {
                    deleteScrollingWindow(event.getWindowId());
                }
                break;
            default:
                break;
        }
        sAccessibilityCache.onAccessibilityEvent(event);
    }

    /**
     * Gets the the result of an async request that returns an {@link AccessibilityNodeInfo}.
     *
     * @param interactionId The interaction id to match the result with the request.
     * @return The result {@link AccessibilityNodeInfo}.
     */
    private AccessibilityNodeInfo getFindAccessibilityNodeInfoResultAndClear(int interactionId) {
        synchronized (mInstanceLock) {
            final boolean success = waitForResultTimedLocked(interactionId);
            AccessibilityNodeInfo result = success ? mFindAccessibilityNodeInfoResult : null;
            clearResultLocked();
            return result;
        }
    }

    /**
     * {@inheritDoc}
     */
    public void setFindAccessibilityNodeInfoResult(AccessibilityNodeInfo info,
                int interactionId) {
        synchronized (mInstanceLock) {
            if (interactionId > mInteractionId) {
                mFindAccessibilityNodeInfoResult = info;
                mInteractionId = interactionId;
                mCallingUid = Binder.getCallingUid();
                mCallStackOfCallback = new ArrayList<StackTraceElement>(
                        Arrays.asList(Thread.currentThread().getStackTrace()));
            }
            mInstanceLock.notifyAll();
        }
    }

    /**
     * Gets the the result of an async request that returns {@link AccessibilityNodeInfo}s.
     *
     * @param interactionId The interaction id to match the result with the request.
     * @return The result {@link AccessibilityNodeInfo}s.
     */
    private List<AccessibilityNodeInfo> getFindAccessibilityNodeInfosResultAndClear(
                int interactionId) {
        synchronized (mInstanceLock) {
            final boolean success = waitForResultTimedLocked(interactionId);
            final List<AccessibilityNodeInfo> result;
            if (success) {
                result = mFindAccessibilityNodeInfosResult;
            } else {
                result = Collections.emptyList();
            }
            clearResultLocked();
            if (Build.IS_DEBUGGABLE && CHECK_INTEGRITY) {
                checkFindAccessibilityNodeInfoResultIntegrity(result);
            }
            return result;
        }
    }

    /**
     * {@inheritDoc}
     */
    public void setFindAccessibilityNodeInfosResult(List<AccessibilityNodeInfo> infos,
                int interactionId) {
        synchronized (mInstanceLock) {
            if (interactionId > mInteractionId) {
                if (infos != null) {
                    // If the call is not an IPC, i.e. it is made from the same process, we need to
                    // instantiate new result list to avoid passing internal instances to clients.
                    final boolean isIpcCall = (Binder.getCallingPid() != Process.myPid());
                    if (!isIpcCall) {
                        mFindAccessibilityNodeInfosResult = new ArrayList<>(infos);
                    } else {
                        mFindAccessibilityNodeInfosResult = infos;
                    }
                } else {
                    mFindAccessibilityNodeInfosResult = Collections.emptyList();
                }
                mInteractionId = interactionId;
                mCallingUid = Binder.getCallingUid();
                mCallStackOfCallback = new ArrayList<StackTraceElement>(
                    Arrays.asList(Thread.currentThread().getStackTrace()));
            }
            mInstanceLock.notifyAll();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setPrefetchAccessibilityNodeInfoResult(@NonNull List<AccessibilityNodeInfo> infos,
                                                       int interactionId) {
        int interactionIdWaitingForPrefetchResultCopy = -1;
        int connectionIdWaitingForPrefetchResultCopy = -1;
        String[] packageNamesForNextPrefetchResultCopy = null;

        if (infos.isEmpty()) {
            return;
        }

        synchronized (mInstanceLock) {
            if (mInteractionIdWaitingForPrefetchResult == interactionId) {
                interactionIdWaitingForPrefetchResultCopy = mInteractionIdWaitingForPrefetchResult;
                connectionIdWaitingForPrefetchResultCopy =
                        mConnectionIdWaitingForPrefetchResult;
                if (mPackageNamesForNextPrefetchResult != null) {
                    packageNamesForNextPrefetchResultCopy =
                            new String[mPackageNamesForNextPrefetchResult.length];
                    for (int i = 0; i < mPackageNamesForNextPrefetchResult.length; i++) {
                        packageNamesForNextPrefetchResultCopy[i] =
                                mPackageNamesForNextPrefetchResult[i];
                    }
                }
            }
        }

        if (interactionIdWaitingForPrefetchResultCopy == interactionId) {
            finalizeAndCacheAccessibilityNodeInfos(
                    infos, connectionIdWaitingForPrefetchResultCopy, false,
                    packageNamesForNextPrefetchResultCopy);
            if (shouldTraceCallback()) {
                logTrace(getConnection(connectionIdWaitingForPrefetchResultCopy),
                        "setPrefetchAccessibilityNodeInfoResult",
                        "InteractionId:" + interactionId + ";connectionId="
                        + connectionIdWaitingForPrefetchResultCopy + ";Result: " + infos,
                        Binder.getCallingUid(),
                        Arrays.asList(Thread.currentThread().getStackTrace()),
                        new HashSet<String>(Arrays.asList("getStackTrace")),
                        FLAGS_ACCESSIBILITY_INTERACTION_CONNECTION_CALLBACK);
            }
        } else if (DEBUG) {
            Log.w(LOG_TAG, "Prefetching for interaction with id " + interactionId + " dropped "
                    + infos.size() + " nodes");
        }
    }

    /**
     * Gets the result of a request to perform an accessibility action.
     *
     * @param interactionId The interaction id to match the result with the request.
     * @return Whether the action was performed.
     */
    private boolean getPerformAccessibilityActionResultAndClear(int interactionId) {
        synchronized (mInstanceLock) {
            final boolean success = waitForResultTimedLocked(interactionId);
            final boolean result = success ? mPerformAccessibilityActionResult : false;
            clearResultLocked();
            return result;
        }
    }

    /**
     * {@inheritDoc}
     */
    public void setPerformAccessibilityActionResult(boolean succeeded, int interactionId) {
        synchronized (mInstanceLock) {
            if (interactionId > mInteractionId) {
                mPerformAccessibilityActionResult = succeeded;
                mInteractionId = interactionId;
                mCallingUid = Binder.getCallingUid();
                mCallStackOfCallback = new ArrayList<StackTraceElement>(
                    Arrays.asList(Thread.currentThread().getStackTrace()));
            }
            mInstanceLock.notifyAll();
        }
    }

    /**
     * Clears the result state.
     */
    private void clearResultLocked() {
        mInteractionId = -1;
        mFindAccessibilityNodeInfoResult = null;
        mFindAccessibilityNodeInfosResult = null;
        mPerformAccessibilityActionResult = false;
    }

    /**
     * Waits up to a given bound for a result of a request and returns it.
     *
     * @param interactionId The interaction id to match the result with the request.
     * @return Whether the result was received.
     */
    private boolean waitForResultTimedLocked(int interactionId) {
        long waitTimeMillis = TIMEOUT_INTERACTION_MILLIS;
        final long startTimeMillis = SystemClock.uptimeMillis();
        while (true) {
            try {
                Message sameProcessMessage = getSameProcessMessageAndClear();
                if (sameProcessMessage != null) {
                    sameProcessMessage.getTarget().handleMessage(sameProcessMessage);
                }

                if (mInteractionId == interactionId) {
                    return true;
                }
                if (mInteractionId > interactionId) {
                    return false;
                }
                final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                waitTimeMillis = TIMEOUT_INTERACTION_MILLIS - elapsedTimeMillis;
                if (waitTimeMillis <= 0) {
                    return false;
                }
                mInstanceLock.wait(waitTimeMillis);
            } catch (InterruptedException ie) {
                /* ignore */
            }
        }
    }

    /**
     * Finalize an {@link AccessibilityNodeInfo} before passing it to the client.
     *
     * @param info The info.
     * @param connectionId The id of the connection to the system.
     * @param bypassCache Whether or not to bypass the cache. The node is added to the cache if
     *                    this value is {@code false}
     * @param packageNames The valid package names a node can come from.
     */
    private void finalizeAndCacheAccessibilityNodeInfo(AccessibilityNodeInfo info,
            int connectionId, boolean bypassCache, String[] packageNames) {
        if (info != null) {
            info.setConnectionId(connectionId);
            // Empty array means any package name is Okay
            if (!ArrayUtils.isEmpty(packageNames)) {
                CharSequence packageName = info.getPackageName();
                if (packageName == null
                        || !ArrayUtils.contains(packageNames, packageName.toString())) {
                    // If the node package not one of the valid ones, pick the top one - this
                    // is one of the packages running in the introspected UID.
                    info.setPackageName(packageNames[0]);
                }
            }
            info.setSealed(true);
            if (!bypassCache) {
                sAccessibilityCache.add(info);
            }
        }
    }

    /**
     * Finalize {@link AccessibilityNodeInfo}s before passing them to the client.
     *
     * @param infos The {@link AccessibilityNodeInfo}s.
     * @param connectionId The id of the connection to the system.
     * @param bypassCache Whether or not to bypass the cache. The nodes are added to the cache if
     *                    this value is {@code false}
     * @param packageNames The valid package names a node can come from.
     */
    private void finalizeAndCacheAccessibilityNodeInfos(List<AccessibilityNodeInfo> infos,
            int connectionId, boolean bypassCache, String[] packageNames) {
        if (infos != null) {
            final int infosCount = infos.size();
            for (int i = 0; i < infosCount; i++) {
                AccessibilityNodeInfo info = infos.get(i);
                finalizeAndCacheAccessibilityNodeInfo(info, connectionId,
                        bypassCache, packageNames);
            }
        }
    }

    /**
     * Gets the message stored if the interacted and interacting
     * threads are the same.
     *
     * @return The message.
     */
    private Message getSameProcessMessageAndClear() {
        synchronized (mInstanceLock) {
            Message result = mSameThreadMessage;
            mSameThreadMessage = null;
            return result;
        }
    }

    /**
     * Checks whether the infos are a fully connected tree with no duplicates.
     *
     * @param infos The result list to check.
     */
    private void checkFindAccessibilityNodeInfoResultIntegrity(List<AccessibilityNodeInfo> infos) {
        if (infos.size() == 0) {
            return;
        }
        // Find the root node.
        AccessibilityNodeInfo root = infos.get(0);
        final int infoCount = infos.size();
        for (int i = 1; i < infoCount; i++) {
            for (int j = i; j < infoCount; j++) {
                AccessibilityNodeInfo candidate = infos.get(j);
                if (root.getParentNodeId() == candidate.getSourceNodeId()) {
                    root = candidate;
                    break;
                }
            }
        }
        if (root == null) {
            Log.e(LOG_TAG, "No root.");
        }
        // Check for duplicates.
        HashSet<AccessibilityNodeInfo> seen = new HashSet<>();
        Queue<AccessibilityNodeInfo> fringe = new LinkedList<>();
        fringe.add(root);
        while (!fringe.isEmpty()) {
            AccessibilityNodeInfo current = fringe.poll();
            if (!seen.add(current)) {
                Log.e(LOG_TAG, "Duplicate node.");
                return;
            }
            final int childCount = current.getChildCount();
            for (int i = 0; i < childCount; i++) {
                final long childId = current.getChildId(i);
                for (int j = 0; j < infoCount; j++) {
                    AccessibilityNodeInfo child = infos.get(j);
                    if (child.getSourceNodeId() == childId) {
                        fringe.add(child);
                    }
                }
            }
        }
        final int disconnectedCount = infos.size() - seen.size();
        if (disconnectedCount > 0) {
            Log.e(LOG_TAG, disconnectedCount + " Disconnected nodes.");
        }
    }

    /**
     * Update scroll event timestamp of a given window.
     *
     * @param windowId The window id.
     * @param uptimeMillis Device uptime millis.
     */
    private void updateScrollingWindow(int windowId, long uptimeMillis) {
        synchronized (sScrollingWindows) {
            sScrollingWindows.put(windowId, uptimeMillis);
        }
    }

    /**
     * Remove a window from the scrolling windows list.
     *
     * @param windowId The window id.
     */
    private void deleteScrollingWindow(int windowId) {
        synchronized (sScrollingWindows) {
            sScrollingWindows.delete(windowId);
        }
    }

    /**
     * Whether or not the window is scrolling.
     *
     * @param windowId
     * @return true if it's scrolling.
     */
    private boolean isWindowScrolling(int windowId) {
        synchronized (sScrollingWindows) {
            final long latestScrollingTime = sScrollingWindows.get(windowId);
            if (latestScrollingTime == 0) {
                return false;
            }
            final long currentUptime = SystemClock.uptimeMillis();
            if (currentUptime > (latestScrollingTime + DISABLE_PREFETCHING_FOR_SCROLLING_MILLIS)) {
                sScrollingWindows.delete(windowId);
                return false;
            }
        }
        return true;
    }

    private boolean shouldTraceClient() {
        return (mAccessibilityManager != null)
                && mAccessibilityManager.isA11yInteractionClientTraceEnabled();
    }

    private boolean shouldTraceCallback() {
        return (mAccessibilityManager != null)
                && mAccessibilityManager.isA11yInteractionConnectionCBTraceEnabled();
    }

    private void logTrace(
            IAccessibilityServiceConnection connection, String method, String params,
            int callingUid, List<StackTraceElement> callStack, HashSet<String> ignoreSet,
            long logTypes) {
        try {
            Bundle b = new Bundle();
            b.putSerializable(CALL_STACK, new ArrayList<StackTraceElement>(callStack));
            if (ignoreSet != null) {
                b.putSerializable(IGNORE_CALL_STACK, ignoreSet);
            }
            connection.logTrace(SystemClock.elapsedRealtimeNanos(),
                    LOG_TAG + "." + method,
                    logTypes, params, Process.myPid(), Thread.currentThread().getId(),
                    callingUid, b);
        } catch (RemoteException e) {
            Log.e(LOG_TAG, "Failed to log trace. " + e);
        }
    }

    private void logTraceCallback(
            IAccessibilityServiceConnection connection, String method, String params) {
        logTrace(connection, method + " callback", params, mCallingUid, mCallStackOfCallback,
                new HashSet<String>(Arrays.asList("getStackTrace")),
                FLAGS_ACCESSIBILITY_INTERACTION_CONNECTION_CALLBACK);
    }

    private void logTraceClient(
            IAccessibilityServiceConnection connection, String method, String params) {
        logTrace(connection, method, params, Binder.getCallingUid(),
                Collections.emptyList(), null, FLAGS_ACCESSIBILITY_INTERACTION_CLIENT);
    }
}
