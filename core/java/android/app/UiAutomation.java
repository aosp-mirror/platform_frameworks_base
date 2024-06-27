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

import static android.view.Display.DEFAULT_DISPLAY;

import android.accessibilityservice.AccessibilityGestureEvent;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityService.Callbacks;
import android.accessibilityservice.AccessibilityService.IAccessibilityServiceClientWrapper;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.accessibilityservice.IAccessibilityServiceConnection;
import android.accessibilityservice.MagnificationConfig;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.Window;
import android.view.WindowAnimationFrameStats;
import android.view.WindowContentFrameStats;
import android.view.accessibility.AccessibilityCache;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityInteractionClient;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.IAccessibilityInteractionConnection;
import android.view.inputmethod.EditorInfo;
import android.window.ScreenCapture;
import android.window.ScreenCapture.ScreenshotHardwareBuffer;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.inputmethod.IAccessibilityInputMethodSessionCallback;
import com.android.internal.inputmethod.RemoteAccessibilityInputConnection;
import com.android.internal.util.Preconditions;
import com.android.internal.util.function.pooled.PooledLambda;

import libcore.io.IoUtils;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
    private static final boolean VERBOSE = false;

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

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            ConnectionState.DISCONNECTED,
            ConnectionState.CONNECTING,
            ConnectionState.CONNECTED,
            ConnectionState.FAILED
    })
    private @interface ConnectionState {
        /** The initial state before {@link #connect} or after {@link #disconnect} is called. */
        int DISCONNECTED = 0;
        /**
         * The temporary state after {@link #connect} is called. Will transition to
         * {@link #CONNECTED} or {@link #FAILED} depending on whether {@link #connect} succeeds or
         * not.
         */
        int CONNECTING = 1;
        /** The state when {@link #connect} has succeeded. */
        int CONNECTED = 2;
        /** The state when {@link #connect} has failed. */
        int FAILED = 3;
    }

    /**
     * UiAutomation suppresses accessibility services by default. This flag specifies that
     * existing accessibility services should continue to run, and that new ones may start.
     * This flag is set when obtaining the UiAutomation from
     * {@link Instrumentation#getUiAutomation(int)}.
     */
    public static final int FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES = 0x00000001;

    /**
     * UiAutomation uses the accessibility subsystem by default. This flag provides an option to
     * eliminate the overhead of engaging the accessibility subsystem for tests that do not need to
     * interact with the user interface. Setting this flag disables methods that rely on
     * accessibility. This flag is set when obtaining the UiAutomation from
     * {@link Instrumentation#getUiAutomation(int)}.
     */
    public static final int FLAG_DONT_USE_ACCESSIBILITY = 0x00000002;

    /**
     * UiAutomation sets {@link AccessibilityServiceInfo#isAccessibilityTool()} true by default.
     * This flag provides the option to set this field false for tests exercising that property.
     *
     * @hide
     */
    @TestApi
    public static final int FLAG_NOT_ACCESSIBILITY_TOOL = 0x00000004;

    /**
     * Returned by {@link #getAdoptedShellPermissions} to indicate that all permissions have been
     * adopted using {@link #adoptShellPermissionIdentity}.
     *
     * @hide
     */
    @TestApi
    @NonNull
    public static final Set<String> ALL_PERMISSIONS = Set.of("_ALL_PERMISSIONS_");

    private final Object mLock = new Object();

    private final ArrayList<AccessibilityEvent> mEventQueue = new ArrayList<AccessibilityEvent>();

    private final Handler mLocalCallbackHandler;

    private final IUiAutomationConnection mUiAutomationConnection;

    private final int mDisplayId;

    private HandlerThread mRemoteCallbackThread;

    private IAccessibilityServiceClient mClient;

    private int mConnectionId = CONNECTION_ID_UNDEFINED;

    private OnAccessibilityEventListener mOnAccessibilityEventListener;

    private boolean mWaitingForEventDelivery;

    private long mLastEventTimeMillis;

    private @ConnectionState int mConnectionState = ConnectionState.DISCONNECTED;

    private boolean mIsDestroyed;

    private int mFlags;

    private int mGenerationId = 0;

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
     * layer on the thread of the provided context main looper and perform requests for privileged
     * operations on the provided connection, and filtering display-related features to the display
     * associated with the context (or the user running the test, on devices that
     * {@link UserManager#isVisibleBackgroundUsersSupported() support visible background users}).
     *
     * @param context the context associated with the automation
     * @param connection The connection for performing privileged operations.
     *
     * @hide
     */
    public UiAutomation(Context context, IUiAutomationConnection connection) {
        this(getDisplayId(context), context.getMainLooper(), connection);
    }

    /**
     * Creates a new instance that will handle callbacks from the accessibility
     * layer on the thread of the provided looper and perform requests for privileged
     * operations on the provided connection.
     *
     * @param looper The looper on which to execute accessibility callbacks.
     * @param connection The connection for performing privileged operations.
     *
     * @deprecated use {@link #UiAutomation(Context, IUiAutomationConnection)} instead
     *
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public UiAutomation(Looper looper, IUiAutomationConnection connection) {
        this(DEFAULT_DISPLAY, looper, connection);
        Log.w(LOG_TAG, "Created with deprecatead constructor, assumes DEFAULT_DISPLAY");
    }

    private UiAutomation(int displayId, Looper looper, IUiAutomationConnection connection) {
        Preconditions.checkArgument(looper != null, "Looper cannot be null!");
        Preconditions.checkArgument(connection != null, "Connection cannot be null!");

        mLocalCallbackHandler = new Handler(looper);
        mUiAutomationConnection = connection;
        mDisplayId = displayId;

        Log.i(LOG_TAG, "Initialized for user " + Process.myUserHandle().getIdentifier()
                + " on display " + mDisplayId);
    }

    /**
     * Connects this UiAutomation to the accessibility introspection APIs with default flags
     * and default timeout.
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void connect() {
        try {
            connectWithTimeout(0, CONNECT_TIMEOUT_MILLIS);
        } catch (TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Connects this UiAutomation to the accessibility introspection APIs with default timeout.
     *
     * @hide
     */
    public void connect(int flags) {
        try {
            connectWithTimeout(flags, CONNECT_TIMEOUT_MILLIS);
        } catch (TimeoutException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Connects this UiAutomation to the accessibility introspection APIs.
     *
     * @param flags Any flags to apply to the automation as it gets connected while
     *              {@link UiAutomation#FLAG_DONT_USE_ACCESSIBILITY} would keep the
     *              connection disconnected and not to register UiAutomation service.
     * @param timeoutMillis The wait timeout in milliseconds
     *
     * @throws IllegalStateException If the connection to the accessibility subsystem is already
     *            established.
     * @throws TimeoutException If not connected within the timeout
     * @hide
     */
    public void connectWithTimeout(int flags, long timeoutMillis) throws TimeoutException {
        if (DEBUG) {
            Log.d(LOG_TAG, "connectWithTimeout: user=" + Process.myUserHandle().getIdentifier()
                    + ", flags=" + DebugUtils.flagsToString(UiAutomation.class, "FLAG_", flags)
                    + ", timeout=" + timeoutMillis + "ms");
        }
        synchronized (mLock) {
            throwIfConnectedLocked();
            if (mConnectionState == ConnectionState.CONNECTING) {
                if (DEBUG) Log.d(LOG_TAG, "already connecting");
                return;
            }
            if (DEBUG) Log.d(LOG_TAG, "setting state to CONNECTING");
            mConnectionState = ConnectionState.CONNECTING;
            mRemoteCallbackThread = new HandlerThread("UiAutomation");
            mRemoteCallbackThread.start();
            // Increment the generation since we are about to interact with a new client
            mClient = new IAccessibilityServiceClientImpl(
                    mRemoteCallbackThread.getLooper(), ++mGenerationId);
        }

        try {
            // Calling out without a lock held.
            mUiAutomationConnection.connect(mClient, flags);
            mFlags = flags;
            // If UiAutomation is not allowed to use the accessibility subsystem, the
            // connection state should keep disconnected and not to start the client connection.
            if (!useAccessibility()) {
                if (DEBUG) Log.d(LOG_TAG, "setting state to DISCONNECTED");
                mConnectionState = ConnectionState.DISCONNECTED;
                return;
            }
        } catch (RemoteException re) {
            throw new RuntimeException("Error while connecting " + this, re);
        }

        synchronized (mLock) {
            final long startTimeMillis = SystemClock.uptimeMillis();
            while (true) {
                if (mConnectionState == ConnectionState.CONNECTED) {
                    break;
                }
                final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                final long remainingTimeMillis = timeoutMillis - elapsedTimeMillis;
                if (remainingTimeMillis <= 0) {
                    if (DEBUG) Log.d(LOG_TAG, "setting state to FAILED");
                    mConnectionState = ConnectionState.FAILED;
                    throw new TimeoutException("Timeout while connecting " + this);
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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void disconnect() {
        synchronized (mLock) {
            if (mConnectionState == ConnectionState.CONNECTING) {
                throw new IllegalStateException(
                        "Cannot call disconnect() while connecting " + this);
            }
            if (useAccessibility() && mConnectionState == ConnectionState.DISCONNECTED) {
                return;
            }
            mConnectionState = ConnectionState.DISCONNECTED;
            mConnectionId = CONNECTION_ID_UNDEFINED;
            // Increment the generation so we no longer interact with the existing client
            ++mGenerationId;
        }
        try {
            // Calling out without a lock held.
            mUiAutomationConnection.disconnect();
        } catch (RemoteException re) {
            throw new RuntimeException("Error while disconnecting " + this, re);
        } finally {
            if (mRemoteCallbackThread != null) {
                mRemoteCallbackThread.quit();
                mRemoteCallbackThread = null;
            }
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
     *
     * @throws IllegalStateException If the connection to the accessibility subsystem is not
     *            established.
     */
    public void setOnAccessibilityEventListener(OnAccessibilityEventListener listener) {
        synchronized (mLock) {
            throwIfNotConnectedLocked();
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
     * Clears the accessibility cache.
     *
     * @return {@code true} if the cache was cleared
     * @see AccessibilityService#clearCache()
     */
    public boolean clearCache() {
        final int connectionId;
        synchronized (mLock) {
            throwIfNotConnectedLocked();
            connectionId = mConnectionId;
        }
        final AccessibilityCache cache = AccessibilityInteractionClient.getCache(connectionId);
        if (cache == null) {
            return false;
        }
        cache.clear();
        return true;
    }

    /**
     * Checks if {@code node} is in the accessibility cache.
     *
     * @param node the node to check.
     * @return {@code true} if {@code node} is in the cache.
     * @hide
     * @see AccessibilityService#isNodeInCache(AccessibilityNodeInfo)
     */
    @TestApi
    public boolean isNodeInCache(@NonNull AccessibilityNodeInfo node) {
        final int connectionId;
        synchronized (mLock) {
            throwIfNotConnectedLocked();
            connectionId = mConnectionId;
        }
        final AccessibilityCache cache = AccessibilityInteractionClient.getCache(connectionId);
        if (cache == null) {
            return false;
        }
        return cache.isNodeInCache(node);
    }

    /**
     * Provides reference to the cache through a locked connection.
     *
     * @return the accessibility cache.
     * @hide
     */
    public @Nullable AccessibilityCache getCache() {
        final int connectionId;
        synchronized (mLock) {
            throwIfNotConnectedLocked();
            connectionId = mConnectionId;
        }
        return AccessibilityInteractionClient.getCache(connectionId);
    }

    /**
     * Adopt the permission identity of the shell UID for all permissions. This allows
     * you to call APIs protected permissions which normal apps cannot hold but are
     * granted to the shell UID. If you already adopted all shell permissions by calling
     * this method or {@link #adoptShellPermissionIdentity(String...)} a subsequent call will
     * replace any previous adoption. Note that your permission state becomes that of the shell UID
     * and it is not a combination of your and the shell UID permissions.
     * <p>
     * <strong>Note:<strong/> Calling this method adopts all shell permissions and overrides
     * any subset of adopted permissions via {@link #adoptShellPermissionIdentity(String...)}.
     *
     * @see #adoptShellPermissionIdentity(String...)
     * @see #dropShellPermissionIdentity()
     */
    public void adoptShellPermissionIdentity() {
        try {
            // Calling out without a lock held.
            mUiAutomationConnection.adoptShellPermissionIdentity(Process.myUid(), null);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Adopt the permission identity of the shell UID only for the provided permissions.
     * This allows you to call APIs protected permissions which normal apps cannot hold
     * but are granted to the shell UID. If you already adopted shell permissions by calling
     * this method, or {@link #adoptShellPermissionIdentity()} a subsequent call will replace any
     * previous adoption.
     * <p>
     * <strong>Note:<strong/> This method behave differently from
     * {@link #adoptShellPermissionIdentity()}. Only the listed permissions will use the shell
     * identity and other permissions will still check against the original UID
     *
     * @param permissions The permissions to adopt or <code>null</code> to adopt all.
     *
     * @see #adoptShellPermissionIdentity()
     * @see #dropShellPermissionIdentity()
     */
    public void adoptShellPermissionIdentity(@Nullable String... permissions) {
        try {
            // Calling out without a lock held.
            mUiAutomationConnection.adoptShellPermissionIdentity(Process.myUid(), permissions);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Drop the shell permission identity adopted by a previous call to
     * {@link #adoptShellPermissionIdentity()}. If you did not adopt the shell permission
     * identity this method would be a no-op.
     *
     * @see #adoptShellPermissionIdentity()
     */
    public void dropShellPermissionIdentity() {
        try {
            // Calling out without a lock held.
            mUiAutomationConnection.dropShellPermissionIdentity();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a list of adopted shell permissions using {@link #adoptShellPermissionIdentity},
     * returns and empty set if no permissions are adopted and {@link #ALL_PERMISSIONS} if all
     * permissions are adopted.
     *
     * @hide
     */
    @TestApi
    @NonNull
    public Set<String> getAdoptedShellPermissions() {
        try {
            final List<String> permissions = mUiAutomationConnection.getAdoptedShellPermissions();
            return permissions == null ? ALL_PERMISSIONS : new ArraySet<>(permissions);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Adds permission to be overridden to the given state. UiAutomation must be connected to
     * root user.
     *
     * @param uid The UID of the app whose permission will be overridden
     * @param permission The permission whose state will be overridden
     * @param result The state to override the permission to
     *
     * @see PackageManager#PERMISSION_GRANTED
     * @see PackageManager#PERMISSION_DENIED
     *
     * @hide
     */
    @TestApi
    @SuppressLint("UnflaggedApi")
    public void addOverridePermissionState(int uid, @NonNull String permission,
            @PackageManager.PermissionResult int result) {
        try {
            mUiAutomationConnection.addOverridePermissionState(uid, permission, result);
        } catch (RemoteException re) {
            re.rethrowFromSystemServer();
        }
    }

    /**
     * Removes overridden permission. UiAutomation must be connected to root user.
     *
     * @param uid The UID of the app whose permission is overridden
     * @param permission The permission whose state will no longer be overridden
     *
     * @hide
     */
    @TestApi
    @SuppressLint("UnflaggedApi")
    public void removeOverridePermissionState(int uid, @NonNull String permission) {
        try {
            mUiAutomationConnection.removeOverridePermissionState(uid, permission);
        } catch (RemoteException re) {
            re.rethrowFromSystemServer();
        }
    }

    /**
     * Clears all overridden permissions for the given UID. UiAutomation must be connected to
     * root user.
     *
     * @param uid The UID of the app whose permissions will no longer be overridden
     *
     * @hide
     */
    @TestApi
    @SuppressLint("UnflaggedApi")
    public void clearOverridePermissionStates(int uid) {
        try {
            mUiAutomationConnection.clearOverridePermissionStates(uid);
        } catch (RemoteException re) {
            re.rethrowFromSystemServer();
        }
    }

    /**
     * Clears all overridden permissions on the device. UiAutomation must be connected to root user.
     *
     * @hide
     */
    @TestApi
    @SuppressLint("UnflaggedApi")
    public void clearAllOverridePermissionStates() {
        try {
            mUiAutomationConnection.clearAllOverridePermissionStates();
        } catch (RemoteException re) {
            re.rethrowFromSystemServer();
        }
    }

    /**
     * Performs a global action. Such an action can be performed at any moment
     * regardless of the current application or user location in that application.
     * For example going back, going home, opening recents, etc.
     *
     * @param action The action to perform.
     * @return Whether the action was successfully performed.
     *
     * @throws IllegalStateException If the connection to the accessibility subsystem is not
     *            established.
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
     *              {@link AccessibilityNodeInfo#FOCUS_ACCESSIBILITY}.
     * @return The node info of the focused view or null.
     *
     * @throws IllegalStateException If the connection to the accessibility subsystem is not
     *            established.
     * @see AccessibilityNodeInfo#FOCUS_INPUT
     * @see AccessibilityNodeInfo#FOCUS_ACCESSIBILITY
     */
    public AccessibilityNodeInfo findFocus(int focus) {
        synchronized (mLock) {
            throwIfNotConnectedLocked();
        }
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
     * @throws IllegalStateException If the connection to the accessibility subsystem is not
     *            established.
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
     * @throws IllegalStateException If the connection to the accessibility subsystem is not
     *            established.
     * @see AccessibilityServiceInfo
     */
    public final void setServiceInfo(AccessibilityServiceInfo info) {
        final IAccessibilityServiceConnection connection;
        synchronized (mLock) {
            throwIfNotConnectedLocked();
            AccessibilityInteractionClient.getInstance().clearCache(mConnectionId);
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
     * Gets the windows on the screen associated with the {@link UiAutomation} context (usually the
     * {@link android.view.Display#DEFAULT_DISPLAY default display).
     *
     * <p>
     * This method returns only the windows that a sighted user can interact with, as opposed to
     * all windows.

     * <p>
     * For example, if there is a modal dialog shown and the user cannot touch
     * anything behind it, then only the modal window will be reported
     * (assuming it is the top one). For convenience the returned windows
     * are ordered in a descending layer order, which is the windows that
     * are higher in the Z-order are reported first.
     * <p>
     * <strong>Note:</strong> In order to access the windows you have to opt-in
     * to retrieve the interactive windows by setting the
     * {@link AccessibilityServiceInfo#FLAG_RETRIEVE_INTERACTIVE_WINDOWS} flag.
     *
     * @return The windows if there are windows such, otherwise an empty list.
     * @throws IllegalStateException If the connection to the accessibility subsystem is not
     *            established.
     */
    public List<AccessibilityWindowInfo> getWindows() {
        if (DEBUG) {
            Log.d(LOG_TAG, "getWindows(): returning windows for display " + mDisplayId);
        }
        final int connectionId;
        synchronized (mLock) {
            throwIfNotConnectedLocked();
            connectionId = mConnectionId;
        }
        // Calling out without a lock held.
        return AccessibilityInteractionClient.getInstance().getWindowsOnDisplay(connectionId,
                mDisplayId);
    }

    /**
     * Gets the windows on the screen of all displays. This method returns only the windows
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
     * @return The windows of all displays if there are windows and the service is can retrieve
     *         them, otherwise an empty list. The key of SparseArray is display ID.
     * @throws IllegalStateException If the connection to the accessibility subsystem is not
     *            established.
     */
    @NonNull
    public SparseArray<List<AccessibilityWindowInfo>> getWindowsOnAllDisplays() {
        final int connectionId;
        synchronized (mLock) {
            throwIfNotConnectedLocked();
            connectionId = mConnectionId;
        }
        // Calling out without a lock held.
        return AccessibilityInteractionClient.getInstance()
                .getWindowsOnAllDisplays(connectionId);
    }

    /**
     * Gets the root {@link AccessibilityNodeInfo} in the active window.
     *
     * @return The root info.
     * @throws IllegalStateException If the connection to the accessibility subsystem is not
     *            established.
     */
    public AccessibilityNodeInfo getRootInActiveWindow() {
        return getRootInActiveWindow(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID);
    }

    /**
     * Gets the root {@link AccessibilityNodeInfo} in the active window.
     *
     * @param prefetchingStrategy the prefetching strategy.
     * @return The root info.
     * @throws IllegalStateException If the connection to the accessibility subsystem is not
     * established.
     *
     * @hide
     */
    @Nullable
    public AccessibilityNodeInfo getRootInActiveWindow(
            @AccessibilityNodeInfo.PrefetchingStrategy int prefetchingStrategy) {
        final int connectionId;
        synchronized (mLock) {
            throwIfNotConnectedLocked();
            connectionId = mConnectionId;
        }
        // Calling out without a lock held.
        return AccessibilityInteractionClient.getInstance()
                .getRootInActiveWindow(connectionId, prefetchingStrategy);
    }

    /**
     * A method for injecting an arbitrary input event.
     *
     * This method waits for all window container animations and surface operations to complete.
     *
     * <p>
     * <strong>Note:</strong> It is caller's responsibility to recycle the event.
     * </p>
     *
     * @param event The event to inject.
     * @param sync Whether to inject the event synchronously.
     * @return Whether event injection succeeded.
     */
    public boolean injectInputEvent(InputEvent event, boolean sync) {
        return injectInputEvent(event, sync, true /* waitForAnimations */);
    }

    /**
     * A method for injecting an arbitrary input event, optionally waiting for window animations to
     * complete.
     * <p>
     * <strong>Note:</strong> It is caller's responsibility to recycle the event.
     * </p>
     *
     * @param event The event to inject.
     * @param sync  Whether to inject the event synchronously.
     * @param waitForAnimations Whether to wait for all window container animations and surface
     *   operations to complete.
     * @return Whether event injection succeeded.
     *
     * @hide
     */
    @TestApi
    public boolean injectInputEvent(@NonNull InputEvent event, boolean sync,
            boolean waitForAnimations) {
        try {
            if (DEBUG) {
                Log.i(LOG_TAG, "Injecting: " + event + " sync: " + sync + " waitForAnimations: "
                        + waitForAnimations);
            }
            // Calling out without a lock held.
            return mUiAutomationConnection.injectInputEvent(event, sync, waitForAnimations);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while injecting input event!", re);
        }
        return false;
    }

    /**
     * Injects an arbitrary {@link InputEvent} to the accessibility input filter, for use in testing
     * the accessibility input filter.
     *
     * Events injected to the input subsystem using the standard {@link #injectInputEvent} method
     * skip the accessibility input filter to avoid feedback loops.
     *
     * @hide
     */
    @TestApi
    public void injectInputEventToInputFilter(@NonNull InputEvent event) {
        try {
            mUiAutomationConnection.injectInputEventToInputFilter(event);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while injecting input event to input filter", re);
        }
    }

    /**
     * Sets the system settings values that control the scaling factor for animations. The scale
     * controls the animation playback speed for animations that respect these settings. Animations
     * that do not respect the settings values will not be affected by this function. A lower scale
     * value results in a faster speed. A value of <code>0</code> disables animations entirely. When
     * animations are disabled services receive window change events more quickly which can reduce
     * the potential by confusion by reducing the time during which windows are in transition.
     *
     * @see AccessibilityEvent#TYPE_WINDOWS_CHANGED
     * @see AccessibilityEvent#TYPE_WINDOW_STATE_CHANGED
     * @see android.provider.Settings.Global#WINDOW_ANIMATION_SCALE
     * @see android.provider.Settings.Global#TRANSITION_ANIMATION_SCALE
     * @see android.provider.Settings.Global#ANIMATOR_DURATION_SCALE
     * @param scale The scaling factor for all animations.
     */
    public void setAnimationScale(float scale) {
        final IAccessibilityServiceConnection connection =
                AccessibilityInteractionClient.getInstance().getConnection(mConnectionId);
        if (connection != null) {
            try {
                connection.setAnimationScale(scale);
            } catch (RemoteException re) {
                throw new RuntimeException(re);
            }
        }
    }

    /**
     * A request for WindowManagerService to wait until all animations have completed and input
     * information has been sent from WindowManager to native InputManager.
     *
     * @hide
     */
    @TestApi
    public void syncInputTransactions() {
        try {
            // Calling out without a lock held.
            mUiAutomationConnection.syncInputTransactions(true /* waitForAnimations */);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while syncing input transactions!", re);
        }
    }

    /**
     * A request for WindowManagerService to wait until all input information has been sent from
     * WindowManager to native InputManager and optionally wait for animations to complete.
     *
     * @param waitForAnimations Whether to wait for all window container animations and surface
     *   operations to complete.
     *
     * @hide
     */
    @TestApi
    public void syncInputTransactions(boolean waitForAnimations) {
        try {
            // Calling out without a lock held.
            mUiAutomationConnection.syncInputTransactions(waitForAnimations);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while syncing input transactions!", re);
        }
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
     *
     * @param command The command to execute.
     * @param filter Filter that recognizes the expected event.
     * @param timeoutMillis The wait timeout in milliseconds.
     *
     * @throws TimeoutException If the expected event is not received within the timeout.
     * @throws IllegalStateException If the connection to the accessibility subsystem is not
     *            established.
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
     * @throws IllegalStateException If the connection to the accessibility subsystem is not
     *            established.
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
        if (DEBUG) {
            Log.d(LOG_TAG, "Taking screenshot of display " + mDisplayId);
        }
        Display display = DisplayManagerGlobal.getInstance().getRealDisplay(mDisplayId);
        Point displaySize = new Point();
        display.getRealSize(displaySize);

        // Take the screenshot
        ScreenCapture.SynchronousScreenCaptureListener syncScreenCapture =
                ScreenCapture.createSyncCaptureListener();
        try {
            if (!mUiAutomationConnection.takeScreenshot(
                    new Rect(0, 0, displaySize.x, displaySize.y), syncScreenCapture)) {
                return null;
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while taking screenshot of display " + mDisplayId, re);
            return null;
        }

        final ScreenshotHardwareBuffer screenshotBuffer = syncScreenCapture.getBuffer();
        if (screenshotBuffer == null) {
            Log.e(LOG_TAG, "Failed to take screenshot for display=" + mDisplayId);
            return null;
        }
        Bitmap screenShot = screenshotBuffer.asBitmap();
        if (screenShot == null) {
            Log.e(LOG_TAG, "Failed to take screenshot for display=" + mDisplayId);
            return null;
        }
        Bitmap swBitmap;
        try (HardwareBuffer buffer = screenshotBuffer.getHardwareBuffer()) {
            swBitmap = screenShot.copy(Bitmap.Config.ARGB_8888, false);
        }
        screenShot.recycle();

        // Optimization
        swBitmap.setHasAlpha(false);
        return swBitmap;
    }

    /**
     * Used to capture a screenshot of a Window. This can return null in the following cases:
     * 1. Window content hasn't been layed out.
     * 2. Window doesn't have a valid SurfaceControl
     * 3. An error occurred in SurfaceFlinger when trying to take the screenshot.
     *
     * @param window Window to take a screenshot of
     *
     * @return The screenshot bitmap on success, null otherwise.
     */
    @Nullable
    public Bitmap takeScreenshot(@NonNull Window window) {
        if (window == null) {
            return null;
        }

        View decorView = window.peekDecorView();
        if (decorView == null) {
            return null;
        }

        ViewRootImpl viewRoot = decorView.getViewRootImpl();
        if (viewRoot == null) {
            return null;
        }

        SurfaceControl sc = viewRoot.getSurfaceControl();
        if (!sc.isValid()) {
            return null;
        }

        // Apply a sync transaction to ensure SurfaceFlinger is flushed before capturing a
        // screenshot.
        new SurfaceControl.Transaction().apply(true);
        ScreenCapture.SynchronousScreenCaptureListener syncScreenCapture =
                ScreenCapture.createSyncCaptureListener();
        try {
            if (!mUiAutomationConnection.takeSurfaceControlScreenshot(sc, syncScreenCapture)) {
                Log.e(LOG_TAG, "Failed to take screenshot for window=" + window);
                return null;
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error while taking screenshot!", re);
            return null;
        }
        ScreenCapture.ScreenshotHardwareBuffer captureBuffer = syncScreenCapture.getBuffer();
        if (captureBuffer == null) {
            Log.e(LOG_TAG, "Failed to take screenshot for window=" + window);
            return null;
        }
        Bitmap screenShot = captureBuffer.asBitmap();
        if (screenShot == null) {
            Log.e(LOG_TAG, "Failed to take screenshot for window=" + window);
            return null;
        }
        Bitmap swBitmap;
        try (HardwareBuffer buffer = captureBuffer.getHardwareBuffer()) {
            swBitmap = screenShot.copy(Bitmap.Config.ARGB_8888, false);
        }

        screenShot.recycle();
        return swBitmap;
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
     * @throws IllegalStateException If the connection to the accessibility subsystem is not
     *            established.
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
     * @throws IllegalStateException If the connection to the accessibility subsystem is not
     *            established.
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
     * @deprecated animation-frames are no-longer used. Use Shared
     *         <a href="https://perfetto.dev/docs/data-sources/frametimeline">FrameTimeline</a>
     *         jank metrics instead.
     */
    @Deprecated
    public void clearWindowAnimationFrameStats() {
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
     * @deprecated animation-frames are no-longer used.
     */
    @Deprecated
    public WindowAnimationFrameStats getWindowAnimationFrameStats() {
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
     *
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
     *
     * @param packageName The package to which to grant.
     * @param permission The permission to grant.
     * @throws SecurityException if unable to grant the permission.
     */
    public void grantRuntimePermissionAsUser(String packageName, String permission,
            UserHandle userHandle) {
        try {
            if (DEBUG) {
                Log.i(LOG_TAG, "Granting runtime permission (" + permission + ") to package "
                        + packageName + " on user " + userHandle);
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
     *
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
     *
     * @param packageName The package to which to grant.
     * @param permission The permission to grant.
     * @throws SecurityException if unable to revoke the permission.
     */
    public void revokeRuntimePermissionAsUser(String packageName, String permission,
            UserHandle userHandle) {
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
     *
     * @see #adoptShellPermissionIdentity()
     */
    public ParcelFileDescriptor executeShellCommand(String command) {
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
     */
    @SuppressLint("ArrayReturn") // For consistency with other APIs
    public @NonNull ParcelFileDescriptor[] executeShellCommandRw(@NonNull String command) {
        return executeShellCommandInternal(command, false /* includeStderr */);
    }

    /**
     * Executes a shell command. This method returns three file descriptors,
     * one that points to the standard output stream (element at index 0), one that points
     * to the standard input stream (element at index 1), and one points to
     * standard error stream (element at index 2). The command execution is similar
     * to running "adb shell <command>" from a host connected to the device.
     * <p>
     * <strong>Note:</strong> It is your responsibility to close the returned file
     * descriptors once you are done reading/writing.
     * </p>
     *
     * @param command The command to execute.
     * @return File descriptors (out, in, err) to the standard output/input/error streams.
     */
    @SuppressLint("ArrayReturn") // For consistency with other APIs
    public @NonNull ParcelFileDescriptor[] executeShellCommandRwe(@NonNull String command) {
        return executeShellCommandInternal(command, true /* includeStderr */);
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public int getDisplayId() {
        return mDisplayId;
    }

    private ParcelFileDescriptor[] executeShellCommandInternal(
            String command, boolean includeStderr) {
        warnIfBetterCommand(command);

        ParcelFileDescriptor source_read = null;
        ParcelFileDescriptor sink_read = null;

        ParcelFileDescriptor source_write = null;
        ParcelFileDescriptor sink_write = null;

        ParcelFileDescriptor stderr_source_read = null;
        ParcelFileDescriptor stderr_sink_read = null;

        try {
            ParcelFileDescriptor[] pipe_read = ParcelFileDescriptor.createPipe();
            source_read = pipe_read[0];
            sink_read = pipe_read[1];

            ParcelFileDescriptor[] pipe_write = ParcelFileDescriptor.createPipe();
            source_write = pipe_write[0];
            sink_write = pipe_write[1];

            if (includeStderr) {
                ParcelFileDescriptor[] stderr_read = ParcelFileDescriptor.createPipe();
                stderr_source_read = stderr_read[0];
                stderr_sink_read = stderr_read[1];
            }

            // Calling out without a lock held.
            mUiAutomationConnection.executeShellCommandWithStderr(
                    command, sink_read, source_write, stderr_sink_read);
        } catch (IOException ioe) {
            Log.e(LOG_TAG, "Error executing shell command!", ioe);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error executing shell command!", re);
        } finally {
            IoUtils.closeQuietly(sink_read);
            IoUtils.closeQuietly(source_write);
            IoUtils.closeQuietly(stderr_sink_read);
        }

        ParcelFileDescriptor[] result = new ParcelFileDescriptor[includeStderr ? 3 : 2];
        result[0] = source_read;
        result[1] = sink_write;
        if (includeStderr) {
            result[2] = stderr_source_read;
        }
        return result;
    }

    @Override
    public String toString() {
        final StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("UiAutomation@").append(Integer.toHexString(hashCode()));
        stringBuilder.append("[id=").append(mConnectionId);
        stringBuilder.append(", displayId=").append(mDisplayId);
        stringBuilder.append(", flags=").append(mFlags);
        stringBuilder.append("]");
        return stringBuilder.toString();
    }

    @GuardedBy("mLock")
    private void throwIfConnectedLocked() {
        if (mConnectionState == ConnectionState.CONNECTED) {
            throw new IllegalStateException("UiAutomation connected, " + this);
        }
    }

    @GuardedBy("mLock")
    private void throwIfNotConnectedLocked() {
        if (mConnectionState != ConnectionState.CONNECTED) {
            final String msg = useAccessibility()
                    ? "UiAutomation not connected, "
                    : "UiAutomation not connected: Accessibility-dependent method called with "
                            + "FLAG_DONT_USE_ACCESSIBILITY set, ";
            throw new IllegalStateException(msg + this);
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

    private boolean useAccessibility() {
        return (mFlags & UiAutomation.FLAG_DONT_USE_ACCESSIBILITY) == 0;
    }

    /**
     * Gets the display id associated with the UiAutomation context.
     *
     * <p><b>NOTE: </b> must be a static method because it's called from a constructor to call
     * another one.
     */
    private static int getDisplayId(Context context) {
        Preconditions.checkArgument(context != null, "Context cannot be null!");

        UserManager userManager = context.getSystemService(UserManager.class);
        // TODO(b/255426725): given that this is a temporary solution until a11y supports multiple
        // users, the display is only set on devices that support that
        if (!userManager.isVisibleBackgroundUsersSupported()) {
            return DEFAULT_DISPLAY;
        }

        int displayId = context.getDisplayId();
        if (displayId == Display.INVALID_DISPLAY) {
            // Shouldn't happen, but we better handle it
            Log.e(LOG_TAG, "UiAutomation created UI context with invalid display id, assuming it's"
                    + " running in the display assigned to the user");
            return getMainDisplayIdAssignedToUser(context, userManager);
        }

        if (displayId != DEFAULT_DISPLAY) {
            if (DEBUG) {
                Log.d(LOG_TAG, "getDisplayId(): returning context's display (" + displayId + ")");
            }
            // Context is explicitly setting the display, so we respect that...
            return displayId;
        }
        // ...otherwise, we need to get the display the test's user is running on
        int userDisplayId = getMainDisplayIdAssignedToUser(context, userManager);
        if (DEBUG) {
            Log.d(LOG_TAG, "getDisplayId(): returning user's display (" + userDisplayId + ")");
        }
        return userDisplayId;
    }

    private static int getMainDisplayIdAssignedToUser(Context context, UserManager userManager) {
        if (!userManager.isUserVisible()) {
            // Should also not happen, but ...
            Log.e(LOG_TAG, "User (" + context.getUserId() + ") is not visible, using "
                    + "DEFAULT_DISPLAY");
            return DEFAULT_DISPLAY;
        }
        return userManager.getMainDisplayIdAssignedToUser();
    }

    private class IAccessibilityServiceClientImpl extends IAccessibilityServiceClientWrapper {

        public IAccessibilityServiceClientImpl(Looper looper, int generationId) {
            super(/* context= */ null, looper, new Callbacks() {
                private final int mGenerationId = generationId;

                /**
                 * True if UiAutomation doesn't interact with this client anymore.
                 * Used by methods below to stop sending notifications or changing members
                 * of {@link UiAutomation}.
                 */
                private boolean isGenerationChangedLocked() {
                    return mGenerationId != UiAutomation.this.mGenerationId;
                }

                @Override
                public void init(int connectionId, IBinder windowToken) {
                    if (DEBUG) {
                        Log.d(LOG_TAG, "init(): connectionId=" + connectionId + ", windowToken="
                                + windowToken + ", user=" + Process.myUserHandle()
                                + ", UiAutomation.mDisplay=" + UiAutomation.this.mDisplayId
                                + ", mGenerationId=" + mGenerationId
                                + ", UiAutomation.mGenerationId="
                                + UiAutomation.this.mGenerationId);
                    }
                    synchronized (mLock) {
                        if (isGenerationChangedLocked()) {
                            if (DEBUG) {
                                Log.d(LOG_TAG, "init(): returning because generation id changed");
                            }
                            return;
                        }
                        if (DEBUG) Log.d(LOG_TAG, "setting state to CONNECTED");
                        mConnectionState = ConnectionState.CONNECTED;
                        mConnectionId = connectionId;
                        mLock.notifyAll();
                    }
                    if (Build.IS_DEBUGGABLE) {
                        Log.v(LOG_TAG, "Init " + UiAutomation.this);
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
                public void onSystemActionsChanged() {
                    /* do nothing */
                }

                @Override
                public void createImeSession(IAccessibilityInputMethodSessionCallback callback) {
                    /* do nothing */
                }

                @Override
                public void startInput(
                        @Nullable RemoteAccessibilityInputConnection inputConnection,
                        @NonNull EditorInfo editorInfo, boolean restarting) {
                }

                @Override
                public boolean onGesture(AccessibilityGestureEvent gestureEvent) {
                    /* do nothing */
                    return false;
                }

                public void onMotionEvent(MotionEvent event) {
                    /* do nothing */
                }

                @Override
                public void onTouchStateChanged(int displayId, int state) {
                    /* do nothing */
                }

                @Override
                public void onAccessibilityEvent(AccessibilityEvent event) {
                    if (VERBOSE) {
                        Log.v(LOG_TAG, "onAccessibilityEvent(" + Process.myUserHandle() + "): "
                                + event);
                    }

                    final OnAccessibilityEventListener listener;
                    synchronized (mLock) {
                        if (isGenerationChangedLocked()) {
                            if (VERBOSE) {
                                Log.v(LOG_TAG, "onAccessibilityEvent(): returning because "
                                        + "generation id changed (from "
                                        + UiAutomation.this.mGenerationId + " to "
                                        + mGenerationId + ")");
                            }
                            return;
                        }
                        // It is not guaranteed that the accessibility framework sends events by the
                        // order of event timestamp.
                        mLastEventTimeMillis = Math.max(mLastEventTimeMillis, event.getEventTime());
                        if (mWaitingForEventDelivery) {
                            mEventQueue.add(AccessibilityEvent.obtain(event));
                        }
                        mLock.notifyAll();
                        listener = mOnAccessibilityEventListener;
                    }
                    if (listener != null) {
                        // Calling out only without a lock held.
                        mLocalCallbackHandler.sendMessage(PooledLambda.obtainMessage(
                                OnAccessibilityEventListener::onAccessibilityEvent,
                                listener, AccessibilityEvent.obtain(event)));
                    }
                }

                @Override
                public boolean onKeyEvent(KeyEvent event) {
                    return false;
                }

                @Override
                public void onMagnificationChanged(int displayId, @NonNull Region region,
                        MagnificationConfig config) {
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
                public void onAccessibilityButtonClicked(int displayId) {
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
