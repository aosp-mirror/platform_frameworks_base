/*
 ** Copyright 2017, The Android Open Source Project
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

package com.android.server.accessibility;

import static android.accessibilityservice.AccessibilityService.ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS;
import static android.accessibilityservice.AccessibilityService.KEY_ACCESSIBILITY_SCREENSHOT_COLORSPACE;
import static android.accessibilityservice.AccessibilityService.KEY_ACCESSIBILITY_SCREENSHOT_HARDWAREBUFFER;
import static android.accessibilityservice.AccessibilityService.KEY_ACCESSIBILITY_SCREENSHOT_STATUS;
import static android.accessibilityservice.AccessibilityService.KEY_ACCESSIBILITY_SCREENSHOT_TIMESTAMP;
import static android.accessibilityservice.AccessibilityServiceInfo.DEFAULT;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_ACCESSIBILITY_INTERACTION_CONNECTION;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_ACCESSIBILITY_SERVICE_CLIENT;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_ACCESSIBILITY_SERVICE_CONNECTION;
import static android.accessibilityservice.AccessibilityTrace.FLAGS_WINDOW_MANAGER_INTERNAL;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
import static android.view.accessibility.AccessibilityInteractionClient.CALL_STACK;
import static android.view.accessibility.AccessibilityInteractionClient.IGNORE_CALL_STACK;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK;

import android.accessibilityservice.AccessibilityGestureEvent;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityTrace;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.accessibilityservice.IAccessibilityServiceConnection;
import android.accessibilityservice.MagnificationConfig;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.graphics.ParcelableColorSpace;
import android.graphics.Region;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.Trace;
import android.provider.Settings;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MagnificationSpec;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.view.accessibility.AccessibilityCache;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;
import android.view.inputmethod.EditorInfo;
import android.window.ScreenCapture;
import android.window.ScreenCapture.ScreenshotHardwareBuffer;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.compat.IPlatformCompat;
import com.android.internal.inputmethod.IAccessibilityInputMethodSession;
import com.android.internal.inputmethod.IAccessibilityInputMethodSessionCallback;
import com.android.internal.inputmethod.IRemoteAccessibilityInputConnection;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalServices;
import com.android.server.accessibility.AccessibilityWindowManager.RemoteAccessibilityConnection;
import com.android.server.accessibility.magnification.MagnificationProcessor;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * This class represents an accessibility client - either an AccessibilityService or a UiAutomation.
 * It is responsible for behavior common to both types of clients.
 */
abstract class AbstractAccessibilityServiceConnection extends IAccessibilityServiceConnection.Stub
        implements ServiceConnection, IBinder.DeathRecipient, KeyEventDispatcher.KeyEventFilter,
        FingerprintGestureDispatcher.FingerprintGestureClient {
    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "AbstractAccessibilityServiceConnection";
    private static final String TRACE_SVC_CONN = LOG_TAG + ".IAccessibilityServiceConnection";
    private static final String TRACE_SVC_CLIENT = LOG_TAG + ".IAccessibilityServiceClient";
    private static final String TRACE_WM = "WindowManagerInternal";
    private static final int WAIT_WINDOWS_TIMEOUT_MILLIS = 5000;

    /** Display type for displays associated with the default user of the device. */
    public static final int DISPLAY_TYPE_DEFAULT = 1 << 0;
    /** Display type for displays associated with an AccessibilityDisplayProxy user. */
    public static final int DISPLAY_TYPE_PROXY = 1 << 1;

    protected static final String TAKE_SCREENSHOT = "takeScreenshot";
    protected final Context mContext;
    protected final SystemSupport mSystemSupport;
    protected final WindowManagerInternal mWindowManagerService;
    private final SystemActionPerformer mSystemActionPerformer;
    final AccessibilityWindowManager mA11yWindowManager;
    private final DisplayManager mDisplayManager;
    private final PowerManager mPowerManager;
    private final IPlatformCompat mIPlatformCompat;

    private final Handler mMainHandler;

    // Handler for scheduling method invocations on the main thread.
    public final InvocationHandler mInvocationHandler;

    final int mId;

    protected final AccessibilityServiceInfo mAccessibilityServiceInfo;

    // Lock must match the one used by AccessibilityManagerService
    protected final Object mLock;

    protected final AccessibilitySecurityPolicy mSecurityPolicy;
    protected final AccessibilityTrace mTrace;

    // The attribution tag set by the service that is bound to this instance
    protected String mAttributionTag;

    protected int mDisplayTypes = DISPLAY_TYPE_DEFAULT;

    // The service that's bound to this instance. Whenever this value is non-null, this
    // object is registered as a death recipient
    IBinder mService;

    IAccessibilityServiceClient mServiceInterface;

    int mEventTypes;

    int mFeedbackType;

    Set<String> mPackageNames = new HashSet<>();

    boolean mIsDefault;

    boolean mRequestTouchExplorationMode;

    private boolean mServiceHandlesDoubleTap;

    private boolean mRequestMultiFingerGestures;

    private boolean mRequestTwoFingerPassthrough;

    private boolean mSendMotionEvents;

    private SparseArray<Boolean> mServiceDetectsGestures = new SparseArray<>(0);
    boolean mRequestFilterKeyEvents;

    boolean mRetrieveInteractiveWindows;

    boolean mCaptureFingerprintGestures;

    boolean mRequestAccessibilityButton;

    boolean mReceivedAccessibilityButtonCallbackSinceBind;

    boolean mLastAccessibilityButtonCallbackState;

    boolean mRequestImeApis;

    int mFetchFlags;

    long mNotificationTimeout;

    final ComponentName mComponentName;

    int mGenericMotionEventSources;

    // the events pending events to be dispatched to this service
    final SparseArray<AccessibilityEvent> mPendingEvents = new SparseArray<>();

    /** Whether this service relies on its {@link AccessibilityCache} being up to date */
    boolean mUsesAccessibilityCache = false;

    // Handler only for dispatching accessibility events since we use event
    // types as message types allowing us to remove messages per event type.
    public Handler mEventDispatchHandler;

    final SparseArray<IBinder> mOverlayWindowTokens = new SparseArray();

    /** The timestamp of requesting to take screenshot in milliseconds */
    private long mRequestTakeScreenshotTimestampMs;
    /**
     * The timestamps of requesting to take a window screenshot in milliseconds,
     * mapping from accessibility window id -> timestamp.
     */
    private SparseArray<Long> mRequestTakeScreenshotOfWindowTimestampMs = new SparseArray<>();

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "DISPLAY_TYPE_" }, value = {
            DISPLAY_TYPE_DEFAULT,
            DISPLAY_TYPE_PROXY
    })
    public @interface DisplayTypes {}

    public interface SystemSupport {
        /**
         * @return The current dispatcher for key events
         */
        @NonNull KeyEventDispatcher getKeyEventDispatcher();

        /**
         * @param displayId The display id.
         * @return The current injector of motion events used on the display, if one exists.
         */
        @Nullable MotionEventInjector getMotionEventInjectorForDisplayLocked(int displayId);

        /**
         * @return The current dispatcher for fingerprint gestures, if one exists
         */
        @Nullable FingerprintGestureDispatcher getFingerprintGestureDispatcher();

        /**
         * @return The magnification processor
         */
        @NonNull
        MagnificationProcessor getMagnificationProcessor();

        /**
         * Called back to notify system that the client has changed
         * @param serviceInfoChanged True if the service's AccessibilityServiceInfo changed.
         */
        void onClientChangeLocked(boolean serviceInfoChanged);

        /**
         * Called back to notify the system the proxy client for a device has changed.
         *
         * Changes include if the proxy is unregistered, if its service info list has changed, or if
         * its focus appearance has changed.
         */
        void onProxyChanged(int deviceId);

        int getCurrentUserIdLocked();

        Pair<float[], MagnificationSpec> getWindowTransformationMatrixAndMagnificationSpec(
                int windowId);

        boolean isAccessibilityButtonShown();

        /**
         * Persists the component names in the specified setting in a
         * colon separated fashion.
         *
         * @param settingName The setting name.
         * @param componentNames The component names.
         * @param userId The user id to persist the setting for.
         */
        void persistComponentNamesToSettingLocked(String settingName,
                Set<ComponentName> componentNames, int userId);

        /* This is exactly PendingIntent.getActivity, separated out for testability */
        PendingIntent getPendingIntentActivity(Context context, int requestCode, Intent intent,
                int flags);

        void setGestureDetectionPassthroughRegion(int displayId, Region region);

        void setTouchExplorationPassthroughRegion(int displayId, Region region);

        void setServiceDetectsGesturesEnabled(int displayId, boolean mode);

        void requestTouchExploration(int displayId);

        void requestDragging(int displayId, int pointerId);

        void requestDelegating(int displayId);

        void onDoubleTap(int displayId);

        void onDoubleTapAndHold(int displayId);

        void requestImeLocked(AbstractAccessibilityServiceConnection connection);

        void unbindImeLocked(AbstractAccessibilityServiceConnection connection);

        void attachAccessibilityOverlayToDisplay(int displayId, SurfaceControl sc);

    }

    public AbstractAccessibilityServiceConnection(Context context, ComponentName componentName,
            AccessibilityServiceInfo accessibilityServiceInfo, int id, Handler mainHandler,
            Object lock, AccessibilitySecurityPolicy securityPolicy, SystemSupport systemSupport,
            AccessibilityTrace trace, WindowManagerInternal windowManagerInternal,
            SystemActionPerformer systemActionPerfomer,
            AccessibilityWindowManager a11yWindowManager) {
        mContext = context;
        mWindowManagerService = windowManagerInternal;
        mId = id;
        mComponentName = componentName;
        mAccessibilityServiceInfo = accessibilityServiceInfo;
        mLock = lock;
        mSecurityPolicy = securityPolicy;
        mSystemActionPerformer = systemActionPerfomer;
        mSystemSupport = systemSupport;
        mTrace = trace;
        mMainHandler = mainHandler;
        mInvocationHandler = new InvocationHandler(mainHandler.getLooper());
        mA11yWindowManager = a11yWindowManager;
        mDisplayManager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        mIPlatformCompat = IPlatformCompat.Stub.asInterface(
                ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));
        mEventDispatchHandler = new Handler(mainHandler.getLooper()) {
            @Override
            public void handleMessage(Message message) {
                final int eventType =  message.what;
                AccessibilityEvent event = (AccessibilityEvent) message.obj;
                boolean serviceWantsEvent = message.arg1 != 0;
                notifyAccessibilityEventInternal(eventType, event, serviceWantsEvent);
            }
        };
        setDynamicallyConfigurableProperties(accessibilityServiceInfo);
    }

    @Override
    public boolean onKeyEvent(KeyEvent keyEvent, int sequenceNumber) {
        if (!mRequestFilterKeyEvents || (mServiceInterface == null)) {
            return false;
        }
        if((mAccessibilityServiceInfo.getCapabilities()
                & AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS) == 0) {
            return false;
        }
        if (!mSecurityPolicy.checkAccessibilityAccess(this)) {
            return false;
        }
        try {
            if (svcClientTracingEnabled()) {
                logTraceSvcClient("onKeyEvent", keyEvent + ", " + sequenceNumber);
            }
            mServiceInterface.onKeyEvent(keyEvent, sequenceNumber);
        } catch (RemoteException e) {
            return false;
        }
        return true;
    }

    public void setDynamicallyConfigurableProperties(AccessibilityServiceInfo info) {
        mEventTypes = info.eventTypes;
        mFeedbackType = info.feedbackType;
        String[] packageNames = info.packageNames;
        mPackageNames.clear();
        if (packageNames != null) {
            mPackageNames.addAll(Arrays.asList(packageNames));
        }
        mNotificationTimeout = info.notificationTimeout;
        mIsDefault = (info.flags & DEFAULT) != 0;
        mGenericMotionEventSources = info.getMotionEventSources();

        if (supportsFlagForNotImportantViews(info)) {
            if ((info.flags & AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS) != 0) {
                mFetchFlags |=
                        AccessibilityNodeInfo.FLAG_SERVICE_REQUESTS_INCLUDE_NOT_IMPORTANT_VIEWS;
            } else {
                mFetchFlags &=
                        ~AccessibilityNodeInfo.FLAG_SERVICE_REQUESTS_INCLUDE_NOT_IMPORTANT_VIEWS;
            }
        }

        if ((info.flags & AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS) != 0) {
            mFetchFlags |= AccessibilityNodeInfo.FLAG_SERVICE_REQUESTS_REPORT_VIEW_IDS;
        } else {
            mFetchFlags &= ~AccessibilityNodeInfo.FLAG_SERVICE_REQUESTS_REPORT_VIEW_IDS;
        }

        if (mAccessibilityServiceInfo.isAccessibilityTool()) {
            mFetchFlags |= AccessibilityNodeInfo.FLAG_SERVICE_IS_ACCESSIBILITY_TOOL;
        } else {
            mFetchFlags &= ~AccessibilityNodeInfo.FLAG_SERVICE_IS_ACCESSIBILITY_TOOL;
        }

        mRequestTouchExplorationMode = (info.flags
                & AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0;
        mServiceHandlesDoubleTap = (info.flags
                & AccessibilityServiceInfo.FLAG_SERVICE_HANDLES_DOUBLE_TAP) != 0;
        mRequestMultiFingerGestures = (info.flags
                & AccessibilityServiceInfo.FLAG_REQUEST_MULTI_FINGER_GESTURES) != 0;
        mRequestTwoFingerPassthrough =
                (info.flags & AccessibilityServiceInfo.FLAG_REQUEST_2_FINGER_PASSTHROUGH) != 0;
        mSendMotionEvents =
                (info.flags & AccessibilityServiceInfo.FLAG_SEND_MOTION_EVENTS) != 0;
        mRequestFilterKeyEvents =
                (info.flags & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0;
        mRetrieveInteractiveWindows = (info.flags
                & AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) != 0;
        mCaptureFingerprintGestures = (info.flags
                & AccessibilityServiceInfo.FLAG_REQUEST_FINGERPRINT_GESTURES) != 0;
        mRequestAccessibilityButton = (info.flags
                & AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON) != 0;
        mRequestImeApis = (info.flags
                & AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR) != 0;
    }

    protected boolean supportsFlagForNotImportantViews(AccessibilityServiceInfo info) {
        return info.getResolveInfo().serviceInfo.applicationInfo.targetSdkVersion
                >= Build.VERSION_CODES.JELLY_BEAN;
    }

    public boolean canReceiveEventsLocked() {
        return (mEventTypes != 0 && mService != null);
    }

    @Override
    public void setOnKeyEventResult(boolean handled, int sequence) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("setOnKeyEventResult", "handled=" + handled + ";sequence=" + sequence);
        }
        mSystemSupport.getKeyEventDispatcher().setOnKeyEventResult(this, handled, sequence);
    }

    @Override
    public AccessibilityServiceInfo getServiceInfo() {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("getServiceInfo", "");
        }
        synchronized (mLock) {
            return mAccessibilityServiceInfo;
        }
    }

    public int getCapabilities() {
        return mAccessibilityServiceInfo.getCapabilities();
    }

    int getRelevantEventTypes() {
        return (mUsesAccessibilityCache ? AccessibilityCache.CACHE_CRITICAL_EVENTS_MASK
                : AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) | mEventTypes;
    }

    @Override
    public void setServiceInfo(AccessibilityServiceInfo info) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("setServiceInfo", "info=" + info);
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                // If the XML manifest had data to configure the service its info
                // should be already set. In such a case update only the dynamically
                // configurable properties.
                boolean oldRequestIme = mRequestImeApis;
                AccessibilityServiceInfo oldInfo = mAccessibilityServiceInfo;
                if (oldInfo != null) {
                    oldInfo.updateDynamicallyConfigurableProperties(mIPlatformCompat, info);
                    setDynamicallyConfigurableProperties(oldInfo);
                } else {
                    setDynamicallyConfigurableProperties(info);
                }
                mSystemSupport.onClientChangeLocked(true);
                if (!oldRequestIme && mRequestImeApis) {
                    mSystemSupport.requestImeLocked(this);
                } else if (oldRequestIme && !mRequestImeApis) {
                    mSystemSupport.unbindImeLocked(this);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void setInstalledAndEnabledServices(List<AccessibilityServiceInfo> infos) {
        return;
    }

    @Override
    public List<AccessibilityServiceInfo> getInstalledAndEnabledServices() {
        return null;
    }

    @Override
    public void setAttributionTag(String attributionTag) {
        mAttributionTag = attributionTag;
    }

    String getAttributionTag() {
        return mAttributionTag;
    }

    protected abstract boolean hasRightsToCurrentUserLocked();

    @Nullable
    @Override
    public AccessibilityWindowInfo.WindowListSparseArray getWindows() {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("getWindows", "");
        }
        synchronized (mLock) {
            if (!hasRightsToCurrentUserLocked()) {
                return null;
            }
            final boolean permissionGranted =
                    mSecurityPolicy.canRetrieveWindowsLocked(this);
            if (!permissionGranted) {
                return null;
            }
            if (!mSecurityPolicy.checkAccessibilityAccess(this)) {
                return null;
            }
            final AccessibilityWindowInfo.WindowListSparseArray allWindows =
                    new AccessibilityWindowInfo.WindowListSparseArray();
            final ArrayList<Integer> displayList = mA11yWindowManager.getDisplayListLocked(
                    mDisplayTypes);
            final int displayListCounts = displayList.size();
            if (displayListCounts > 0) {
                for (int i = 0; i < displayListCounts; i++) {
                    final int displayId = displayList.get(i);
                    ensureWindowsAvailableTimedLocked(displayId);

                    final List<AccessibilityWindowInfo> windowList = getWindowsByDisplayLocked(
                            displayId);
                    if (windowList != null) {
                        allWindows.put(displayId, windowList);
                    }
                }
            }
            return allWindows;
        }
    }

    protected void setDisplayTypes(@DisplayTypes int displayTypes) {
        mDisplayTypes = displayTypes;
    }

    @Override
    public AccessibilityWindowInfo getWindow(int windowId) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("getWindow", "windowId=" + windowId);
        }
        synchronized (mLock) {
            int displayId = Display.INVALID_DISPLAY;
            if (windowId != AccessibilityWindowInfo.UNDEFINED_WINDOW_ID) {
                displayId = mA11yWindowManager.getDisplayIdByUserIdAndWindowIdLocked(
                        mSystemSupport.getCurrentUserIdLocked(), windowId);
            }
            ensureWindowsAvailableTimedLocked(displayId);

            if (!hasRightsToCurrentUserLocked()) {
                return null;
            }
            final boolean permissionGranted =
                    mSecurityPolicy.canRetrieveWindowsLocked(this);
            if (!permissionGranted) {
                return null;
            }
            if (!mSecurityPolicy.checkAccessibilityAccess(this)) {
                return null;
            }
            AccessibilityWindowInfo window =
                    mA11yWindowManager.findA11yWindowInfoByIdLocked(windowId);
            if (window != null) {
                AccessibilityWindowInfo windowClone = AccessibilityWindowInfo.obtain(window);
                windowClone.setConnectionId(mId);
                return windowClone;
            }
            return null;
        }
    }

    @Override
    public String[] findAccessibilityNodeInfosByViewId(int accessibilityWindowId,
            long accessibilityNodeId, String viewIdResName, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, long interrogatingTid)
            throws RemoteException {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("findAccessibilityNodeInfosByViewId",
                    "accessibilityWindowId=" + accessibilityWindowId + ";accessibilityNodeId="
                    + accessibilityNodeId + ";viewIdResName=" + viewIdResName + ";interactionId="
                    + interactionId + ";callback=" + callback + ";interrogatingTid="
                    + interrogatingTid);
        }
        final int resolvedWindowId;
        RemoteAccessibilityConnection connection;
        Region partialInteractiveRegion = Region.obtain();
        synchronized (mLock) {
            mUsesAccessibilityCache = true;
            if (!hasRightsToCurrentUserLocked()) {
                return null;
            }
            resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
            final boolean permissionGranted =
                    mSecurityPolicy.canGetAccessibilityNodeInfoLocked(
                            mSystemSupport.getCurrentUserIdLocked(), this, resolvedWindowId);
            if (!permissionGranted) {
                return null;
            } else {
                connection = mA11yWindowManager.getConnectionLocked(
                        mSystemSupport.getCurrentUserIdLocked(), resolvedWindowId);
                if (connection == null) {
                    return null;
                }
            }
            if (!mA11yWindowManager.computePartialInteractiveRegionForWindowLocked(
                    resolvedWindowId, partialInteractiveRegion)) {
                partialInteractiveRegion.recycle();
                partialInteractiveRegion = null;
            }
        }
        final Pair<float[], MagnificationSpec> transformMatrixAndSpec =
                getWindowTransformationMatrixAndMagnificationSpec(resolvedWindowId);
        final float[] transformMatrix = transformMatrixAndSpec.first;
        final MagnificationSpec spec = transformMatrixAndSpec.second;
        if (!mSecurityPolicy.checkAccessibilityAccess(this)) {
            return null;
        }
        final int interrogatingPid = Binder.getCallingPid();
        callback = replaceCallbackIfNeeded(callback, resolvedWindowId, interactionId,
                interrogatingPid, interrogatingTid);
        final long identityToken = Binder.clearCallingIdentity();
        if (intConnTracingEnabled()) {
            logTraceIntConn("findAccessibilityNodeInfosByViewId",
                    accessibilityNodeId + ";" + viewIdResName + ";" + partialInteractiveRegion + ";"
                    + interactionId + ";" + callback + ";" + mFetchFlags + ";" + interrogatingPid
                    + ";" + interrogatingTid + ";" + spec + ";" + Arrays.toString(transformMatrix));
        }
        try {
            connection.getRemote().findAccessibilityNodeInfosByViewId(accessibilityNodeId,
                    viewIdResName, partialInteractiveRegion, interactionId, callback, mFetchFlags,
                    interrogatingPid, interrogatingTid, spec, transformMatrix);
            return mSecurityPolicy.computeValidReportedPackages(
                    connection.getPackageName(), connection.getUid());
        } catch (RemoteException re) {
            if (DEBUG) {
                Slog.e(LOG_TAG, "Error findAccessibilityNodeInfoByViewId().");
            }
        } finally {
            Binder.restoreCallingIdentity(identityToken);
            // Recycle if passed to another process.
            if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                partialInteractiveRegion.recycle();
            }
        }
        return null;
    }

    @Override
    public String[] findAccessibilityNodeInfosByText(int accessibilityWindowId,
            long accessibilityNodeId, String text, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, long interrogatingTid)
            throws RemoteException {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("findAccessibilityNodeInfosByText",
                    "accessibilityWindowId=" + accessibilityWindowId + ";accessibilityNodeId="
                    + accessibilityNodeId + ";text=" + text + ";interactionId=" + interactionId
                    + ";callback=" + callback + ";interrogatingTid=" + interrogatingTid);
        }
        final int resolvedWindowId;
        RemoteAccessibilityConnection connection;
        Region partialInteractiveRegion = Region.obtain();
        synchronized (mLock) {
            mUsesAccessibilityCache = true;
            if (!hasRightsToCurrentUserLocked()) {
                return null;
            }
            resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
            final boolean permissionGranted =
                    mSecurityPolicy.canGetAccessibilityNodeInfoLocked(
                            mSystemSupport.getCurrentUserIdLocked(), this, resolvedWindowId);
            if (!permissionGranted) {
                return null;
            } else {
                connection = mA11yWindowManager.getConnectionLocked(
                        mSystemSupport.getCurrentUserIdLocked(), resolvedWindowId);
                if (connection == null) {
                    return null;
                }
            }
            if (!mA11yWindowManager.computePartialInteractiveRegionForWindowLocked(
                    resolvedWindowId, partialInteractiveRegion)) {
                partialInteractiveRegion.recycle();
                partialInteractiveRegion = null;
            }
        }
        final Pair<float[], MagnificationSpec> transformMatrixAndSpec =
                getWindowTransformationMatrixAndMagnificationSpec(resolvedWindowId);
        final float[] transformMatrix = transformMatrixAndSpec.first;
        final MagnificationSpec spec = transformMatrixAndSpec.second;
        if (!mSecurityPolicy.checkAccessibilityAccess(this)) {
            return null;
        }
        final int interrogatingPid = Binder.getCallingPid();
        callback = replaceCallbackIfNeeded(callback, resolvedWindowId, interactionId,
                interrogatingPid, interrogatingTid);
        final long identityToken = Binder.clearCallingIdentity();
        if (intConnTracingEnabled()) {
            logTraceIntConn("findAccessibilityNodeInfosByText",
                    accessibilityNodeId + ";" + text + ";" + partialInteractiveRegion + ";"
                    + interactionId + ";" + callback + ";" + mFetchFlags + ";" + interrogatingPid
                    + ";" + interrogatingTid + ";" + spec + ";" + Arrays.toString(transformMatrix));
        }
        try {
            connection.getRemote().findAccessibilityNodeInfosByText(accessibilityNodeId,
                    text, partialInteractiveRegion, interactionId, callback, mFetchFlags,
                    interrogatingPid, interrogatingTid, spec, transformMatrix);
            return mSecurityPolicy.computeValidReportedPackages(
                    connection.getPackageName(), connection.getUid());
        } catch (RemoteException re) {
            if (DEBUG) {
                Slog.e(LOG_TAG, "Error calling findAccessibilityNodeInfosByText()");
            }
        } finally {
            Binder.restoreCallingIdentity(identityToken);
            // Recycle if passed to another process.
            if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                partialInteractiveRegion.recycle();
            }
        }
        return null;
    }

    @Override
    public String[] findAccessibilityNodeInfoByAccessibilityId(
            int accessibilityWindowId, long accessibilityNodeId, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, int flags,
            long interrogatingTid, Bundle arguments) throws RemoteException {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("findAccessibilityNodeInfoByAccessibilityId",
                    "accessibilityWindowId=" + accessibilityWindowId + ";accessibilityNodeId="
                    + accessibilityNodeId + ";interactionId=" + interactionId + ";callback="
                    + callback + ";flags=" + flags + ";interrogatingTid=" + interrogatingTid
                    + ";arguments=" + arguments);
        }
        final int resolvedWindowId;
        RemoteAccessibilityConnection connection;
        Region partialInteractiveRegion = Region.obtain();
        synchronized (mLock) {
            mUsesAccessibilityCache = true;
            if (!hasRightsToCurrentUserLocked()) {
                return null;
            }
            resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
            final boolean permissionGranted =
                    mSecurityPolicy.canGetAccessibilityNodeInfoLocked(
                            mSystemSupport.getCurrentUserIdLocked(), this, resolvedWindowId);
            if (!permissionGranted) {
                return null;
            } else {
                connection = mA11yWindowManager.getConnectionLocked(
                        mSystemSupport.getCurrentUserIdLocked(), resolvedWindowId);
                if (connection == null) {
                    return null;
                }
            }
            if (!mA11yWindowManager.computePartialInteractiveRegionForWindowLocked(
                    resolvedWindowId, partialInteractiveRegion)) {
                partialInteractiveRegion.recycle();
                partialInteractiveRegion = null;
            }
        }
        final Pair<float[], MagnificationSpec> transformMatrixAndSpec =
                getWindowTransformationMatrixAndMagnificationSpec(resolvedWindowId);
        final float[] transformMatrix = transformMatrixAndSpec.first;
        final MagnificationSpec spec = transformMatrixAndSpec.second;
        if (!mSecurityPolicy.checkAccessibilityAccess(this)) {
            return null;
        }
        final int interrogatingPid = Binder.getCallingPid();
        callback = replaceCallbackIfNeeded(callback, resolvedWindowId, interactionId,
                interrogatingPid, interrogatingTid);
        final long identityToken = Binder.clearCallingIdentity();
        if (intConnTracingEnabled()) {
            logTraceIntConn("findAccessibilityNodeInfoByAccessibilityId",
                    accessibilityNodeId + ";" + partialInteractiveRegion + ";" + interactionId + ";"
                    + callback + ";" + (mFetchFlags | flags) + ";" + interrogatingPid + ";"
                            + interrogatingTid + ";" + spec + ";" + Arrays.toString(transformMatrix)
                            + ";" + arguments);
        }
        try {
            connection.getRemote().findAccessibilityNodeInfoByAccessibilityId(
                    accessibilityNodeId, partialInteractiveRegion, interactionId, callback,
                    mFetchFlags | flags, interrogatingPid, interrogatingTid, spec, transformMatrix,
                    arguments);
            return mSecurityPolicy.computeValidReportedPackages(
                    connection.getPackageName(), connection.getUid());
        } catch (RemoteException re) {
            if (DEBUG) {
                Slog.e(LOG_TAG, "Error calling findAccessibilityNodeInfoByAccessibilityId()");
            }
        } finally {
            Binder.restoreCallingIdentity(identityToken);
            // Recycle if passed to another process.
            if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                partialInteractiveRegion.recycle();
            }
        }
        return null;
    }

    @Override
    public String[] findFocus(int accessibilityWindowId, long accessibilityNodeId,
            int focusType, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, long interrogatingTid)
            throws RemoteException {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("findFocus",
                    "accessibilityWindowId=" + accessibilityWindowId + ";accessibilityNodeId="
                    + accessibilityNodeId + ";focusType=" + focusType + ";interactionId="
                    + interactionId + ";callback=" + callback + ";interrogatingTid="
                    + interrogatingTid);
        }
        final int resolvedWindowId;
        RemoteAccessibilityConnection connection;
        Region partialInteractiveRegion = Region.obtain();
        synchronized (mLock) {
            if (!hasRightsToCurrentUserLocked()) {
                return null;
            }
            resolvedWindowId = resolveAccessibilityWindowIdForFindFocusLocked(
                    accessibilityWindowId, focusType);
            final boolean permissionGranted =
                    mSecurityPolicy.canGetAccessibilityNodeInfoLocked(
                            mSystemSupport.getCurrentUserIdLocked(), this, resolvedWindowId);
            if (!permissionGranted) {
                return null;
            } else {
                connection = mA11yWindowManager.getConnectionLocked(
                        mSystemSupport.getCurrentUserIdLocked(), resolvedWindowId);
                if (connection == null) {
                    return null;
                }
            }
            if (!mA11yWindowManager.computePartialInteractiveRegionForWindowLocked(
                    resolvedWindowId, partialInteractiveRegion)) {
                partialInteractiveRegion.recycle();
                partialInteractiveRegion = null;
            }
        }
        final Pair<float[], MagnificationSpec> transformMatrixAndSpec =
                getWindowTransformationMatrixAndMagnificationSpec(resolvedWindowId);
        final float[] transformMatrix = transformMatrixAndSpec.first;
        final MagnificationSpec spec = transformMatrixAndSpec.second;
        if (!mSecurityPolicy.checkAccessibilityAccess(this)) {
            return null;
        }
        final int interrogatingPid = Binder.getCallingPid();
        callback = replaceCallbackIfNeeded(callback, resolvedWindowId, interactionId,
                interrogatingPid, interrogatingTid);
        final long identityToken = Binder.clearCallingIdentity();
        if (intConnTracingEnabled()) {
            logTraceIntConn("findFocus",
                    accessibilityNodeId + ";" + focusType + ";" + partialInteractiveRegion + ";"
                    + interactionId + ";" + callback + ";" + mFetchFlags + ";" + interrogatingPid
                            + ";" + interrogatingTid + ";" + spec + ";"
                            + Arrays.toString(transformMatrix));
        }
        try {
            connection.getRemote().findFocus(accessibilityNodeId, focusType,
                    partialInteractiveRegion, interactionId, callback, mFetchFlags,
                    interrogatingPid, interrogatingTid, spec, transformMatrix);
            return mSecurityPolicy.computeValidReportedPackages(
                    connection.getPackageName(), connection.getUid());
        } catch (RemoteException re) {
            if (DEBUG) {
                Slog.e(LOG_TAG, "Error calling findFocus()");
            }
        } finally {
            Binder.restoreCallingIdentity(identityToken);
            // Recycle if passed to another process.
            if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                partialInteractiveRegion.recycle();
            }
        }
        return null;
    }

    @Override
    public String[] focusSearch(int accessibilityWindowId, long accessibilityNodeId,
            int direction, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, long interrogatingTid)
            throws RemoteException {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("focusSearch",
                    "accessibilityWindowId=" + accessibilityWindowId + ";accessibilityNodeId="
                    + accessibilityNodeId + ";direction=" + direction + ";interactionId="
                    + interactionId + ";callback=" + callback + ";interrogatingTid="
                    + interrogatingTid);
        }
        final int resolvedWindowId;
        RemoteAccessibilityConnection connection;
        Region partialInteractiveRegion = Region.obtain();
        synchronized (mLock) {
            if (!hasRightsToCurrentUserLocked()) {
                return null;
            }
            resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
            final boolean permissionGranted =
                    mSecurityPolicy.canGetAccessibilityNodeInfoLocked(
                            mSystemSupport.getCurrentUserIdLocked(), this, resolvedWindowId);
            if (!permissionGranted) {
                return null;
            } else {
                connection = mA11yWindowManager.getConnectionLocked(
                        mSystemSupport.getCurrentUserIdLocked(), resolvedWindowId);
                if (connection == null) {
                    return null;
                }
            }
            if (!mA11yWindowManager.computePartialInteractiveRegionForWindowLocked(
                    resolvedWindowId, partialInteractiveRegion)) {
                partialInteractiveRegion.recycle();
                partialInteractiveRegion = null;
            }
        }
        final Pair<float[], MagnificationSpec> transformMatrixAndSpec =
                getWindowTransformationMatrixAndMagnificationSpec(resolvedWindowId);
        final float[] transformMatrix = transformMatrixAndSpec.first;
        final MagnificationSpec spec = transformMatrixAndSpec.second;
        if (!mSecurityPolicy.checkAccessibilityAccess(this)) {
            return null;
        }
        final int interrogatingPid = Binder.getCallingPid();
        callback = replaceCallbackIfNeeded(callback, resolvedWindowId, interactionId,
                interrogatingPid, interrogatingTid);
        final long identityToken = Binder.clearCallingIdentity();
        if (intConnTracingEnabled()) {
            logTraceIntConn("focusSearch",
                    accessibilityNodeId + ";" + direction + ";" + partialInteractiveRegion
                    + ";" + interactionId + ";" + callback + ";" + mFetchFlags + ";"
                            + interrogatingPid + ";" + interrogatingTid + ";" + spec + ";"
                             + Arrays.toString(transformMatrix));
        }
        try {
            connection.getRemote().focusSearch(accessibilityNodeId, direction,
                    partialInteractiveRegion, interactionId, callback, mFetchFlags,
                    interrogatingPid, interrogatingTid, spec, transformMatrix);
            return mSecurityPolicy.computeValidReportedPackages(
                    connection.getPackageName(), connection.getUid());
        } catch (RemoteException re) {
            if (DEBUG) {
                Slog.e(LOG_TAG, "Error calling accessibilityFocusSearch()");
            }
        } finally {
            Binder.restoreCallingIdentity(identityToken);
            // Recycle if passed to another process.
            if (partialInteractiveRegion != null && Binder.isProxy(connection.getRemote())) {
                partialInteractiveRegion.recycle();
            }
        }
        return null;
    }

    @Override
    public void sendGesture(int sequence, ParceledListSlice gestureSteps) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn(
                    "sendGesture", "sequence=" + sequence + ";gestureSteps=" + gestureSteps);
        }
    }

    @Override
    public void dispatchGesture(int sequence, ParceledListSlice gestureSteps, int displayId) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("dispatchGesture", "sequence=" + sequence + ";gestureSteps="
                    + gestureSteps + ";displayId=" + displayId);
        }
    }

    @Override
    public boolean performAccessibilityAction(int accessibilityWindowId,
            long accessibilityNodeId, int action, Bundle arguments, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, long interrogatingTid)
            throws RemoteException {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("performAccessibilityAction",
                    "accessibilityWindowId=" + accessibilityWindowId + ";accessibilityNodeId="
                    + accessibilityNodeId + ";action=" + action + ";arguments=" + arguments
                    + ";interactionId=" + interactionId + ";callback=" + callback
                    + ";interrogatingTid=" + interrogatingTid);
        }
        final int resolvedWindowId;
        synchronized (mLock) {
            if (!hasRightsToCurrentUserLocked()) {
                return false;
            }
            resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
            if (!mSecurityPolicy.canGetAccessibilityNodeInfoLocked(
                    mSystemSupport.getCurrentUserIdLocked(), this, resolvedWindowId)) {
                return false;
            }
        }
        if (!mSecurityPolicy.checkAccessibilityAccess(this)) {
            return false;
        }
        return performAccessibilityActionInternal(
                mSystemSupport.getCurrentUserIdLocked(), resolvedWindowId, accessibilityNodeId,
                action, arguments, interactionId, callback, mFetchFlags, interrogatingTid);
    }

    @Override
    public boolean performGlobalAction(int action) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("performGlobalAction", "action=" + action);
        }
        synchronized (mLock) {
            if (!hasRightsToCurrentUserLocked()) {
                return false;
            }
        }
        return mSystemActionPerformer.performSystemAction(action);
    }

    @Override
    public @NonNull List<AccessibilityNodeInfo.AccessibilityAction> getSystemActions() {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("getSystemActions", "");
        }
        synchronized (mLock) {
            if (!hasRightsToCurrentUserLocked()) {
                return Collections.emptyList();
            }
        }
        return mSystemActionPerformer.getSystemActions();
    }

    @Override
    public boolean isFingerprintGestureDetectionAvailable() {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("isFingerprintGestureDetectionAvailable", "");
        }
        if (!mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
            return false;
        }
        if (isCapturingFingerprintGestures()) {
            FingerprintGestureDispatcher dispatcher =
                    mSystemSupport.getFingerprintGestureDispatcher();
            return (dispatcher != null) && dispatcher.isFingerprintGestureDetectionAvailable();
        }
        return false;
    }

    @Nullable
    @Override
    public MagnificationConfig getMagnificationConfig(int displayId) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("getMagnificationConfig", "displayId=" + displayId);
        }
        synchronized (mLock) {
            if (!hasRightsToCurrentUserLocked()) {
                return null;
            }
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            return mSystemSupport.getMagnificationProcessor().getMagnificationConfig(displayId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public float getMagnificationScale(int displayId) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("getMagnificationScale", "displayId=" + displayId);
        }
        synchronized (mLock) {
            if (!hasRightsToCurrentUserLocked()) {
                return 1.0f;
            }
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            return mSystemSupport.getMagnificationProcessor().getScale(displayId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public Region getMagnificationRegion(int displayId) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("getMagnificationRegion", "displayId=" + displayId);
        }
        synchronized (mLock) {
            final Region region = Region.obtain();
            if (!hasRightsToCurrentUserLocked()) {
                return region;
            }
            MagnificationProcessor magnificationProcessor =
                    mSystemSupport.getMagnificationProcessor();
            final long identity = Binder.clearCallingIdentity();
            try {
                magnificationProcessor.getFullscreenMagnificationRegion(displayId,
                        region, mSecurityPolicy.canControlMagnification(this));
                return region;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }


    @Override
    public Region getCurrentMagnificationRegion(int displayId) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("getCurrentMagnificationRegion", "displayId=" + displayId);
        }
        synchronized (mLock) {
            final Region region = Region.obtain();
            if (!hasRightsToCurrentUserLocked()) {
                return region;
            }
            MagnificationProcessor magnificationProcessor =
                    mSystemSupport.getMagnificationProcessor();
            final long identity = Binder.clearCallingIdentity();
            try {
                magnificationProcessor.getCurrentMagnificationRegion(displayId,
                        region, mSecurityPolicy.canControlMagnification(this));
                return region;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public float getMagnificationCenterX(int displayId) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("getMagnificationCenterX", "displayId=" + displayId);
        }
        synchronized (mLock) {
            if (!hasRightsToCurrentUserLocked()) {
                return 0.0f;
            }
            MagnificationProcessor magnificationProcessor =
                    mSystemSupport.getMagnificationProcessor();
            final long identity = Binder.clearCallingIdentity();
            try {
                return magnificationProcessor.getCenterX(displayId,
                        mSecurityPolicy.canControlMagnification(this));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public float getMagnificationCenterY(int displayId) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("getMagnificationCenterY", "displayId=" + displayId);
        }
        synchronized (mLock) {
            if (!hasRightsToCurrentUserLocked()) {
                return 0.0f;
            }
            MagnificationProcessor magnificationProcessor =
                    mSystemSupport.getMagnificationProcessor();
            final long identity = Binder.clearCallingIdentity();
            try {
                return magnificationProcessor.getCenterY(displayId,
                        mSecurityPolicy.canControlMagnification(this));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public boolean resetMagnification(int displayId, boolean animate) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("resetMagnification", "displayId=" + displayId + ";animate=" + animate);
        }
        synchronized (mLock) {
            if (!hasRightsToCurrentUserLocked()) {
                return false;
            }
            if (!mSecurityPolicy.canControlMagnification(this)) {
                return false;
            }
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            MagnificationProcessor magnificationProcessor =
                    mSystemSupport.getMagnificationProcessor();
            return (magnificationProcessor.resetFullscreenMagnification(displayId, animate)
                    || !magnificationProcessor.isMagnifying(displayId));
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public boolean resetCurrentMagnification(int displayId, boolean animate) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("resetCurrentMagnification",
                    "displayId=" + displayId + ";animate=" + animate);
        }
        synchronized (mLock) {
            if (!hasRightsToCurrentUserLocked()) {
                return false;
            }
            if (!mSecurityPolicy.canControlMagnification(this)) {
                return false;
            }
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            MagnificationProcessor magnificationProcessor =
                    mSystemSupport.getMagnificationProcessor();
            return (magnificationProcessor.resetCurrentMagnification(displayId, animate)
                    || !magnificationProcessor.isMagnifying(displayId));
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public boolean setMagnificationConfig(int displayId,
            @NonNull MagnificationConfig config, boolean animate) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("setMagnificationSpec",
                    "displayId=" + displayId + ", config=" + config.toString());
        }
        synchronized (mLock) {
            if (!hasRightsToCurrentUserLocked()) {
                return false;
            }
            if (!mSecurityPolicy.canControlMagnification(this)) {
                return false;
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                MagnificationProcessor magnificationProcessor =
                        mSystemSupport.getMagnificationProcessor();
                return magnificationProcessor.setMagnificationConfig(displayId, config, animate,
                        mId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public void setMagnificationCallbackEnabled(int displayId, boolean enabled) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("setMagnificationCallbackEnabled",
                    "displayId=" + displayId + ";enabled=" + enabled);
        }
        mInvocationHandler.setMagnificationCallbackEnabled(displayId, enabled);
    }

    public boolean isMagnificationCallbackEnabled(int displayId) {
        return mInvocationHandler.isMagnificationCallbackEnabled(displayId);
    }

    @Override
    public void setSoftKeyboardCallbackEnabled(boolean enabled) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("setSoftKeyboardCallbackEnabled", "enabled=" + enabled);
        }
        mInvocationHandler.setSoftKeyboardCallbackEnabled(enabled);
    }

    @Override
    public void takeScreenshotOfWindow(int accessibilityWindowId, int interactionId,
            ScreenCapture.ScreenCaptureListener listener,
            IAccessibilityInteractionConnectionCallback callback) throws RemoteException {
        final long currentTimestamp = SystemClock.uptimeMillis();
        if ((currentTimestamp
                - mRequestTakeScreenshotOfWindowTimestampMs.get(accessibilityWindowId, 0L))
                <= ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS) {
            callback.sendTakeScreenshotOfWindowError(
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT, interactionId);
            return;
        }
        mRequestTakeScreenshotOfWindowTimestampMs.put(accessibilityWindowId, currentTimestamp);

        synchronized (mLock) {
            if (!hasRightsToCurrentUserLocked()) {
                callback.sendTakeScreenshotOfWindowError(
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR, interactionId);
                return;
            }
            if (!mSecurityPolicy.canTakeScreenshotLocked(this)) {
                callback.sendTakeScreenshotOfWindowError(
                        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS,
                        interactionId);
                return;
            }
        }
        if (!mSecurityPolicy.checkAccessibilityAccess(this)) {
            callback.sendTakeScreenshotOfWindowError(
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS,
                    interactionId);
            return;
        }

        RemoteAccessibilityConnection connection = mA11yWindowManager.getConnectionLocked(
                mSystemSupport.getCurrentUserIdLocked(),
                resolveAccessibilityWindowIdLocked(accessibilityWindowId));
        if (connection == null) {
            callback.sendTakeScreenshotOfWindowError(
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW, interactionId);
            return;
        }
        connection.getRemote().takeScreenshotOfWindow(interactionId, listener, callback);
    }

    @Override
    public void takeScreenshot(int displayId, RemoteCallback callback) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("takeScreenshot", "displayId=" + displayId + ";callback=" + callback);
        }
        final long currentTimestamp = SystemClock.uptimeMillis();
        if (mRequestTakeScreenshotTimestampMs != 0
                && (currentTimestamp - mRequestTakeScreenshotTimestampMs)
                <= AccessibilityService.ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS) {
            sendScreenshotFailure(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT,
                    callback);
            return;
        }
        mRequestTakeScreenshotTimestampMs = currentTimestamp;

        synchronized (mLock) {
            if (!hasRightsToCurrentUserLocked()) {
                sendScreenshotFailure(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR,
                        callback);
                return;
            }

            if (!mSecurityPolicy.canTakeScreenshotLocked(this)) {
                throw new SecurityException("Services don't have the capability of taking"
                        + " the screenshot.");
            }
        }

        if (!mSecurityPolicy.checkAccessibilityAccess(this)) {
            sendScreenshotFailure(
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS,
                    callback);
            return;
        }

        // Private virtual displays are created by the ap and is not allowed to access by other
        // aps.  We assume the contents on this display should not be captured.
        final DisplayManager displayManager =
                (DisplayManager) mContext.getSystemService(Context.DISPLAY_SERVICE);
        final Display display = displayManager.getDisplay(displayId);
        if ((display == null) || (display.getType() == Display.TYPE_VIRTUAL
                && (display.getFlags() & Display.FLAG_PRIVATE) != 0)) {
            sendScreenshotFailure(
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY, callback);
            return;
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            mMainHandler.post(PooledLambda.obtainRunnable((nonArg) -> {
                final ScreenshotHardwareBuffer screenshotBuffer = LocalServices
                        .getService(DisplayManagerInternal.class).userScreenshot(displayId);
                if (screenshotBuffer != null) {
                    sendScreenshotSuccess(screenshotBuffer, callback);
                } else {
                    sendScreenshotFailure(
                            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY, callback);
                }
            }, null).recycleOnUse());
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void sendScreenshotSuccess(ScreenshotHardwareBuffer screenshotBuffer,
            RemoteCallback callback) {
        final HardwareBuffer hardwareBuffer = screenshotBuffer.getHardwareBuffer();
        final ParcelableColorSpace colorSpace =
                new ParcelableColorSpace(screenshotBuffer.getColorSpace());

        final Bundle payload = new Bundle();
        payload.putInt(KEY_ACCESSIBILITY_SCREENSHOT_STATUS,
                AccessibilityService.TAKE_SCREENSHOT_SUCCESS);
        payload.putParcelable(KEY_ACCESSIBILITY_SCREENSHOT_HARDWAREBUFFER,
                hardwareBuffer);
        payload.putParcelable(KEY_ACCESSIBILITY_SCREENSHOT_COLORSPACE, colorSpace);
        payload.putLong(KEY_ACCESSIBILITY_SCREENSHOT_TIMESTAMP,
                SystemClock.uptimeMillis());

        // Send back the result.
        callback.sendResult(payload);
        hardwareBuffer.close();
    }

    private void sendScreenshotFailure(@AccessibilityService.ScreenshotErrorCode int errorCode,
            RemoteCallback callback) {
        mMainHandler.post(PooledLambda.obtainRunnable((nonArg) -> {
            final Bundle payload = new Bundle();
            payload.putInt(KEY_ACCESSIBILITY_SCREENSHOT_STATUS, errorCode);
            // Send back the result.
            callback.sendResult(payload);
        }, null).recycleOnUse());
    }

    @Override
    public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, LOG_TAG, pw)) return;
        synchronized (mLock) {
            pw.append("Service[label=" + mAccessibilityServiceInfo.getResolveInfo()
                    .loadLabel(mContext.getPackageManager()));
            pw.append(", feedbackType"
                    + AccessibilityServiceInfo.feedbackTypeToString(mFeedbackType));
            pw.append(", capabilities=" + mAccessibilityServiceInfo.getCapabilities());
            pw.append(", eventTypes="
                    + AccessibilityEvent.eventTypeToString(mEventTypes));
            pw.append(", notificationTimeout=" + mNotificationTimeout);
            pw.append(", requestA11yBtn=" + mRequestAccessibilityButton);
            pw.append("]");
        }
    }

    public void onAdded() {
        final Display[] displays = mDisplayManager.getDisplays();
        for (int i = 0; i < displays.length; i++) {
            final int displayId = displays[i].getDisplayId();
            onDisplayAdded(displayId);
        }
    }

    /**
     * Called whenever a logical display has been added to the system. Add a window token for adding
     * an accessibility overlay.
     *
     * @param displayId The id of the logical display that was added.
     */
    public void onDisplayAdded(int displayId) {
        final long identity = Binder.clearCallingIdentity();
        try {
            final IBinder overlayWindowToken = new Binder();
            if (wmTracingEnabled()) {
                logTraceWM("addWindowToken",
                        overlayWindowToken + ";TYPE_ACCESSIBILITY_OVERLAY;" + displayId + ";null");
            }
            mWindowManagerService.addWindowToken(overlayWindowToken, TYPE_ACCESSIBILITY_OVERLAY,
                    displayId, null /* options */);
            synchronized (mLock) {
                mOverlayWindowTokens.put(displayId, overlayWindowToken);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void onRemoved() {
        final Display[] displays = mDisplayManager.getDisplays();
        for (int i = 0; i < displays.length; i++) {
            final int displayId = displays[i].getDisplayId();
            onDisplayRemoved(displayId);
        }
    }

    /**
     * Called whenever a logical display has been removed from the system. Remove a window token for
     * removing an accessibility overlay.
     *
     * @param displayId The id of the logical display that was added.
     */
    public void onDisplayRemoved(int displayId) {
        final long identity = Binder.clearCallingIdentity();
        if (wmTracingEnabled()) {
            logTraceWM(
                    "addWindowToken", mOverlayWindowTokens.get(displayId) + ";true;" + displayId);
        }
        try {
            mWindowManagerService.removeWindowToken(mOverlayWindowTokens.get(displayId), true,
                    displayId);
            synchronized (mLock) {
                mOverlayWindowTokens.remove(displayId);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Gets overlay window token by the display Id.
     *
     * @param displayId The id of the logical display that was added.
     * @return window token.
     */
    @Override
    public IBinder getOverlayWindowToken(int displayId) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("getOverlayWindowToken", "displayId=" + displayId);
        }
        synchronized (mLock) {
            return mOverlayWindowTokens.get(displayId);
        }
    }

    /**
     * Gets windowId of given token.
     *
     * @param token The token
     * @return window id
     */
    @Override
    public int getWindowIdForLeashToken(@NonNull IBinder token) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("getWindowIdForLeashToken", "token=" + token);
        }
        synchronized (mLock) {
            return mA11yWindowManager.getWindowIdLocked(token);
        }
    }

    public void resetLocked() {
        mSystemSupport.getKeyEventDispatcher().flush(this);
        try {
            // Clear the proxy in the other process so this
            // IAccessibilityServiceConnection can be garbage collected.
            if (mServiceInterface != null) {
                if (svcClientTracingEnabled()) {
                    logTraceSvcClient("init", "null, " + mId + ", null");
                }
                mServiceInterface.init(null, mId, null);
            }
        } catch (RemoteException re) {
                /* ignore */
        }
        if (mService != null) {
            try {
                mService.unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
                Slog.e(LOG_TAG, "Failed unregistering death link");
            }
            mService = null;
        }

        mServiceInterface = null;
        mReceivedAccessibilityButtonCallbackSinceBind = false;
    }

    public boolean isConnectedLocked() {
        return (mService != null);
    }

    public void notifyAccessibilityEvent(AccessibilityEvent event) {
        synchronized (mLock) {
            final int eventType = event.getEventType();

            final boolean serviceWantsEvent = wantsEventLocked(event);
            final boolean requiredForCacheConsistency = mUsesAccessibilityCache
                    && ((AccessibilityCache.CACHE_CRITICAL_EVENTS_MASK & eventType) != 0);
            if (!serviceWantsEvent && !requiredForCacheConsistency) {
                return;
            }

            if (!mSecurityPolicy.checkAccessibilityAccess(this)) {
                return;
            }
            // Make a copy since during dispatch it is possible the event to
            // be modified to remove its source if the receiving service does
            // not have permission to access the window content.
            AccessibilityEvent newEvent = AccessibilityEvent.obtain(event);
            Message message;
            if ((mNotificationTimeout > 0)
                    && (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
                // Allow at most one pending event
                final AccessibilityEvent oldEvent = mPendingEvents.get(eventType);
                mPendingEvents.put(eventType, newEvent);
                if (oldEvent != null) {
                    mEventDispatchHandler.removeMessages(eventType);
                    oldEvent.recycle();
                }
                message = mEventDispatchHandler.obtainMessage(eventType);
            } else {
                // Send all messages, bypassing mPendingEvents
                message = mEventDispatchHandler.obtainMessage(eventType, newEvent);
            }
            message.arg1 = serviceWantsEvent ? 1 : 0;

            mEventDispatchHandler.sendMessageDelayed(message, mNotificationTimeout);
        }
    }

    /**
     * Determines if given event can be dispatched to a service based on the package of the
     * event source. Specifically, a service is notified if it is interested in events from the
     * package.
     *
     * @param event The event.
     * @return True if the listener should be notified, false otherwise.
     */
    private boolean wantsEventLocked(AccessibilityEvent event) {

        if (!canReceiveEventsLocked()) {
            return false;
        }

        final boolean includeNotImportantViews = (mFetchFlags
                & AccessibilityNodeInfo.FLAG_SERVICE_REQUESTS_INCLUDE_NOT_IMPORTANT_VIEWS) != 0;
        if ((event.getWindowId() != AccessibilityWindowInfo.UNDEFINED_WINDOW_ID)
                && !event.isImportantForAccessibility()
                && !includeNotImportantViews) {
            return false;
        }

        if (event.isAccessibilityDataSensitive()
                && (mFetchFlags & AccessibilityNodeInfo.FLAG_SERVICE_IS_ACCESSIBILITY_TOOL) == 0) {
            return false;
        }

        int eventType = event.getEventType();
        if ((mEventTypes & eventType) != eventType) {
            return false;
        }

        Set<String> packageNames = mPackageNames;
        String packageName = (event.getPackageName() != null)
                ? event.getPackageName().toString() : null;

        return (packageNames.isEmpty() || packageNames.contains(packageName));
    }

    /**
     * Notifies an accessibility service client for a scheduled event given the event type.
     *
     * @param eventType The type of the event to dispatch.
     */
    private void notifyAccessibilityEventInternal(
            int eventType,
            AccessibilityEvent event,
            boolean serviceWantsEvent) {
        IAccessibilityServiceClient listener;

        synchronized (mLock) {
            listener = mServiceInterface;

            // If the service died/was disabled while the message for dispatching
            // the accessibility event was propagating the listener may be null.
            if (listener == null) {
                return;
            }

            // There are two ways we notify for events, throttled AND non-throttled. If we
            // are not throttling, then messages come with events, which we handle with
            // minimal fuss.
            if (event == null) {
                // We are throttling events, so we'll send the event for this type in
                // mPendingEvents as long as it it's null. It can only null due to a race
                // condition:
                //
                //   1) A binder thread calls notifyAccessibilityServiceDelayedLocked
                //      which posts a message for dispatching an event and stores the event
                //      in mPendingEvents.
                //   2) The message is pulled from the queue by the handler on the service
                //      thread and this method is just about to acquire the lock.
                //   3) Another binder thread acquires the lock in notifyAccessibilityEvent
                //   4) notifyAccessibilityEvent recycles the event that this method was about
                //      to process, replaces it with a new one, and posts a second message
                //   5) This method grabs the new event, processes it, and removes it from
                //      mPendingEvents
                //   6) The second message dispatched in (4) arrives, but the event has been
                //      remvoved in (5).
                event = mPendingEvents.get(eventType);
                if (event == null) {
                    return;
                }
                mPendingEvents.remove(eventType);
            }
            if (mSecurityPolicy.canRetrieveWindowContentLocked(this)) {
                event.setConnectionId(mId);
            } else {
                event.setSource((View) null);
            }
            event.setSealed(true);
        }

        try {
            if (svcClientTracingEnabled()) {
                logTraceSvcClient("onAccessibilityEvent", event + ";" + serviceWantsEvent);
            }
            listener.onAccessibilityEvent(event, serviceWantsEvent);
            if (DEBUG) {
                Slog.i(LOG_TAG, "Event " + event + " sent to " + listener);
            }
        } catch (RemoteException re) {
            Slog.e(LOG_TAG, "Error during sending " + event + " to " + listener, re);
        } finally {
            event.recycle();
        }
    }

    public void notifyGesture(AccessibilityGestureEvent gestureEvent) {
        mInvocationHandler.obtainMessage(InvocationHandler.MSG_ON_GESTURE,
                gestureEvent).sendToTarget();
    }

    public void notifySystemActionsChangedLocked() {
        mInvocationHandler.sendEmptyMessage(
                InvocationHandler.MSG_ON_SYSTEM_ACTIONS_CHANGED);
    }

    public void notifyClearAccessibilityNodeInfoCache() {
        mInvocationHandler.sendEmptyMessage(
                InvocationHandler.MSG_CLEAR_ACCESSIBILITY_CACHE);
    }

    public void notifyMagnificationChangedLocked(int displayId, @NonNull Region region,
            @NonNull MagnificationConfig config) {
        mInvocationHandler
                .notifyMagnificationChangedLocked(displayId, region, config);
    }

    public void notifySoftKeyboardShowModeChangedLocked(int showState) {
        mInvocationHandler.notifySoftKeyboardShowModeChangedLocked(showState);
    }

    public void notifyAccessibilityButtonClickedLocked(int displayId) {
        mInvocationHandler.notifyAccessibilityButtonClickedLocked(displayId);
    }

    public void notifyAccessibilityButtonAvailabilityChangedLocked(boolean available) {
        mInvocationHandler.notifyAccessibilityButtonAvailabilityChangedLocked(available);
    }

    public void createImeSessionLocked() {
        mInvocationHandler.createImeSessionLocked();
    }

    public void setImeSessionEnabledLocked(IAccessibilityInputMethodSession session,
            boolean enabled) {
        mInvocationHandler.setImeSessionEnabledLocked(session, enabled);
    }

    public void bindInputLocked() {
        mInvocationHandler.bindInputLocked();
    }

    public  void unbindInputLocked() {
        mInvocationHandler.unbindInputLocked();
    }

    public void startInputLocked(IRemoteAccessibilityInputConnection connection,
            EditorInfo editorInfo, boolean restarting) {
        mInvocationHandler.startInputLocked(connection, editorInfo, restarting);
    }

    @Nullable
    private Pair<float[], MagnificationSpec> getWindowTransformationMatrixAndMagnificationSpec(
            int resolvedWindowId) {
        return mSystemSupport.getWindowTransformationMatrixAndMagnificationSpec(resolvedWindowId);
    }

    public boolean wantsGenericMotionEvent(MotionEvent event) {
        final int eventSourceWithoutClass = event.getSource() & ~InputDevice.SOURCE_CLASS_MASK;
        return (mGenericMotionEventSources & eventSourceWithoutClass) != 0;
    }

    /**
     * Called by the invocation handler to notify the service that the
     * state of magnification has changed.
     */
    private void notifyMagnificationChangedInternal(int displayId, @NonNull Region region,
            @NonNull MagnificationConfig config) {
        final IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                if (svcClientTracingEnabled()) {
                    logTraceSvcClient("onMagnificationChanged", displayId + ", " + region + ", "
                            + config.toString());
                }
                listener.onMagnificationChanged(displayId, region, config);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error sending magnification changes to " + mService, re);
            }
        }
    }

    /**
     * Called by the invocation handler to notify the service that the state of the soft
     * keyboard show mode has changed.
     */
    private void notifySoftKeyboardShowModeChangedInternal(int showState) {
        final IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                if (svcClientTracingEnabled()) {
                    logTraceSvcClient("onSoftKeyboardShowModeChanged", String.valueOf(showState));
                }
                listener.onSoftKeyboardShowModeChanged(showState);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error sending soft keyboard show mode changes to " + mService,
                        re);
            }
        }
    }

    private void notifyAccessibilityButtonClickedInternal(int displayId) {
        final IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                if (svcClientTracingEnabled()) {
                    logTraceSvcClient("onAccessibilityButtonClicked", String.valueOf(displayId));
                }
                listener.onAccessibilityButtonClicked(displayId);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error sending accessibility button click to " + mService, re);
            }
        }
    }

    private void notifyAccessibilityButtonAvailabilityChangedInternal(boolean available) {
        // Only notify the service if it's not been notified or the state has changed
        if (mReceivedAccessibilityButtonCallbackSinceBind
                && (mLastAccessibilityButtonCallbackState == available)) {
            return;
        }
        mReceivedAccessibilityButtonCallbackSinceBind = true;
        mLastAccessibilityButtonCallbackState = available;
        final IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                if (svcClientTracingEnabled()) {
                    logTraceSvcClient("onAccessibilityButtonAvailabilityChanged",
                            String.valueOf(available));
                }
                listener.onAccessibilityButtonAvailabilityChanged(available);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG,
                        "Error sending accessibility button availability change to " + mService,
                        re);
            }
        }
    }

    private void notifyGestureInternal(AccessibilityGestureEvent gestureInfo) {
        final IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                if (svcClientTracingEnabled()) {
                    logTraceSvcClient("onGesture", gestureInfo.toString());
                }
                listener.onGesture(gestureInfo);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error during sending gesture " + gestureInfo
                        + " to " + mService, re);
            }
        }
    }

    private void notifySystemActionsChangedInternal() {
        final IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                if (svcClientTracingEnabled()) {
                    logTraceSvcClient("onSystemActionsChanged", "");
                }
                listener.onSystemActionsChanged();
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error sending system actions change to " + mService,
                        re);
            }
        }
    }

    private void notifyClearAccessibilityCacheInternal() {
        final IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                if (svcClientTracingEnabled()) {
                    logTraceSvcClient("clearAccessibilityCache", "");
                }
                listener.clearAccessibilityCache();
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error during requesting accessibility info cache"
                        + " to be cleared.", re);
            }
        }
    }

    private void createImeSessionInternal() {
        final IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                if (svcClientTracingEnabled()) {
                    logTraceSvcClient("createImeSession", "");
                }
                AccessibilityCallback callback = new AccessibilityCallback();
                listener.createImeSession(callback);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG,
                        "Error requesting IME session from " + mService, re);
            }
        }
    }

    private void setImeSessionEnabledInternal(IAccessibilityInputMethodSession session,
            boolean enabled) {
        final IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null && session != null) {
            try {
                if (svcClientTracingEnabled()) {
                    logTraceSvcClient("createImeSession", "");
                }
                listener.setImeSessionEnabled(session, enabled);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG,
                        "Error requesting IME session from " + mService, re);
            }
        }
    }

    private void bindInputInternal() {
        final IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                if (svcClientTracingEnabled()) {
                    logTraceSvcClient("bindInput", "");
                }
                listener.bindInput();
            } catch (RemoteException re) {
                Slog.e(LOG_TAG,
                        "Error binding input to " + mService, re);
            }
        }
    }

    private void unbindInputInternal() {
        final IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                if (svcClientTracingEnabled()) {
                    logTraceSvcClient("unbindInput", "");
                }
                listener.unbindInput();
            } catch (RemoteException re) {
                Slog.e(LOG_TAG,
                        "Error unbinding input to " + mService, re);
            }
        }
    }

    private void startInputInternal(IRemoteAccessibilityInputConnection connection,
            EditorInfo editorInfo, boolean restarting) {
        final IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                if (svcClientTracingEnabled()) {
                    logTraceSvcClient("startInput", "editorInfo=" + editorInfo
                            + " restarting=" + restarting);
                }
                listener.startInput(connection, editorInfo, restarting);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG,
                        "Error starting input to " + mService, re);
            }
        }
    }

    protected IAccessibilityServiceClient getServiceInterfaceSafely() {
        synchronized (mLock) {
            return mServiceInterface;
        }
    }

    private int resolveAccessibilityWindowIdLocked(int accessibilityWindowId) {
        if (accessibilityWindowId == AccessibilityWindowInfo.ACTIVE_WINDOW_ID) {
            final int focusedWindowId =
                    mA11yWindowManager.getActiveWindowId(mSystemSupport.getCurrentUserIdLocked());
            if (!mA11yWindowManager.windowIdBelongsToDisplayType(focusedWindowId, mDisplayTypes)) {
                return AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
            }
            return focusedWindowId;
        }
        return accessibilityWindowId;
    }

    int resolveAccessibilityWindowIdForFindFocusLocked(int windowId, int focusType) {
        if (windowId == AccessibilityWindowInfo.ANY_WINDOW_ID) {
            final int focusedWindowId = mA11yWindowManager.getFocusedWindowId(focusType);
            // If the caller is a proxy and the found window doesn't belong to a proxy display
            // (or vice versa), then return null. This doesn't work if there are multiple active
            // proxys, but in the future this code shouldn't be needed if input focus are
            // properly split. (so we will deal with the issues if we see them).
            //TODO(254545943): Remove this when there is user and proxy separation of input focus
            if (!mA11yWindowManager.windowIdBelongsToDisplayType(focusedWindowId, mDisplayTypes)) {
                return AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
            }
            return focusedWindowId;
        }
        return windowId;
    }

    /**
     * Request that the system make sure windows are available to interrogate.
     *
     * @param displayId The logical display id.
     */
    private void ensureWindowsAvailableTimedLocked(int displayId) {
        if (displayId == Display.INVALID_DISPLAY) {
            return;
        }

        if (mA11yWindowManager.getWindowListLocked(displayId) != null) {
            return;
        }
        // If we have no registered callback, update the state we
        // we may have to register one but it didn't happen yet.
        if (!mA11yWindowManager.isTrackingWindowsLocked(displayId)) {
            // Invokes client change to make sure tracking window enabled.
            mSystemSupport.onClientChangeLocked(false);
        }
        // We have no windows but do not care about them, done.
        if (!mA11yWindowManager.isTrackingWindowsLocked(displayId)) {
            return;
        }

        // Wait for the windows with a timeout.
        final long startMillis = SystemClock.uptimeMillis();
        while (mA11yWindowManager.getWindowListLocked(displayId) == null) {
            final long elapsedMillis = SystemClock.uptimeMillis() - startMillis;
            final long remainMillis = WAIT_WINDOWS_TIMEOUT_MILLIS - elapsedMillis;
            if (remainMillis <= 0) {
                return;
            }
            try {
                mLock.wait(remainMillis);
            } catch (InterruptedException ie) {
                /* ignore */
            }
        }
    }

    /**
     * Perform the specified accessibility action
     *
     * @param resolvedWindowId The window ID
     * [Other parameters match the method on IAccessibilityServiceConnection]
     *
     * @return Whether or not the action could be sent to the app process
     */
    private boolean performAccessibilityActionInternal(int userId, int resolvedWindowId,
            long accessibilityNodeId, int action, Bundle arguments, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, int fetchFlags,
            long interrogatingTid) {
        RemoteAccessibilityConnection connection;
        IBinder windowToken = null;
        synchronized (mLock) {
            connection = mA11yWindowManager.getConnectionLocked(userId, resolvedWindowId);
            if (connection == null)  {
                return false;
            }
            final boolean isA11yFocusAction = (action == ACTION_ACCESSIBILITY_FOCUS)
                    || (action == ACTION_CLEAR_ACCESSIBILITY_FOCUS);
            if (!isA11yFocusAction) {
                windowToken = mA11yWindowManager.getWindowTokenForUserAndWindowIdLocked(
                        userId, resolvedWindowId);
            }
            final AccessibilityWindowInfo a11yWindowInfo =
                    mA11yWindowManager.findA11yWindowInfoByIdLocked(resolvedWindowId);
            if (a11yWindowInfo != null && a11yWindowInfo.isInPictureInPictureMode()
                    && mA11yWindowManager.getPictureInPictureActionReplacingConnection() != null
                    && !isA11yFocusAction) {
                connection = mA11yWindowManager.getPictureInPictureActionReplacingConnection();
            }
        }
        final int interrogatingPid = Binder.getCallingPid();
        final long identityToken = Binder.clearCallingIdentity();
        try {
            // Regardless of whether or not the action succeeds, it was generated by an
            // accessibility service that is driven by user actions, so note user activity.
            mPowerManager.userActivity(SystemClock.uptimeMillis(),
                    PowerManager.USER_ACTIVITY_EVENT_ACCESSIBILITY, 0);

            if (action == ACTION_CLICK || action == ACTION_LONG_CLICK) {
                mA11yWindowManager.notifyOutsideTouch(userId, resolvedWindowId);
            }
            if (windowToken != null) {
                mWindowManagerService.requestWindowFocus(windowToken);
            }
            if (intConnTracingEnabled()) {
                logTraceIntConn("performAccessibilityAction",
                        accessibilityNodeId + ";" + action + ";" + arguments + ";" + interactionId
                        + ";" + callback + ";" + mFetchFlags + ";" + interrogatingPid + ";"
                        + interrogatingTid);
            }
            connection.getRemote().performAccessibilityAction(accessibilityNodeId, action,
                    arguments, interactionId, callback, fetchFlags, interrogatingPid,
                    interrogatingTid);
        } catch (RemoteException re) {
            if (DEBUG) {
                Slog.e(LOG_TAG, "Error calling performAccessibilityAction: " + re);
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(identityToken);
        }
        return true;
    }

    /**
     * Replace the interaction callback if needed, for example if the window is in picture-
     * in-picture mode and needs its nodes replaced.
     *
     * @param originalCallback The callback we were planning to use
     * @param resolvedWindowId The ID of the window we're calling
     * @param interactionId The id for the original callback
     * @param interrogatingPid Process ID of requester
     * @param interrogatingTid Thread ID of requester
     *
     * @return The callback to use, which may be the original one.
     */
    private IAccessibilityInteractionConnectionCallback replaceCallbackIfNeeded(
            IAccessibilityInteractionConnectionCallback originalCallback, int resolvedWindowId,
            int interactionId, int interrogatingPid, long interrogatingTid) {
        final RemoteAccessibilityConnection pipActionReplacingConnection =
                mA11yWindowManager.getPictureInPictureActionReplacingConnection();
        synchronized (mLock) {
            final AccessibilityWindowInfo windowInfo =
                    mA11yWindowManager.findA11yWindowInfoByIdLocked(resolvedWindowId);
            if ((windowInfo == null) || !windowInfo.isInPictureInPictureMode()
                || (pipActionReplacingConnection == null)) {
                return originalCallback;
            }
        }
        return new ActionReplacingCallback(originalCallback,
                pipActionReplacingConnection.getRemote(), interactionId,
                interrogatingPid, interrogatingTid);
    }

    private List<AccessibilityWindowInfo> getWindowsByDisplayLocked(int displayId) {
        final List<AccessibilityWindowInfo> internalWindowList =
                mA11yWindowManager.getWindowListLocked(displayId);
        if (internalWindowList == null) {
            return null;
        }
        final List<AccessibilityWindowInfo> returnedWindowList = new ArrayList<>();
        final int windowCount = internalWindowList.size();
        for (int i = 0; i < windowCount; i++) {
            AccessibilityWindowInfo window = internalWindowList.get(i);
            AccessibilityWindowInfo windowClone =
                    AccessibilityWindowInfo.obtain(window);
            windowClone.setConnectionId(mId);
            returnedWindowList.add(windowClone);
        }
        return returnedWindowList;
    }

    public ComponentName getComponentName() {
        return mComponentName;
    }

    private final class InvocationHandler extends Handler {
        public static final int MSG_ON_GESTURE = 1;
        public static final int MSG_CLEAR_ACCESSIBILITY_CACHE = 2;

        private static final int MSG_ON_MAGNIFICATION_CHANGED = 5;
        private static final int MSG_ON_SOFT_KEYBOARD_STATE_CHANGED = 6;
        private static final int MSG_ON_ACCESSIBILITY_BUTTON_CLICKED = 7;
        private static final int MSG_ON_ACCESSIBILITY_BUTTON_AVAILABILITY_CHANGED = 8;
        private static final int MSG_ON_SYSTEM_ACTIONS_CHANGED = 9;
        private static final int MSG_CREATE_IME_SESSION = 10;
        private static final int MSG_SET_IME_SESSION_ENABLED = 11;
        private static final int MSG_BIND_INPUT = 12;
        private static final int MSG_UNBIND_INPUT = 13;
        private static final int MSG_START_INPUT = 14;

        /** List of magnification callback states, mapping from displayId -> Boolean */
        @GuardedBy("mlock")
        private final SparseArray<Boolean> mMagnificationCallbackState = new SparseArray<>(0);
        private boolean mIsSoftKeyboardCallbackEnabled = false;

        public InvocationHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message message) {
            final int type = message.what;
            switch (type) {
                case MSG_ON_GESTURE: {
                    notifyGestureInternal((AccessibilityGestureEvent) message.obj);
                } break;

                case MSG_CLEAR_ACCESSIBILITY_CACHE: {
                    notifyClearAccessibilityCacheInternal();
                } break;

                case MSG_ON_MAGNIFICATION_CHANGED: {
                    final SomeArgs args = (SomeArgs) message.obj;
                    final Region region = (Region) args.arg1;
                    final MagnificationConfig config = (MagnificationConfig) args.arg2;
                    final int displayId = args.argi1;
                    notifyMagnificationChangedInternal(displayId, region, config);
                    args.recycle();
                } break;

                case MSG_ON_SOFT_KEYBOARD_STATE_CHANGED: {
                    final int showState = (int) message.arg1;
                    notifySoftKeyboardShowModeChangedInternal(showState);
                } break;

                case MSG_ON_ACCESSIBILITY_BUTTON_CLICKED: {
                    final int displayId = (int) message.arg1;
                    notifyAccessibilityButtonClickedInternal(displayId);
                } break;

                case MSG_ON_ACCESSIBILITY_BUTTON_AVAILABILITY_CHANGED: {
                    final boolean available = (message.arg1 != 0);
                    notifyAccessibilityButtonAvailabilityChangedInternal(available);
                } break;
                case MSG_ON_SYSTEM_ACTIONS_CHANGED: {
                    notifySystemActionsChangedInternal();
                    break;
                }
                case MSG_CREATE_IME_SESSION:
                    createImeSessionInternal();
                    break;
                case MSG_SET_IME_SESSION_ENABLED:
                    final boolean enabled = (message.arg1 != 0);
                    final IAccessibilityInputMethodSession session =
                            (IAccessibilityInputMethodSession) message.obj;
                    setImeSessionEnabledInternal(session, enabled);
                    break;
                case MSG_BIND_INPUT:
                    bindInputInternal();
                    break;
                case MSG_UNBIND_INPUT:
                    unbindInputInternal();
                    break;
                case MSG_START_INPUT:
                    final boolean restarting = (message.arg1 != 0);
                    final SomeArgs args = (SomeArgs) message.obj;
                    final IRemoteAccessibilityInputConnection connection =
                            (IRemoteAccessibilityInputConnection) args.arg1;
                    final EditorInfo editorInfo = (EditorInfo) args.arg2;
                    startInputInternal(connection, editorInfo, restarting);
                    args.recycle();
                    break;
                default: {
                    throw new IllegalArgumentException("Unknown message: " + type);
                }
            }
        }

        public void notifyMagnificationChangedLocked(int displayId, @NonNull Region region,
                @NonNull MagnificationConfig config) {
            synchronized (mLock) {
                if (mMagnificationCallbackState.get(displayId) == null) {
                    return;
                }
            }

            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = region;
            args.arg2 = config;
            args.argi1 = displayId;

            final Message msg = obtainMessage(MSG_ON_MAGNIFICATION_CHANGED, args);
            msg.sendToTarget();
        }

        public void setMagnificationCallbackEnabled(int displayId, boolean enabled) {
            synchronized (mLock) {
                if (enabled) {
                    mMagnificationCallbackState.put(displayId, true);
                } else {
                    mMagnificationCallbackState.remove(displayId);
                }
            }
        }

        public boolean isMagnificationCallbackEnabled(int displayId) {
            synchronized (mLock) {
                return mMagnificationCallbackState.get(displayId) != null;
            }
        }

        public void notifySoftKeyboardShowModeChangedLocked(int showState) {
            if (!mIsSoftKeyboardCallbackEnabled) {
                return;
            }

            final Message msg = obtainMessage(MSG_ON_SOFT_KEYBOARD_STATE_CHANGED, showState, 0);
            msg.sendToTarget();
        }

        public void setSoftKeyboardCallbackEnabled(boolean enabled) {
            mIsSoftKeyboardCallbackEnabled = enabled;
        }

        public void notifyAccessibilityButtonClickedLocked(int displayId) {
            final Message msg = obtainMessage(MSG_ON_ACCESSIBILITY_BUTTON_CLICKED, displayId, 0);
            msg.sendToTarget();
        }

        public void notifyAccessibilityButtonAvailabilityChangedLocked(boolean available) {
            final Message msg = obtainMessage(MSG_ON_ACCESSIBILITY_BUTTON_AVAILABILITY_CHANGED,
                    (available ? 1 : 0), 0);
            msg.sendToTarget();
        }

        public void createImeSessionLocked() {
            final Message msg = obtainMessage(MSG_CREATE_IME_SESSION);
            msg.sendToTarget();
        }

        public void setImeSessionEnabledLocked(IAccessibilityInputMethodSession session,
                boolean enabled) {
            final Message msg = obtainMessage(MSG_SET_IME_SESSION_ENABLED, (enabled ? 1 : 0),
                    0, session);
            msg.sendToTarget();
        }

        public void bindInputLocked() {
            final Message msg = obtainMessage(MSG_BIND_INPUT);
            msg.sendToTarget();
        }

        public void unbindInputLocked() {
            final Message msg = obtainMessage(MSG_UNBIND_INPUT);
            msg.sendToTarget();
        }

        public void startInputLocked(
                IRemoteAccessibilityInputConnection connection,
                EditorInfo editorInfo, boolean restarting) {
            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = connection;
            args.arg2 = editorInfo;
            final Message msg = obtainMessage(MSG_START_INPUT, restarting ? 1 : 0, 0, args);
            msg.sendToTarget();
        }
    }

    public boolean isServiceHandlesDoubleTapEnabled() {
        return mServiceHandlesDoubleTap;
    }

    public boolean isMultiFingerGesturesEnabled() {
        return mRequestMultiFingerGestures;
    }

    public boolean isTwoFingerPassthroughEnabled() {
        return mRequestTwoFingerPassthrough;
    }

    public boolean isSendMotionEventsEnabled() {
        return mSendMotionEvents;
    }

    @Override
    public void setGestureDetectionPassthroughRegion(int displayId, Region region) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("setGestureDetectionPassthroughRegion",
                    "displayId=" + displayId + ";region=" + region);
        }
        mSystemSupport.setGestureDetectionPassthroughRegion(displayId, region);
    }

    @Override
    public void setTouchExplorationPassthroughRegion(int displayId, Region region) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("setTouchExplorationPassthroughRegion",
                    "displayId=" + displayId + ";region=" + region);
        }
        mSystemSupport.setTouchExplorationPassthroughRegion(displayId, region);
    }

    @Override
    public void setFocusAppearance(int strokeWidth, int color) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("setFocusAppearance", "strokeWidth=" + strokeWidth + ";color=" + color);
        }
    }

    @Override
    public void setCacheEnabled(boolean enabled) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("setCacheEnabled", "enabled=" + enabled);
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                mUsesAccessibilityCache = enabled;
                mSystemSupport.onClientChangeLocked(true);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void logTrace(long timestamp, String where, long loggingTypes, String callingParams,
            int processId, long threadId, int callingUid, Bundle callingStack) {
        if (mTrace.isA11yTracingEnabledForTypes(loggingTypes)) {
            ArrayList<StackTraceElement> list =
                    (ArrayList<StackTraceElement>) callingStack.getSerializable(CALL_STACK, java.util.ArrayList.class);
            HashSet<String> ignoreList =
                    (HashSet<String>) callingStack.getSerializable(IGNORE_CALL_STACK, java.util.HashSet.class);
            mTrace.logTrace(timestamp, where, loggingTypes, callingParams, processId, threadId,
                    callingUid, list.toArray(new StackTraceElement[list.size()]), ignoreList);
        }
    }

    protected boolean svcClientTracingEnabled() {
        return mTrace.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_SERVICE_CLIENT);
    }

    protected void logTraceSvcClient(String methodName, String params) {
        mTrace.logTrace(TRACE_SVC_CLIENT + "." + methodName,
                    FLAGS_ACCESSIBILITY_SERVICE_CLIENT, params);
    }

    protected boolean svcConnTracingEnabled() {
        return mTrace.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_SERVICE_CONNECTION);
    }

    protected void logTraceSvcConn(String methodName, String params) {
        mTrace.logTrace(TRACE_SVC_CONN + "." + methodName,
                FLAGS_ACCESSIBILITY_SERVICE_CONNECTION, params);
    }

    protected boolean intConnTracingEnabled() {
        return mTrace.isA11yTracingEnabledForTypes(FLAGS_ACCESSIBILITY_INTERACTION_CONNECTION);
    }

    protected void logTraceIntConn(String methodName, String params) {
        mTrace.logTrace(LOG_TAG + "." + methodName,
                FLAGS_ACCESSIBILITY_INTERACTION_CONNECTION, params);
    }

    protected boolean wmTracingEnabled() {
        return mTrace.isA11yTracingEnabledForTypes(FLAGS_WINDOW_MANAGER_INTERNAL);
    }

    protected void logTraceWM(String methodName, String params) {
        mTrace.logTrace(TRACE_WM + "." + methodName, FLAGS_WINDOW_MANAGER_INTERNAL, params);
    }

    public void setServiceDetectsGesturesEnabled(int displayId, boolean mode) {
        mServiceDetectsGestures.put(displayId, mode);
        mSystemSupport.setServiceDetectsGesturesEnabled(displayId, mode);
    }

    public boolean isServiceDetectsGesturesEnabled(int displayId) {
        if (mServiceDetectsGestures.contains(displayId)) {
            return mServiceDetectsGestures.get(displayId);
        }
        return false;
    }

    public void requestTouchExploration(int displayId) {
        mSystemSupport.requestTouchExploration(displayId);
    }

    public void requestDragging(int displayId, int pointerId) {
        mSystemSupport.requestDragging(displayId, pointerId);
    }

    public void requestDelegating(int displayId) {
        mSystemSupport.requestDelegating(displayId);
    }

    public void onDoubleTap(int displayId) {
        mSystemSupport.onDoubleTap(displayId);
    }

    public void onDoubleTapAndHold(int displayId) {
        mSystemSupport.onDoubleTapAndHold(displayId);
    }

    /**
     * Sets the scaling factor for animations.
     */
    public void setAnimationScale(float scale) {
        final long identity = Binder.clearCallingIdentity();
        try {
            Settings.Global.putFloat(
                    mContext.getContentResolver(), Settings.Global.WINDOW_ANIMATION_SCALE, scale);
            Settings.Global.putFloat(
                    mContext.getContentResolver(),
                    Settings.Global.TRANSITION_ANIMATION_SCALE,
                    scale);
            Settings.Global.putFloat(
                    mContext.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, scale);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static final class AccessibilityCallback
            extends IAccessibilityInputMethodSessionCallback.Stub {
        @Override
        public void sessionCreated(IAccessibilityInputMethodSession session, int id) {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "AACS.sessionCreated");
            final long ident = Binder.clearCallingIdentity();
            try {
                InputMethodManagerInternal.get().onSessionForAccessibilityCreated(id, session);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    @Override
    public void attachAccessibilityOverlayToDisplay(int displayId, SurfaceControl sc) {
        mSystemSupport.attachAccessibilityOverlayToDisplay(displayId, sc);
    }

    @Override
    public void attachAccessibilityOverlayToWindow(int accessibilityWindowId, SurfaceControl sc)
            throws RemoteException {
        synchronized (mLock) {
            RemoteAccessibilityConnection connection =
                    mA11yWindowManager.getConnectionLocked(
                            mSystemSupport.getCurrentUserIdLocked(),
                            resolveAccessibilityWindowIdLocked(accessibilityWindowId));
            if (connection == null) {
                Slog.e(LOG_TAG, "unable to get remote accessibility connection.");
                return;
            }
            connection.getRemote().attachAccessibilityOverlayToWindow(sc);
        }
    }
}