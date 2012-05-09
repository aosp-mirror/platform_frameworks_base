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

package android.accessibilityservice;

import android.accessibilityservice.AccessibilityService.Callbacks;
import android.accessibilityservice.AccessibilityService.IAccessibilityServiceClientWrapper;
import android.content.Context;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityInteractionClient;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.IAccessibilityManager;

import com.android.internal.util.Predicate;

import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * This class represents a bridge that can be used for UI test
 * automation. It is responsible for connecting to the system,
 * keeping track of the last accessibility event, and exposing
 * window content querying APIs. This class is designed to be
 * used from both an Android application and a Java program
 * run from the shell.
 *
 * @hide
 */
public class UiTestAutomationBridge {

    private static final String LOG_TAG = UiTestAutomationBridge.class.getSimpleName();

    private static final int TIMEOUT_REGISTER_SERVICE = 5000;

    public static final int ACTIVE_WINDOW_ID = AccessibilityNodeInfo.ACTIVE_WINDOW_ID;

    public static final long ROOT_NODE_ID = AccessibilityNodeInfo.ROOT_NODE_ID;

    public static final int UNDEFINED = -1;

    private static final int FIND_ACCESSIBILITY_NODE_INFO_PREFETCH_FLAGS =
        AccessibilityNodeInfo.FLAG_PREFETCH_PREDECESSORS
        | AccessibilityNodeInfo.FLAG_PREFETCH_SIBLINGS
        | AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS;

    private final Object mLock = new Object();

    private volatile int mConnectionId = AccessibilityInteractionClient.NO_ID;

    private IAccessibilityServiceClientWrapper mListener;

    private AccessibilityEvent mLastEvent;

    private volatile boolean mWaitingForEventDelivery;

    private volatile boolean mUnprocessedEventAvailable;

    private HandlerThread mHandlerThread;

    /**
     * Gets the last received {@link AccessibilityEvent}.
     *
     * @return The event.
     */
    public AccessibilityEvent getLastAccessibilityEvent() {
        return mLastEvent;
    }

    /**
     * Callback for receiving an {@link AccessibilityEvent}.
     *
     * <strong>Note:</strong> This method is <strong>NOT</strong>
     * executed on the application main thread. The client are
     * responsible for proper synchronization.
     *
     * @param event The received event.
     */
    public void onAccessibilityEvent(AccessibilityEvent event) {
        /* hook - do nothing */
    }

    /**
     * Callback for requests to stop feedback.
     *
     * <strong>Note:</strong> This method is <strong>NOT</strong>
     * executed on the application main thread. The client are
     * responsible for proper synchronization.
     */
    public void onInterrupt() {
        /* hook - do nothing */
    }

    /**
     * Connects this service.
     *
     * @throws IllegalStateException If already connected.
     */
    public void connect() {
        if (isConnected()) {
            throw new IllegalStateException("Already connected.");
        }

        // Serialize binder calls to a handler on a dedicated thread
        // different from the main since we expose APIs that block
        // the main thread waiting for a result the deliver of which
        // on the main thread will prevent that thread from waking up.
        // The serialization is needed also to ensure that events are
        // examined in delivery order. Otherwise, a fair locking
        // is needed for making sure the binder calls are interleaved
        // with check for the expected event and also to make sure the
        // binder threads are allowed to proceed in the received order.
        mHandlerThread = new HandlerThread("UiTestAutomationBridge");
        mHandlerThread.setDaemon(true);
        mHandlerThread.start();
        Looper looper = mHandlerThread.getLooper();

        mListener = new IAccessibilityServiceClientWrapper(null, looper, new Callbacks() {
            @Override
            public void onServiceConnected() {
                /* do nothing */
            }

            @Override
            public void onInterrupt() {
                UiTestAutomationBridge.this.onInterrupt();
            }

            @Override
            public void onAccessibilityEvent(AccessibilityEvent event) {
                synchronized (mLock) {
                    while (true) {
                        mLastEvent = AccessibilityEvent.obtain(event);
                        if (!mWaitingForEventDelivery) {
                            mLock.notifyAll();
                            break;
                        }
                        if (!mUnprocessedEventAvailable) {
                            mUnprocessedEventAvailable = true;
                            mLock.notifyAll();
                            break;
                        }
                        try {
                            mLock.wait();
                        } catch (InterruptedException ie) {
                            /* ignore */
                        }
                    }
                }
                UiTestAutomationBridge.this.onAccessibilityEvent(event);
            }

            @Override
            public void onSetConnectionId(int connectionId) {
                synchronized (mLock) {
                    mConnectionId = connectionId;
                    mLock.notifyAll();
                }
            }

            @Override
            public boolean onGesture(int gestureId) {
                return false;
            }
        });

        final IAccessibilityManager manager = IAccessibilityManager.Stub.asInterface(
                ServiceManager.getService(Context.ACCESSIBILITY_SERVICE));

        final AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags |= AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;

        try {
            manager.registerUiTestAutomationService(mListener, info);
        } catch (RemoteException re) {
            throw new IllegalStateException("Cound not register UiAutomationService.", re);
        }

        synchronized (mLock) {
            final long startTimeMillis = SystemClock.uptimeMillis();
            while (true) {
                if (isConnected()) {
                    return;
                }
                final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                final long remainingTimeMillis = TIMEOUT_REGISTER_SERVICE - elapsedTimeMillis;
                if (remainingTimeMillis <= 0) {
                    throw new IllegalStateException("Cound not register UiAutomationService.");
                }
                try {
                    mLock.wait(remainingTimeMillis);
                } catch (InterruptedException ie) {
                    /* ignore */
                }
            }
        }
    }

    /**
     * Disconnects this service.
     *
     * @throws IllegalStateException If already disconnected.
     */
    public void disconnect() {
        if (!isConnected()) {
            throw new IllegalStateException("Already disconnected.");
        }

        mHandlerThread.quit();

        IAccessibilityManager manager = IAccessibilityManager.Stub.asInterface(
              ServiceManager.getService(Context.ACCESSIBILITY_SERVICE));

        try {
            manager.unregisterUiTestAutomationService(mListener);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while unregistering UiTestAutomationService", re);
        }
    }

    /**
     * Gets whether this service is connected.
     *
     * @return True if connected.
     */
    public boolean isConnected() {
        return (mConnectionId != AccessibilityInteractionClient.NO_ID);
    }

    /**
     * Executes a command and waits for a specific accessibility event type up
     * to a given timeout.
     *
     * @param command The command to execute before starting to wait for the event.
     * @param predicate Predicate for recognizing the awaited event.
     * @param timeoutMillis The max wait time in milliseconds.
     */
    public AccessibilityEvent executeCommandAndWaitForAccessibilityEvent(Runnable command,
            Predicate<AccessibilityEvent> predicate, long timeoutMillis)
            throws TimeoutException, Exception {
        // TODO: This is broken - remove from here when finalizing this as public APIs.
        synchronized (mLock) {
            // Prepare to wait for an event.
            mWaitingForEventDelivery = true;
            mUnprocessedEventAvailable = false;
            if (mLastEvent != null) {
                mLastEvent.recycle();
                mLastEvent = null;
            }
            // Execute the command.
            command.run();
            // Wait for the event.
            final long startTimeMillis = SystemClock.uptimeMillis();
            while (true) {
                // If the expected event is received, that's it.
                if ((mUnprocessedEventAvailable && predicate.apply(mLastEvent))) {
                    mWaitingForEventDelivery = false;
                    mUnprocessedEventAvailable = false;
                    mLock.notifyAll();
                    return mLastEvent;
                }
                // Ask for another event.
                mWaitingForEventDelivery = true;
                mUnprocessedEventAvailable = false;
                mLock.notifyAll();
                // Check if timed out and if not wait.
                final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                final long remainingTimeMillis = timeoutMillis - elapsedTimeMillis;
                if (remainingTimeMillis <= 0) {
                    mWaitingForEventDelivery = false;
                    mUnprocessedEventAvailable = false;
                    mLock.notifyAll();
                    throw new TimeoutException("Expacted event not received within: "
                            + timeoutMillis + " ms.");
                }
                try {
                    mLock.wait(remainingTimeMillis);
                } catch (InterruptedException ie) {
                    /* ignore */
                }
            }
        }
    }

    /**
     * Waits for the accessibility event stream to become idle, which is not to
     * have received a new accessibility event within <code>idleTimeout</code>,
     * and do so within a maximal global timeout as specified by
     * <code>globalTimeout</code>.
     *
     * @param idleTimeout The timeout between two event to consider the device idle.
     * @param globalTimeout The maximal global timeout in which to wait for idle.
     */
    public void waitForIdle(long idleTimeout, long globalTimeout) {
        final long startTimeMillis = SystemClock.uptimeMillis();
        long lastEventTime = (mLastEvent != null)
                ? mLastEvent.getEventTime() : SystemClock.uptimeMillis();
        synchronized (mLock) {
            while (true) {
                final long currentTimeMillis = SystemClock.uptimeMillis();
                final long sinceLastEventTimeMillis = currentTimeMillis - lastEventTime;
                if (sinceLastEventTimeMillis > idleTimeout) {
                    return;
                }
                if (mLastEvent != null) {
                    lastEventTime = mLastEvent.getEventTime();
                }
                final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                final long remainingTimeMillis = globalTimeout - elapsedTimeMillis;
                if (remainingTimeMillis <= 0) {
                    return;
                }
                try {
                     mLock.wait(idleTimeout);
                } catch (InterruptedException e) {
                     /* ignore */
                }
            }
        }
    }

    /**
     * Finds an {@link AccessibilityNodeInfo} by accessibility id in the active
     * window. The search is performed from the root node.
     *
     * @param accessibilityNodeId A unique view id or virtual descendant id for
     *     which to search.
     * @return The current window scale, where zero means a failure.
     */
    public AccessibilityNodeInfo findAccessibilityNodeInfoByAccessibilityIdInActiveWindow(
            long accessibilityNodeId) {
        return findAccessibilityNodeInfoByAccessibilityId(ACTIVE_WINDOW_ID, accessibilityNodeId);
    }

    /**
     * Finds an {@link AccessibilityNodeInfo} by accessibility id.
     *
     * @param accessibilityWindowId A unique window id. Use {@link #ACTIVE_WINDOW_ID} to query
     *     the currently active window.
     * @param accessibilityNodeId A unique view id or virtual descendant id for
     *     which to search.
     * @return The current window scale, where zero means a failure.
     */
    public AccessibilityNodeInfo findAccessibilityNodeInfoByAccessibilityId(
            int accessibilityWindowId, long accessibilityNodeId) {
        // Cache the id to avoid locking
        final int connectionId = mConnectionId;
        ensureValidConnection(connectionId);
        return AccessibilityInteractionClient.getInstance()
                .findAccessibilityNodeInfoByAccessibilityId(mConnectionId,
                        accessibilityWindowId, accessibilityNodeId,
                        FIND_ACCESSIBILITY_NODE_INFO_PREFETCH_FLAGS);
    }

    /**
     * Finds an {@link AccessibilityNodeInfo} by View id in the active
     * window. The search is performed from the root node.
     *
     * @param viewId The id of a View.
     * @return The current window scale, where zero means a failure.
     */
    public AccessibilityNodeInfo findAccessibilityNodeInfoByViewIdInActiveWindow(int viewId) {
        return findAccessibilityNodeInfoByViewId(ACTIVE_WINDOW_ID, ROOT_NODE_ID, viewId);
    }

    /**
     * Finds an {@link AccessibilityNodeInfo} by View id. The search is performed in
     * the window whose id is specified and starts from the node whose accessibility
     * id is specified.
     *
     * @param accessibilityWindowId A unique window id. Use
     *     {@link  #ACTIVE_WINDOW_ID} to query the currently active window.
     * @param accessibilityNodeId A unique view id or virtual descendant id from
     *     where to start the search. Use {@link  #ROOT_NODE_ID} to start from the root.
     * @param viewId The id of a View.
     * @return The current window scale, where zero means a failure.
     */
    public AccessibilityNodeInfo findAccessibilityNodeInfoByViewId(int accessibilityWindowId,
            long accessibilityNodeId, int viewId) {
        // Cache the id to avoid locking
        final int connectionId = mConnectionId;
        ensureValidConnection(connectionId);
        return AccessibilityInteractionClient.getInstance()
                .findAccessibilityNodeInfoByViewId(connectionId, accessibilityWindowId,
                        accessibilityNodeId, viewId);
    }

    /**
     * Finds {@link AccessibilityNodeInfo}s by View text in the active
     * window. The search is performed from the root node.
     *
     * @param text The searched text.
     * @return The current window scale, where zero means a failure.
     */
    public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByTextInActiveWindow(String text) {
        return findAccessibilityNodeInfosByText(ACTIVE_WINDOW_ID, ROOT_NODE_ID, text);
    }

    /**
     * Finds {@link AccessibilityNodeInfo}s by View text. The match is case
     * insensitive containment. The search is performed in the window whose
     * id is specified and starts from the node whose accessibility id is
     * specified.
     *
     * @param accessibilityWindowId A unique window id. Use
     *     {@link #ACTIVE_WINDOW_ID} to query the currently active window.
     * @param accessibilityNodeId A unique view id or virtual descendant id from
     *     where to start the search. Use {@link #ROOT_NODE_ID} to start from the root.
     * @param text The searched text.
     * @return The current window scale, where zero means a failure.
     */
    public List<AccessibilityNodeInfo> findAccessibilityNodeInfosByText(int accessibilityWindowId,
            long accessibilityNodeId, String text) {
        // Cache the id to avoid locking
        final int connectionId = mConnectionId;
        ensureValidConnection(connectionId);
        return AccessibilityInteractionClient.getInstance()
                .findAccessibilityNodeInfosByText(connectionId, accessibilityWindowId,
                        accessibilityNodeId, text);
    }

    /**
     * Performs an accessibility action on an {@link AccessibilityNodeInfo}
     * in the active window.
     *
     * @param accessibilityNodeId A unique node id (accessibility and virtual descendant id).
     * @param action The action to perform.
     * @param arguments Optional action arguments.
     * @return Whether the action was performed.
     */
    public boolean performAccessibilityActionInActiveWindow(long accessibilityNodeId, int action,
            Bundle arguments) {
        return performAccessibilityAction(ACTIVE_WINDOW_ID, accessibilityNodeId, action, arguments);
    }

    /**
     * Performs an accessibility action on an {@link AccessibilityNodeInfo}.
     *
     * @param accessibilityWindowId A unique window id. Use
     *     {@link #ACTIVE_WINDOW_ID} to query the currently active window.
     * @param accessibilityNodeId A unique node id (accessibility and virtual descendant id).
     * @param action The action to perform.
     * @param arguments Optional action arguments.
     * @return Whether the action was performed.
     */
    public boolean performAccessibilityAction(int accessibilityWindowId, long accessibilityNodeId,
            int action, Bundle arguments) {
        // Cache the id to avoid locking
        final int connectionId = mConnectionId;
        ensureValidConnection(connectionId);
        return AccessibilityInteractionClient.getInstance().performAccessibilityAction(connectionId,
                accessibilityWindowId, accessibilityNodeId, action, arguments);
    }

    /**
     * Gets the root {@link AccessibilityNodeInfo} in the active window.
     *
     * @return The root info.
     */
    public AccessibilityNodeInfo getRootAccessibilityNodeInfoInActiveWindow() {
        // Cache the id to avoid locking
        final int connectionId = mConnectionId;
        ensureValidConnection(connectionId);
        return AccessibilityInteractionClient.getInstance()
                .findAccessibilityNodeInfoByAccessibilityId(connectionId, ACTIVE_WINDOW_ID,
                        ROOT_NODE_ID, AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS);
    }

    private void ensureValidConnection(int connectionId) {
        if (connectionId == UNDEFINED) {
            throw new IllegalStateException("UiAutomationService not connected."
                    + " Did you call #register()?");
        }
    }
}
