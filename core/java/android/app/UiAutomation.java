/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.app;

import android.accessibilityservice.AccessibilityService.Callbacks;
import android.accessibilityservice.AccessibilityService.IAccessibilityServiceClientWrapper;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.accessibilityservice.IAccessibilityServiceConnection;
import android.annotation.NonNull;
import android.annotation.TestApi;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.view.Display;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.WindowAnimationFrameStats;
import android.view.WindowContentFrameStats;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityInteractionClient;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.IAccessibilityInteractionConnection;

import com.android.internal.util.function.pooled.PooledLambda;
import libcore.io.IoUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Class for interacting with the device's UI by simulation user actions and
 * introspection of the screen content. It relies on the platform accessibility
 * APIs to introspect the screen and to perform some actions on the remote view
 * tree. It also allows injecting of arbitrary raw input events simulating user
 * interaction with keyboards and touch devices. One can think of a UiAutomation
 * as a special type of {@link android.accessibilityservice.AccessibilityService}
 * which does not provide hooks for the service life cycle and exposes other
 * APIs that are useful for UI test automation.
 * <p>
 * The APIs exposed by this class are low-level to maximize flexibility when
 * developing UI test automation tools and libraries. Generally, a UiAutomation
 * client should be using a higher-level library or implement high-level functions.
 * For example, performing a tap on the screen requires construction and injecting
 * of a touch down and up events which have to be delivered to the system by a
 * call to {@link #injectInputEvent(InputEvent, boolean)}.
 * </p>
 * <p>
 * The APIs exposed by this class operate across applications enabling a client
 * to write tests that cover use cases spanning over multiple applications. For
 * example, going to the settings application to change a setting and then
 * interacting with another application whose behavior depends on that setting.
 * </p>
 */
public final class UiAutomation {

    private static final String LOG_TAG = UiAutomation.class.getSimpleName();

    private static final boolean DEBUG = false;

    private static final int CONNECTION_ID_UNDEFINED = -1;

    private static final long CONNECT_TIMEOUT_MILLIS = 5000;

    /** Rotation constant: Unfreeze rotation (rotating the device changes its rotation state). */
    public static final int ROTATION_UNFREEZE = -2;

    /** Rotation constant: Freeze rotation to its current state. */
    public static final int ROTATION_FREEZE_CURRENT = -1;

    /** Rotation constant: Freeze rotation to 0 degrees (natural orientation) */
    public static final int ROTATION_FREEZE_0 = Surface.ROTATION_0;

    /** Rotation constant: Freeze rotation to 90 degrees . */
    public static final int ROTATION_FREEZE_90 = Surface.ROTATION_90;

    /** Rotation constant: Freeze rotation to 180 degrees . */
    public static final int ROTATION_FREEZE_180 = Surface.ROTATION_180;

    /** Rotation constant: Freeze rotation to 270 degrees . */
    public static final int ROTATION_FREEZE_270 = Surface.ROTATION_270;

    /**
     * UiAutomation supresses accessibility services by default. This flag specifies that
     * existing accessibility services should continue to run, and that new ones may start.
     * This flag is set when obtaining the UiAutomation from
     * {@link Instrumentation#getUiAutomation(int)}.
     */
    public static final int FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES = 0x00000001;

    private final Object mLock = new Object();

    private final ArrayList<AccessibilityEvent> mEventQueue = new ArrayList<AccessibilityEvent>();

    private final Handler mLocalCallbackHandler;

    private final IUiAutomationConnection mUiAutomationConnection;

    private HandlerThread mRemoteCallbackThread;

    private IAccessibilityServiceClient mClient;

    private int mConnectionId = CONNECTION_ID_UNDEFINED;

    private OnAccessibilityEventListener mOnAccessibilityEventListener;

    private boolean mWaitingForEventDelivery;

    private long mLastEventTimeMillis;

    private boolean mIsConnecting;

    private boolean mIsDestroyed;

    private int mFlags;

    /**
     * Listener for observing the {@link AccessibilityEvent} stream.
     */
    public static interface OnAccessibilityEventListener {

        /**
         * Callback for receiving an {@link AccessibilityEvent}.
         * <p>
         * <strong>Note:</strong> This method is <strong>NOT</strong> executed
         * on the main test thread. The client is responsible for proper
         * synchronization.
         * </p>
         * <p>
         * <strong>Note:</strong> It is responsibility of the client
         * to recycle the received events to minimize object creation.
         * </p>
         *
         * @param event The received event.
         */
        public void onAccessibilityEvent(AccessibilityEvent event);
    }

    /**
     * Listener for filtering accessibility events.
     */
    public static interface AccessibilityEventFilter {

        /**
         * Callback for determining whether an event is accepted or
         * it is filtered out.
         *
         * @param event The event to process.
         * @return True if the event is accepted, false to filter it out.
         */
        public boolean accept(AccessibilityEvent event);
    }

    /**
     * Creates a new instance that will handle callbacks from the accessibility
     * layer on the thread of the provided looper and perform requests for privileged
     * operations on the provided connection.
     *
     * @param looper The looper on which to execute accessibility callbacks.
     * @param connection The connection for performing privileged operations.
     *
     * @hide
     */
    public UiAutomation(Looper looper, IUiAutomationConnection connection) {
        if (looper == null) {
            throw new IllegalArgumentException("Looper cannot be null!");
        }
        if (connection == null) {
            throw new IllegalArgumentException("Connection cannot be null!");
        }
        mLocalCallbackHandler = new Handler(looper);
        mUiAutomationConnection = connection;
    }

    /**
     * Connects this UiAutomation to the accessibility introspection APIs with default flags.
     *
     * @hide
     */
    public void connect() {
        connect(0);
    }

    /**
     * Connects this UiAutomation to the accessibility introspection APIs.
     *
     * @param flags Any flags to apply to the automation as it gets connected
     *
     * @hide
     */
    public void connect(int flags) {
        synchronized (mLock) {
            throwIfConnectedLocked();
            if (mIsConnecting) {
                return;
            }
            mIsConnecting = true;
            mRemoteCallbackThread = new HandlerThread("UiAutomation");
            mRemoteCallbackThread.start();
            mClient = new IAccessibilityServiceClientImpl(mRemoteCallbackThread.getLooper());
        }

        try {
            // Calling out without a lock held.
            mUiAutomationConnection.connect(mClient, flags);
            mFlags = flags;
        } catch (RemoteException re) {
            throw new RuntimeException("Error while connecting UiAutomation", re);
        }

        synchronized (mLock) {
            final long startTimeMillis = SystemClock.uptimeMillis();
            try {
                while (true) {
                    if (isConnectedLocked()) {
                        break;
                    }
                    final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                    final long remainingTimeMillis = CONNECT_TIMEOUT_MILLIS - elapsedTimeMillis;
                    if (remainingTimeMillis <= 0) {
                        throw new RuntimeException("Error while connecting UiAutomation");
                    }
                    try {
                        mLock.wait(remainingTimeMillis);
                    } catch (InterruptedException ie) {
                        /* ignore */
                    }
                }
            } finally {
                mIsConnecting = false;
            }
        }
    }

    /**
     * Get the flags used to connect the service.
     *
     * @return The flags used to connect
     *
     * @hide
     */
    public int getFlags() {
        return mFlags;
    }

    /**
     * Disconnects this UiAutomation from the accessibility introspection APIs.
     *
     * @hide
     */
    public void disconnect() {
        synchronized (mLock) {
            if (mIsConnecting) {
                throw new IllegalStateException(
                        "Cannot call disconnect() while connecting!");
            }
            throwIfNotConnectedLocked();
            mConnectionId = CONNECTION_ID_UNDEFINED;
        }
        try {
            // Calling out without a lock held.
            mUiAutomationConnection.disconnect();
        } catch (RemoteException re) {
            throw new RuntimeException("Error while disconnecting UiAutomation", re);
        } finally {
            mRemoteCallbackThread.quit();
            mRemoteCallbackThread = null;
        }
    }

    /**
     * The id of the {@link IAccessibilityInteractionConnection} for querying
     * the screen content. This is here for legacy purposes since some tools use
     * hidden APIs to introspect the screen.
     *
     * @hide
     */
    public int getConnectionId() {
        synchronized (mLock) {
            throwIfNotConnectedLocked();
            return mConnectionId;
        }
    }

    /**
     * Reports if the object has been destroyed
     *
     * @return {code true} if the object has been destroyed.
     *
     * @hide
     */
    public boolean isDestroyed() {
        return mIsDestroyed;
    }

    /**
     * Sets a callback for observing the stream of {@link AccessibilityEvent}s.
     * The callbacks are delivered on the main application thread.
     *
     * @param listener The callback.
     */
    public void setOnAccessibilityEventListener(OnAccessibilityEventListener listener) {
        synchronized (mLock) {
            mOnAccessibilityEventListener = listener;
        }
    }

    /**
     * Destroy this UiAutomation. After calling this method, attempting to use the object will
     * result in errors.
     *
     * @hide
     */
    @TestApi
    public void destroy() {
        disconnect();
        mIsDestroyed = true;
    }

    /**
     * Performs a global action. Such an action can be performed at any moment
     * regardless of the current application or user location in that application.
     * For example going back, going home, opening recents, etc.
     *
     * @param action The action to perform.
     * @return Whether the action was successfully performed.
     *
     * @see android.accessibilityservice.AccessibilityService#GLOBAL_ACTION_BACK
     * @see android.accessibilityservice.AccessibilityService#GLOBAL_ACTION_HOME
     * @see android.accessibilityservice.AccessibilityService#GLOBAL_ACTION_NOTIFICATIONS
     * @see android.accessibilityservice.AccessibilityService#GLOBAL_ACTION_RECENTS
     */
    public final boolean performGlobalAction(int action) {
        final IAccessibilityServiceConnection connection;
        synchronized (mLock) {
            throwIfNotConnectedLocked();
            connection = AccessibilityInteractionClient.getInstance()
                    .getConnection(mConnectionId);
        }
        // Calling out without a lock held.
        if (connection != null) {
            try {
                return connection.performGlobalAction(action);
            } catch (RemoteException re) {
                Log.w(LOG_TAG, "Error while calling performGlobalAction", re);
            }
        }
        return false;
    }

    /**
     * Find the view that has the specified focus type. The search is performed
     * across all windows.
     * <p>
     * <strong>Note:</strong> In order to access the windows you have to opt-in
     * to retrieve the interactive windows by setting the
     * {@link AccessibilityServiceInfo#FLAG_RETRIEVE_INTERACTIVE_WINDOWS} flag.
     * Otherwise, the search will be performed only in the active window.
     * </p>
     *
     * @param focus The focus to find. One of {@link AccessibilityNodeInfo#FOCUS_INPUT} or
     *         {@link AccessibilityNodeInfo#FOCUS_ACCESSIBILITY}.
     * @return The node info of the focused view or null.
     *
     * @see AccessibilityNodeInfo#FOCUS_INPUT
     * @see AccessibilityNodeInfo#FOCUS_ACCESSIBILITY
     */
    public AccessibilityNodeInfo findFocus(int focus) {
        return AccessibilityInteractionClient.getInstance().findFocus(mConnectionId,
                AccessibilityWindowInfo.ANY_WINDOW_ID, AccessibilityNodeInfo.ROOT_NODE_ID, focus);
    }

    /**
     * Gets the an {@link AccessibilityServiceInfo} describing this UiAutomation.
     * This method is useful if one wants to change some of the dynamically
     * configurable properties at runtime.
     *
     * @return The accessibility service info.
     *
     * @see AccessibilityServiceInfo
     */
    public final AccessibilityServiceInfo getServiceInfo() {
        final IAccessibilityServiceConnection connection;
        synchronized (mLock) {
            throwIfNotConnectedLocked();
            connection = AccessibilityInteractionClient.getInstance()
                    .getConnection(mConnectionId);
        }
        // Calling out without a lock held.
        if (connection != null) {
            try {
                return connection.getServiceInfo();
            } catch (RemoteException re) {
                Log.w(LOG_TAG, "Error while getting AccessibilityServiceInfo", re);
            }
        }
        return null;
    }

    /**
     * Sets the {@link AccessibilityServiceInfo} that describes how this
     * UiAutomation will be handled by the platform accessibility layer.
     *
     * @param info The info.
     *
     * @see AccessibilityServiceInfo
     */
    public final void setServiceInfo(AccessibilityServiceInfo info) {
        final IAccessibilityServiceConnection connection;
        synchronized (mLock) {
            throwIfNotConnectedLocked();
            AccessibilityInteractionClient.getInstance().clearCache();
            connection = AccessibilityInteractionClient.getInstance()
                    .getConnection(mConnectionId);
        }
        // Calling out without a lock held.
        if (connection != null) {
            try {
                connection.setServiceInfo(info);
            } catch (RemoteException re) {
                Log.w(LOG_TAG, "Error while setting AccessibilityServiceInfo", re);
            }
        }
    }

    /**
     * Gets the windows on the screen. This method returns only the windows
     * that a sighted user can interact with, as opposed to all windows.
     * For example, if there is a modal dialog shown and the user cannot touch
     * anything behind it, then only the modal window will be reported
     * (assuming it is the top one). For convenience the returned windows
     * are ordered in a descending layer order, which is the windows that
     * are higher in the Z-order are reported first.
     * <p>
     * <strong>Note:</strong> In order to access the windows you have to opt-in
     * to retrieve the interactive windows by setting the
     * {@link AccessibilityServiceInfo#FLAG_RETRIEVE_INTERACTIVE_WINDOWS} flag.
     * </p>
     *
     * @return The windows if there are windows such, otherwise an empty list.
     */
    public List<AccessibilityWindowInfo> getWindows() {
        final int connectionId;
        synchronized (mLock) {
            throwIfNotConnectedLocked();
            connectionId = mConnectionId;
        }
        // Calling out without a lock held.
        return AccessibilityInteractionClient.getInstance()
                .getWindows(connectionId);
    }

    /**
     * Gets the root {@link AccessibilityNodeInfo} in the active window.
     *
     * @return The root info.
     */
    public AccessibilityNodeInfo getRootInActiveWindow() {
        final int connectionId;
        synchronized (mLock) {
            throwIfNotConnectedLocked();
            connectionId = mConnectionId;
        }
        // Calling out without a lock held.
        return AccessibilityInteractionClient.getInstance()
                .getRootInActiveWindow(connectionId);
    }

    /**
     * A method for injecting an arbitrary input event.
     * <p>
     * <strong>Note:</strong> It is caller's responsibility to recycle the event.
     * </p>
     * @param event The event to inject.
     * @param sync Whether to inject the event synchronously.
     * @return Whether event injection succeeded.
     */
    public boolean injectInputEvent(InputEvent event, boolean sync) {
        synchronized (mLock) {
            throwIfNotConnectedLocked();
        }
        try {
            if (DEBUG) {
                Log.i(LOG_TAG, "Injecting: " + event + " sync: " + sync);
            }
            // Calling out without a lock held.
            return mUiAutomationConnection.injectInputEvent(event, sync);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while injecting input event!", re);
        }
        return false;
    }

    /**
     * Sets the device rotation. A client can freeze the rotation in
     * desired state or freeze the rotation to its current state or
     * unfreeze the rotation (rotating the device changes its rotation
     * state).
     *
     * @param rotation The desired rotation.
     * @return Whether the rotation was set successfully.
     *
     * @see #ROTATION_FREEZE_0
     * @see #ROTATION_FREEZE_90
     * @see #ROTATION_FREEZE_180
     * @see #ROTATION_FREEZE_270
     * @see #ROTATION_FREEZE_CURRENT
     * @see #ROTATION_UNFREEZE
     */
    public boolean setRotation(int rotation) {
        synchronized (mLock) {
            throwIfNotConnectedLocked();
        }
        switch (rotation) {
            case ROTATION_FREEZE_0:
            case ROTATION_FREEZE_90:
            case ROTATION_FREEZE_180:
            case ROTATION_FREEZE_270:
            case ROTATION_UNFREEZE:
            case ROTATION_FREEZE_CURRENT: {
                try {
                    // Calling out without a lock held.
                    mUiAutomationConnection.setRotation(rotation);
                    return true;
                } catch (RemoteException re) {
                    Log.e(LOG_TAG, "Error while setting rotation!", re);
                }
            } return false;
            default: {
                throw new IllegalArgumentException("Invalid rotation.");
            }
        }
    }

    /**
     * Executes a command and waits for a specific accessibility event up to a
     * given wait timeout. To detect a sequence of events one can implement a
     * filter that keeps track of seen events of the expected sequence and
     * returns true after the last event of that sequence is received.
     * <p>
     * <strong>Note:</strong> It is caller's responsibility to recycle the returned event.
     * </p>
     * @param command The command to execute.
     * @param filter Filter that recognizes the expected event.
     * @param timeoutMillis The wait timeout in milliseconds.
     *
     * @throws TimeoutException If the expected event is not received within the timeout.
     */
    public AccessibilityEvent executeAndWaitForEvent(Runnable command,
            AccessibilityEventFilter filter, long timeoutMillis) throws TimeoutException {
        // Acquire the lock and prepare for receiving events.
        synchronized (mLock) {
            throwIfNotConnectedLocked();
            mEventQueue.clear();
            // Prepare to wait for an event.
            mWaitingForEventDelivery = true;
        }

        // Note: We have to release the lock since calling out with this lock held
        // can bite. We will correctly filter out events from other interactions,
        // so starting to collect events before running the action is just fine.

        // We will ignore events from previous interactions.
        final long executionStartTimeMillis = SystemClock.uptimeMillis();
        // Execute the command *without* the lock being held.
        command.run();

        List<AccessibilityEvent> receivedEvents = new ArrayList<>();

        // Acquire the lock and wait for the event.
        try {
            // Wait for the event.
            final long startTimeMillis = SystemClock.uptimeMillis();
            while (true) {
                List<AccessibilityEvent> localEvents = new ArrayList<>();
                synchronized (mLock) {
                    localEvents.addAll(mEventQueue);
                    mEventQueue.clear();
                }
                // Drain the event queue
                while (!localEvents.isEmpty()) {
                    AccessibilityEvent event = localEvents.remove(0);
                    // Ignore events from previous interactions.
                    if (event.getEventTime() < executionStartTimeMillis) {
                        continue;
                    }
                    if (filter.accept(event)) {
                        return event;
                    }
                    receivedEvents.add(event);
                }
                // Check if timed out and if not wait.
                final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                final long remainingTimeMillis = timeoutMillis - elapsedTimeMillis;
                if (remainingTimeMillis <= 0) {
                    throw new TimeoutException("Expected event not received within: "
                            + timeoutMillis + " ms among: " + receivedEvents);
                }
                synchronized (mLock) {
                    if (mEventQueue.isEmpty()) {
                        try {
                            mLock.wait(remainingTimeMillis);
                        } catch (InterruptedException ie) {
                            /* ignore */
                        }
                    }
                }
            }
        } finally {
            int size = receivedEvents.size();
            for (int i = 0; i < size; i++) {
                receivedEvents.get(i).recycle();
            }

            synchronized (mLock) {
                mWaitingForEventDelivery = false;
                mEventQueue.clear();
                mLock.notifyAll();
            }
        }
    }

    /**
     * Waits for the accessibility event stream to become idle, which is not to
     * have received an accessibility event within <code>idleTimeoutMillis</code>.
     * The total time spent to wait for an idle accessibility event stream is bounded
     * by the <code>globalTimeoutMillis</code>.
     *
     * @param idleTimeoutMillis The timeout in milliseconds between two events
     *            to consider the device idle.
     * @param globalTimeoutMillis The maximal global timeout in milliseconds in
     *            which to wait for an idle state.
     *
     * @throws TimeoutException If no idle state was detected within
     *            <code>globalTimeoutMillis.</code>
     */
    public void waitForIdle(long idleTimeoutMillis, long globalTimeoutMillis)
            throws TimeoutException {
        synchronized (mLock) {
            throwIfNotConnectedLocked();

            final long startTimeMillis = SystemClock.uptimeMillis();
            if (mLastEventTimeMillis <= 0) {
                mLastEventTimeMillis = startTimeMillis;
            }

            while (true) {
                final long currentTimeMillis = SystemClock.uptimeMillis();
                // Did we get idle state within the global timeout?
                final long elapsedGlobalTimeMillis = currentTimeMillis - startTimeMillis;
                final long remainingGlobalTimeMillis =
                        globalTimeoutMillis - elapsedGlobalTimeMillis;
                if (remainingGlobalTimeMillis <= 0) {
                    throw new TimeoutException("No idle state with idle timeout: "
                            + idleTimeoutMillis + " within global timeout: "
                            + globalTimeoutMillis);
                }
                // Did we get an idle state within the idle timeout?
                final long elapsedIdleTimeMillis = currentTimeMillis - mLastEventTimeMillis;
                final long remainingIdleTimeMillis = idleTimeoutMillis - elapsedIdleTimeMillis;
                if (remainingIdleTimeMillis <= 0) {
                    return;
                }
                try {
                     mLock.wait(remainingIdleTimeMillis);
                } catch (InterruptedException ie) {
                     /* ignore */
                }
            }
        }
    }

    /**
     * Takes a screenshot.
     *
     * @return The screenshot bitmap on success, null otherwise.
     */
    public Bitmap takeScreenshot() {
        synchronized (mLock) {
            throwIfNotConnectedLocked();
        }
        Display display = DisplayManagerGlobal.getInstance()
                .getRealDisplay(Display.DEFAULT_DISPLAY);
        Point displaySize = new Point();
        display.getRealSize(displaySize);

        int rotation = display.getRotation();

        // Take the screenshot
        Bitmap screenShot = null;
        try {
            // Calling out without a lock held.
            screenShot = mUiAutomationConnection.takeScreenshot(
                    new Rect(0, 0, displaySize.x, displaySize.y), rotation);
            if (screenShot == null) {
                return null;
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while taking screnshot!", re);
            return null;
        }

        // Optimization
        screenShot.setHasAlpha(false);

        return screenShot;
    }

    /**
     * Sets whether this UiAutomation to run in a "monkey" mode. Applications can query whether
     * they are executed in a "monkey" mode, i.e. run by a test framework, and avoid doing
     * potentially undesirable actions such as calling 911 or posting on public forums etc.
     *
     * @param enable whether to run in a "monkey" mode or not. Default is not.
     * @see ActivityManager#isUserAMonkey()
     */
    public void setRunAsMonkey(boolean enable) {
        synchronized (mLock) {
            throwIfNotConnectedLocked();
        }
        try {
            ActivityManager.getService().setUserIsMonkey(enable);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while setting run as monkey!", re);
        }
    }

    /**
     * Clears the frame statistics for the content of a given window. These
     * statistics contain information about the most recently rendered content
     * frames.
     *
     * @param windowId The window id.
     * @return Whether the window is present and its frame statistics
     *         were cleared.
     *
     * @see android.view.WindowContentFrameStats
     * @see #getWindowContentFrameStats(int)
     * @see #getWindows()
     * @see AccessibilityWindowInfo#getId() AccessibilityWindowInfo.getId()
     */
    public boolean clearWindowContentFrameStats(int windowId) {
        synchronized (mLock) {
            throwIfNotConnectedLocked();
        }
        try {
            if (DEBUG) {
                Log.i(LOG_TAG, "Clearing content frame stats for window: " + windowId);
            }
            // Calling out without a lock held.
            return mUiAutomationConnection.clearWindowContentFrameStats(windowId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error clearing window content frame stats!", re);
        }
        return false;
    }

    /**
     * Gets the frame statistics for a given window. These statistics contain
     * information about the most recently rendered content frames.
     * <p>
     * A typical usage requires clearing the window frame statistics via {@link
     * #clearWindowContentFrameStats(int)} followed by an interaction with the UI and
     * finally getting the window frame statistics via calling this method.
     * </p>
     * <pre>
     * // Assume we have at least one window.
     * final int windowId = getWindows().get(0).getId();
     *
     * // Start with a clean slate.
     * uiAutimation.clearWindowContentFrameStats(windowId);
     *
     * // Do stuff with the UI.
     *
     * // Get the frame statistics.
     * WindowContentFrameStats stats = uiAutomation.getWindowContentFrameStats(windowId);
     * </pre>
     *
     * @param windowId The window id.
     * @return The window frame statistics, or null if the window is not present.
     *
     * @see android.view.WindowContentFrameStats
     * @see #clearWindowContentFrameStats(int)
     * @see #getWindows()
     * @see AccessibilityWindowInfo#getId() AccessibilityWindowInfo.getId()
     */
    public WindowContentFrameStats getWindowContentFrameStats(int windowId) {
        synchronized (mLock) {
            throwIfNotConnectedLocked();
        }
        try {
            if (DEBUG) {
                Log.i(LOG_TAG, "Getting content frame stats for window: " + windowId);
            }
            // Calling out without a lock held.
            return mUiAutomationConnection.getWindowContentFrameStats(windowId);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error getting window content frame stats!", re);
        }
        return null;
    }

    /**
     * Clears the window animation rendering statistics. These statistics contain
     * information about the most recently rendered window animation frames, i.e.
     * for window transition animations.
     *
     * @see android.view.WindowAnimationFrameStats
     * @see #getWindowAnimationFrameStats()
     * @see android.R.styleable#WindowAnimation
     */
    public void clearWindowAnimationFrameStats() {
        synchronized (mLock) {
            throwIfNotConnectedLocked();
        }
        try {
            if (DEBUG) {
                Log.i(LOG_TAG, "Clearing window animation frame stats");
            }
            // Calling out without a lock held.
            mUiAutomationConnection.clearWindowAnimationFrameStats();
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error clearing window animation frame stats!", re);
        }
    }

    /**
     * Gets the window animation frame statistics. These statistics contain
     * information about the most recently rendered window animation frames, i.e.
     * for window transition animations.
     *
     * <p>
     * A typical usage requires clearing the window animation frame statistics via
     * {@link #clearWindowAnimationFrameStats()} followed by an interaction that causes
     * a window transition which uses a window animation and finally getting the window
     * animation frame statistics by calling this method.
     * </p>
     * <pre>
     * // Start with a clean slate.
     * uiAutimation.clearWindowAnimationFrameStats();
     *
     * // Do stuff to trigger a window transition.
     *
     * // Get the frame statistics.
     * WindowAnimationFrameStats stats = uiAutomation.getWindowAnimationFrameStats();
     * </pre>
     *
     * @return The window animation frame statistics.
     *
     * @see android.view.WindowAnimationFrameStats
     * @see #clearWindowAnimationFrameStats()
     * @see android.R.styleable#WindowAnimation
     */
    public WindowAnimationFrameStats getWindowAnimationFrameStats() {
        synchronized (mLock) {
            throwIfNotConnectedLocked();
        }
        try {
            if (DEBUG) {
                Log.i(LOG_TAG, "Getting window animation frame stats");
            }
            // Calling out without a lock held.
            return mUiAutomationConnection.getWindowAnimationFrameStats();
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error getting window animation frame stats!", re);
        }
        return null;
    }

    /**
     * Grants a runtime permission to a package.
     * @param packageName The package to which to grant.
     * @param permission The permission to grant.
     * @throws SecurityException if unable to grant the permission.
     */
    public void grantRuntimePermission(String packageName, String permission) {
        grantRuntimePermissionAsUser(packageName, permission, android.os.Process.myUserHandle());
    }

    /**
     * @deprecated replaced by
     *             {@link #grantRuntimePermissionAsUser(String, String, UserHandle)}.
     * @hide
     */
    @Deprecated
    @TestApi
    public boolean grantRuntimePermission(String packageName, String permission,
            UserHandle userHandle) {
        grantRuntimePermissionAsUser(packageName, permission, userHandle);
        return true;
    }

    /**
     * Grants a runtime permission to a package for a user.
     * @param packageName The package to which to grant.
     * @param permission The permission to grant.
     * @throws SecurityException if unable to grant the permission.
     */
    public void grantRuntimePermissionAsUser(String packageName, String permission,
            UserHandle userHandle) {
        synchronized (mLock) {
            throwIfNotConnectedLocked();
        }
        try {
            if (DEBUG) {
                Log.i(LOG_TAG, "Granting runtime permission");
            }
            // Calling out without a lock held.
            mUiAutomationConnection.grantRuntimePermission(packageName,
                    permission, userHandle.getIdentifier());
        } catch (Exception e) {
            throw new SecurityException("Error granting runtime permission", e);
        }
    }

    /**
     * Revokes a runtime permission from a package.
     * @param packageName The package to which to grant.
     * @param permission The permission to grant.
     * @throws SecurityException if unable to revoke the permission.
     */
    public void revokeRuntimePermission(String packageName, String permission) {
        revokeRuntimePermissionAsUser(packageName, permission, android.os.Process.myUserHandle());
    }

    /**
     * @deprecated replaced by
     *             {@link #revokeRuntimePermissionAsUser(String, String, UserHandle)}.
     * @hide
     */
    @Deprecated
    @TestApi
    public boolean revokeRuntimePermission(String packageName, String permission,
            UserHandle userHandle) {
        revokeRuntimePermissionAsUser(packageName, permission, userHandle);
        return true;
    }

    /**
     * Revokes a runtime permission from a package.
     * @param packageName The package to which to grant.
     * @param permission The permission to grant.
     * @throws SecurityException if unable to revoke the permission.
     */
    public void revokeRuntimePermissionAsUser(String packageName, String permission,
            UserHandle userHandle) {
        synchronized (mLock) {
            throwIfNotConnectedLocked();
        }
        try {
            if (DEBUG) {
                Log.i(LOG_TAG, "Revoking runtime permission");
            }
            // Calling out without a lock held.
            mUiAutomationConnection.revokeRuntimePermission(packageName,
                    permission, userHandle.getIdentifier());
        } catch (Exception e) {
            throw new SecurityException("Error granting runtime permission", e);
        }
    }

    /**
     * Executes a shell command. This method returns a file descriptor that points
     * to the standard output stream. The command execution is similar to running
     * "adb shell <command>" from a host connected to the device.
     * <p>
     * <strong>Note:</strong> It is your responsibility to close the returned file
     * descriptor once you are done reading.
     * </p>
     *
     * @param command The command to execute.
     * @return A file descriptor to the standard output stream.
     */
    public ParcelFileDescriptor executeShellCommand(String command) {
        synchronized (mLock) {
            throwIfNotConnectedLocked();
        }
        warnIfBetterCommand(command);

        ParcelFileDescriptor source = null;
        ParcelFileDescriptor sink = null;

        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            source = pipe[0];
            sink = pipe[1];

            // Calling out without a lock held.
            mUiAutomationConnection.executeShellCommand(command, sink, null);
        } catch (IOException ioe) {
            Log.e(LOG_TAG, "Error executing shell command!", ioe);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error executing shell command!", re);
        } finally {
            IoUtils.closeQuietly(sink);
        }

        return source;
    }

    /**
     * Executes a shell command. This method returns two file descriptors,
     * one that points to the standard output stream (element at index 0), and one that points
     * to the standard input stream (element at index 1). The command execution is similar
     * to running "adb shell <command>" from a host connected to the device.
     * <p>
     * <strong>Note:</strong> It is your responsibility to close the returned file
     * descriptors once you are done reading/writing.
     * </p>
     *
     * @param command The command to execute.
     * @return File descriptors (out, in) to the standard output/input streams.
     *
     * @hide
     */
    @TestApi
    public ParcelFileDescriptor[] executeShellCommandRw(String command) {
        synchronized (mLock) {
            throwIfNotConnectedLocked();
        }
        warnIfBetterCommand(command);

        ParcelFileDescriptor source_read = null;
        ParcelFileDescriptor sink_read = null;

        ParcelFileDescriptor source_write = null;
        ParcelFileDescriptor sink_write = null;

        try {
            ParcelFileDescriptor[] pipe_read = ParcelFileDescriptor.createPipe();
            source_read = pipe_read[0];
            sink_read = pipe_read[1];

            ParcelFileDescriptor[] pipe_write = ParcelFileDescriptor.createPipe();
            source_write = pipe_write[0];
            sink_write = pipe_write[1];

            // Calling out without a lock held.
            mUiAutomationConnection.executeShellCommand(command, sink_read, source_write);
        } catch (IOException ioe) {
            Log.e(LOG_TAG, "Error executing shell command!", ioe);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error executing shell command!", re);
        } finally {
            IoUtils.closeQuietly(sink_read);
            IoUtils.closeQuietly(source_write);
        }

        ParcelFileDescriptor[] result = new ParcelFileDescriptor[2];
        result[0] = source_read;
        result[1] = sink_write;
        return result;
    }

    private static float getDegreesForRotation(int value) {
        switch (value) {
            case Surface.ROTATION_90: {
                return 360f - 90f;
            }
            case Surface.ROTATION_180: {
                return 360f - 180f;
            }
            case Surface.ROTATION_270: {
                return 360f - 270f;
            } default: {
                return 0;
            }
        }
    }

    private boolean isConnectedLocked() {
        return mConnectionId != CONNECTION_ID_UNDEFINED;
    }

    private void throwIfConnectedLocked() {
        if (mConnectionId != CONNECTION_ID_UNDEFINED) {
            throw new IllegalStateException("UiAutomation not connected!");
        }
    }

    private void throwIfNotConnectedLocked() {
        if (!isConnectedLocked()) {
            throw new IllegalStateException("UiAutomation not connected!");
        }
    }

    private void warnIfBetterCommand(String cmd) {
        if (cmd.startsWith("pm grant ")) {
            Log.w(LOG_TAG, "UiAutomation.grantRuntimePermission() "
                    + "is more robust and should be used instead of 'pm grant'");
        } else if (cmd.startsWith("pm revoke ")) {
            Log.w(LOG_TAG, "UiAutomation.revokeRuntimePermission() "
                    + "is more robust and should be used instead of 'pm revoke'");
        }
    }

    private class IAccessibilityServiceClientImpl extends IAccessibilityServiceClientWrapper {

        public IAccessibilityServiceClientImpl(Looper looper) {
            super(null, looper, new Callbacks() {
                @Override
                public void init(int connectionId, IBinder windowToken) {
                    synchronized (mLock) {
                        mConnectionId = connectionId;
                        mLock.notifyAll();
                    }
                }

                @Override
                public void onServiceConnected() {
                    /* do nothing */
                }

                @Override
                public void onInterrupt() {
                    /* do nothing */
                }

                @Override
                public boolean onGesture(int gestureId) {
                    /* do nothing */
                    return false;
                }

                @Override
                public void onAccessibilityEvent(AccessibilityEvent event) {
                    final OnAccessibilityEventListener listener;
                    synchronized (mLock) {
                        mLastEventTimeMillis = event.getEventTime();
                        if (mWaitingForEventDelivery) {
                            mEventQueue.add(AccessibilityEvent.obtain(event));
                        }
                        mLock.notifyAll();
                        listener = mOnAccessibilityEventListener;
                    }
                    if (listener != null) {
                        // Calling out only without a lock held.
                        mLocalCallbackHandler.post(PooledLambda.obtainRunnable(
                                OnAccessibilityEventListener::onAccessibilityEvent,
                                listener, AccessibilityEvent.obtain(event))
                                .recycleOnUse());
                    }
                }

                @Override
                public boolean onKeyEvent(KeyEvent event) {
                    return false;
                }

                @Override
                public void onMagnificationChanged(@NonNull Region region,
                        float scale, float centerX, float centerY) {
                    /* do nothing */
                }

                @Override
                public void onSoftKeyboardShowModeChanged(int showMode) {
                    /* do nothing */
                }

                @Override
                public void onPerformGestureResult(int sequence, boolean completedSuccessfully) {
                    /* do nothing */
                }

                @Override
                public void onFingerprintCapturingGesturesChanged(boolean active) {
                    /* do nothing */
                }

                @Override
                public void onFingerprintGesture(int gesture) {
                    /* do nothing */
                }

                @Override
                public void onAccessibilityButtonClicked() {
                    /* do nothing */
                }

                @Override
                public void onAccessibilityButtonAvailabilityChanged(boolean available) {
                    /* do nothing */
                }
            });
        }
    }
}
