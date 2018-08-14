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

import static android.accessibilityservice.AccessibilityServiceInfo.DEFAULT;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.accessibilityservice.IAccessibilityServiceConnection;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.graphics.Region;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.MagnificationSpec;
import android.view.View;
import android.view.accessibility.AccessibilityCache;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.IAccessibilityInteractionConnection;
import android.view.accessibility.IAccessibilityInteractionConnectionCallback;

import com.android.internal.os.SomeArgs;
import com.android.internal.util.DumpUtils;
import com.android.server.accessibility.AccessibilityManagerService.RemoteAccessibilityConnection;
import com.android.server.accessibility.AccessibilityManagerService.SecurityPolicy;
import com.android.server.wm.WindowManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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

    protected final Context mContext;
    protected final SystemSupport mSystemSupport;
    private final WindowManagerInternal mWindowManagerService;
    private final GlobalActionPerformer mGlobalActionPerformer;

    // Handler for scheduling method invocations on the main thread.
    public final InvocationHandler mInvocationHandler;

    final int mId;

    protected final AccessibilityServiceInfo mAccessibilityServiceInfo;

    // Lock must match the one used by AccessibilityManagerService
    protected final Object mLock;

    protected final SecurityPolicy mSecurityPolicy;

    // The service that's bound to this instance. Whenever this value is non-null, this
    // object is registered as a death recipient
    IBinder mService;

    IAccessibilityServiceClient mServiceInterface;

    int mEventTypes;

    int mFeedbackType;

    Set<String> mPackageNames = new HashSet<>();

    boolean mIsDefault;

    boolean mRequestTouchExplorationMode;

    boolean mRequestFilterKeyEvents;

    boolean mRetrieveInteractiveWindows;

    boolean mCaptureFingerprintGestures;

    boolean mRequestAccessibilityButton;

    boolean mReceivedAccessibilityButtonCallbackSinceBind;

    boolean mLastAccessibilityButtonCallbackState;

    int mFetchFlags;

    long mNotificationTimeout;

    final ComponentName mComponentName;

    // the events pending events to be dispatched to this service
    final SparseArray<AccessibilityEvent> mPendingEvents = new SparseArray<>();

    /** Whether this service relies on its {@link AccessibilityCache} being up to date */
    boolean mUsesAccessibilityCache = false;

    // Handler only for dispatching accessibility events since we use event
    // types as message types allowing us to remove messages per event type.
    public Handler mEventDispatchHandler;

    final IBinder mOverlayWindowToken = new Binder();


    public interface SystemSupport {
        /**
         * @return The current dispatcher for key events
         */
        @NonNull KeyEventDispatcher getKeyEventDispatcher();

        /**
         * @param windowId The id of the window of interest
         * @return The magnification spec for the window, or {@code null} if none is available
         */
        @Nullable MagnificationSpec getCompatibleMagnificationSpecLocked(int windowId);

        /**
         * @return The current injector of motion events, if one exists
         */
        @Nullable MotionEventInjector getMotionEventInjectorLocked();

        /**
         * @return The current dispatcher for fingerprint gestures, if one exists
         */
        @Nullable FingerprintGestureDispatcher getFingerprintGestureDispatcher();

        /**
         * @return The magnification controller
         */
        @NonNull MagnificationController getMagnificationController();

        /**
         * Resolve a connection wrapper for a window id
         *
         * @param windowId The id of the window of interest
         *
         * @return a connection to the window
         */
        RemoteAccessibilityConnection getConnectionLocked(int windowId);

        /**
         * Perform the specified accessibility action
         *
         * @param resolvedWindowId The window ID
         * [Other parameters match the method on IAccessibilityServiceConnection]
         *
         * @return Whether or not the action could be sent to the app process
         */
        boolean performAccessibilityAction(int resolvedWindowId,
                long accessibilityNodeId, int action, Bundle arguments, int interactionId,
                IAccessibilityInteractionConnectionCallback callback, int fetchFlags,
                long interrogatingTid);

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
        @NonNull IAccessibilityInteractionConnectionCallback replaceCallbackIfNeeded(
                IAccessibilityInteractionConnectionCallback originalCallback,
                int resolvedWindowId, int interactionId, int interrogatingPid,
                long interrogatingTid);

        /**
         * Request that the system make sure windows are available to interrogate
         */
        void ensureWindowsAvailableTimed();

        /**
         * Called back to notify system that the client has changed
         * @param serviceInfoChanged True if the service's AccessibilityServiceInfo changed.
         */
        void onClientChange(boolean serviceInfoChanged);

        int getCurrentUserIdLocked();

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
    }

    public AbstractAccessibilityServiceConnection(Context context, ComponentName componentName,
            AccessibilityServiceInfo accessibilityServiceInfo, int id, Handler mainHandler,
            Object lock, SecurityPolicy securityPolicy, SystemSupport systemSupport,
            WindowManagerInternal windowManagerInternal,
            GlobalActionPerformer globalActionPerfomer) {
        mContext = context;
        mWindowManagerService = windowManagerInternal;
        mId = id;
        mComponentName = componentName;
        mAccessibilityServiceInfo = accessibilityServiceInfo;
        mLock = lock;
        mSecurityPolicy = securityPolicy;
        mGlobalActionPerformer = globalActionPerfomer;
        mSystemSupport = systemSupport;
        mInvocationHandler = new InvocationHandler(mainHandler.getLooper());
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
        try {
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
        if (packageNames != null) {
            mPackageNames.addAll(Arrays.asList(packageNames));
        }
        mNotificationTimeout = info.notificationTimeout;
        mIsDefault = (info.flags & DEFAULT) != 0;

        if (supportsFlagForNotImportantViews(info)) {
            if ((info.flags & AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS) != 0) {
                mFetchFlags |= AccessibilityNodeInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            } else {
                mFetchFlags &= ~AccessibilityNodeInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            }
        }

        if ((info.flags & AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS) != 0) {
            mFetchFlags |= AccessibilityNodeInfo.FLAG_REPORT_VIEW_IDS;
        } else {
            mFetchFlags &= ~AccessibilityNodeInfo.FLAG_REPORT_VIEW_IDS;
        }

        mRequestTouchExplorationMode = (info.flags
                & AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0;
        mRequestFilterKeyEvents = (info.flags
                & AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0;
        mRetrieveInteractiveWindows = (info.flags
                & AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) != 0;
        mCaptureFingerprintGestures = (info.flags
                & AccessibilityServiceInfo.FLAG_REQUEST_FINGERPRINT_GESTURES) != 0;
        mRequestAccessibilityButton = (info.flags
                & AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON) != 0;
    }

    protected boolean supportsFlagForNotImportantViews(AccessibilityServiceInfo info) {
        return info.getResolveInfo().serviceInfo.applicationInfo.targetSdkVersion
                >= Build.VERSION_CODES.JELLY_BEAN;
    }

    public boolean canReceiveEventsLocked() {
        return (mEventTypes != 0 && mFeedbackType != 0 && mService != null);
    }

    @Override
    public void setOnKeyEventResult(boolean handled, int sequence) {
        mSystemSupport.getKeyEventDispatcher().setOnKeyEventResult(this, handled, sequence);
    }

    @Override
    public AccessibilityServiceInfo getServiceInfo() {
        synchronized (mLock) {
            return mAccessibilityServiceInfo;
        }
    }

    public int getCapabilities() {
        return mAccessibilityServiceInfo.getCapabilities();
    }

    int getRelevantEventTypes() {
        return (mUsesAccessibilityCache ? AccessibilityCache.CACHE_CRITICAL_EVENTS_MASK : 0)
                | mEventTypes;
    }

    @Override
    public void setServiceInfo(AccessibilityServiceInfo info) {
        final long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                // If the XML manifest had data to configure the service its info
                // should be already set. In such a case update only the dynamically
                // configurable properties.
                AccessibilityServiceInfo oldInfo = mAccessibilityServiceInfo;
                if (oldInfo != null) {
                    oldInfo.updateDynamicallyConfigurableProperties(info);
                    setDynamicallyConfigurableProperties(oldInfo);
                } else {
                    setDynamicallyConfigurableProperties(info);
                }
                mSystemSupport.onClientChange(true);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    protected abstract boolean isCalledForCurrentUserLocked();

    @Override
    public List<AccessibilityWindowInfo> getWindows() {
        mSystemSupport.ensureWindowsAvailableTimed();
        synchronized (mLock) {
            if (!isCalledForCurrentUserLocked()) {
                return null;
            }
            final boolean permissionGranted =
                    mSecurityPolicy.canRetrieveWindowsLocked(this);
            if (!permissionGranted) {
                return null;
            }
            if (mSecurityPolicy.mWindows == null) {
                return null;
            }
            List<AccessibilityWindowInfo> windows = new ArrayList<>();
            final int windowCount = mSecurityPolicy.mWindows.size();
            for (int i = 0; i < windowCount; i++) {
                AccessibilityWindowInfo window = mSecurityPolicy.mWindows.get(i);
                AccessibilityWindowInfo windowClone =
                        AccessibilityWindowInfo.obtain(window);
                windowClone.setConnectionId(mId);
                windows.add(windowClone);
            }
            return windows;
        }
    }

    @Override
    public AccessibilityWindowInfo getWindow(int windowId) {
        mSystemSupport.ensureWindowsAvailableTimed();
        synchronized (mLock) {
            if (!isCalledForCurrentUserLocked()) {
                return null;
            }
            final boolean permissionGranted =
                    mSecurityPolicy.canRetrieveWindowsLocked(this);
            if (!permissionGranted) {
                return null;
            }
            AccessibilityWindowInfo window = mSecurityPolicy.findA11yWindowInfoById(windowId);
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
        final int resolvedWindowId;
        RemoteAccessibilityConnection connection;
        Region partialInteractiveRegion = Region.obtain();
        MagnificationSpec spec;
        synchronized (mLock) {
            mUsesAccessibilityCache = true;
            if (!isCalledForCurrentUserLocked()) {
                return null;
            }
            resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
            final boolean permissionGranted =
                    mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
            if (!permissionGranted) {
                return null;
            } else {
                connection = mSystemSupport.getConnectionLocked(resolvedWindowId);
                if (connection == null) {
                    return null;
                }
            }
            if (!mSecurityPolicy.computePartialInteractiveRegionForWindowLocked(
                    resolvedWindowId, partialInteractiveRegion)) {
                partialInteractiveRegion.recycle();
                partialInteractiveRegion = null;
            }
            spec = mSystemSupport.getCompatibleMagnificationSpecLocked(resolvedWindowId);
        }
        final int interrogatingPid = Binder.getCallingPid();
        callback = mSystemSupport.replaceCallbackIfNeeded(callback, resolvedWindowId, interactionId,
                interrogatingPid, interrogatingTid);
        final int callingUid = Binder.getCallingUid();
        final long identityToken = Binder.clearCallingIdentity();
        try {
            connection.getRemote().findAccessibilityNodeInfosByViewId(accessibilityNodeId,
                    viewIdResName, partialInteractiveRegion, interactionId, callback, mFetchFlags,
                    interrogatingPid, interrogatingTid, spec);
            return mSecurityPolicy.computeValidReportedPackages(callingUid,
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
        final int resolvedWindowId;
        RemoteAccessibilityConnection connection;
        Region partialInteractiveRegion = Region.obtain();
        MagnificationSpec spec;
        synchronized (mLock) {
            mUsesAccessibilityCache = true;
            if (!isCalledForCurrentUserLocked()) {
                return null;
            }
            resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
            final boolean permissionGranted =
                    mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
            if (!permissionGranted) {
                return null;
            } else {
                connection = mSystemSupport.getConnectionLocked(resolvedWindowId);
                if (connection == null) {
                    return null;
                }
            }
            if (!mSecurityPolicy.computePartialInteractiveRegionForWindowLocked(
                    resolvedWindowId, partialInteractiveRegion)) {
                partialInteractiveRegion.recycle();
                partialInteractiveRegion = null;
            }
            spec = mSystemSupport.getCompatibleMagnificationSpecLocked(resolvedWindowId);
        }
        final int interrogatingPid = Binder.getCallingPid();
        callback = mSystemSupport.replaceCallbackIfNeeded(callback, resolvedWindowId, interactionId,
                interrogatingPid, interrogatingTid);
        final int callingUid = Binder.getCallingUid();
        final long identityToken = Binder.clearCallingIdentity();
        try {
            connection.getRemote().findAccessibilityNodeInfosByText(accessibilityNodeId,
                    text, partialInteractiveRegion, interactionId, callback, mFetchFlags,
                    interrogatingPid, interrogatingTid, spec);
            return mSecurityPolicy.computeValidReportedPackages(callingUid,
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
        final int resolvedWindowId;
        RemoteAccessibilityConnection connection;
        Region partialInteractiveRegion = Region.obtain();
        MagnificationSpec spec;
        synchronized (mLock) {
            mUsesAccessibilityCache = true;
            if (!isCalledForCurrentUserLocked()) {
                return null;
            }
            resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
            final boolean permissionGranted =
                    mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
            if (!permissionGranted) {
                return null;
            } else {
                connection = mSystemSupport.getConnectionLocked(resolvedWindowId);
                if (connection == null) {
                    return null;
                }
            }
            if (!mSecurityPolicy.computePartialInteractiveRegionForWindowLocked(
                    resolvedWindowId, partialInteractiveRegion)) {
                partialInteractiveRegion.recycle();
                partialInteractiveRegion = null;
            }
            spec = mSystemSupport.getCompatibleMagnificationSpecLocked(resolvedWindowId);
        }
        final int interrogatingPid = Binder.getCallingPid();
        callback = mSystemSupport.replaceCallbackIfNeeded(callback, resolvedWindowId, interactionId,
                interrogatingPid, interrogatingTid);
        final int callingUid = Binder.getCallingUid();
        final long identityToken = Binder.clearCallingIdentity();
        try {
            connection.getRemote().findAccessibilityNodeInfoByAccessibilityId(
                    accessibilityNodeId, partialInteractiveRegion, interactionId, callback,
                    mFetchFlags | flags, interrogatingPid, interrogatingTid, spec, arguments);
            return mSecurityPolicy.computeValidReportedPackages(callingUid,
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
        final int resolvedWindowId;
        RemoteAccessibilityConnection connection;
        Region partialInteractiveRegion = Region.obtain();
        MagnificationSpec spec;
        synchronized (mLock) {
            if (!isCalledForCurrentUserLocked()) {
                return null;
            }
            resolvedWindowId = resolveAccessibilityWindowIdForFindFocusLocked(
                    accessibilityWindowId, focusType);
            final boolean permissionGranted =
                    mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
            if (!permissionGranted) {
                return null;
            } else {
                connection = mSystemSupport.getConnectionLocked(resolvedWindowId);
                if (connection == null) {
                    return null;
                }
            }
            if (!mSecurityPolicy.computePartialInteractiveRegionForWindowLocked(
                    resolvedWindowId, partialInteractiveRegion)) {
                partialInteractiveRegion.recycle();
                partialInteractiveRegion = null;
            }
            spec = mSystemSupport.getCompatibleMagnificationSpecLocked(resolvedWindowId);
        }
        final int interrogatingPid = Binder.getCallingPid();
        callback = mSystemSupport.replaceCallbackIfNeeded(callback, resolvedWindowId, interactionId,
                interrogatingPid, interrogatingTid);
        final int callingUid = Binder.getCallingUid();
        final long identityToken = Binder.clearCallingIdentity();
        try {
            connection.getRemote().findFocus(accessibilityNodeId, focusType,
                    partialInteractiveRegion, interactionId, callback, mFetchFlags,
                    interrogatingPid, interrogatingTid, spec);
            return mSecurityPolicy.computeValidReportedPackages(callingUid,
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
        final int resolvedWindowId;
        RemoteAccessibilityConnection connection;
        Region partialInteractiveRegion = Region.obtain();
        MagnificationSpec spec;
        synchronized (mLock) {
            if (!isCalledForCurrentUserLocked()) {
                return null;
            }
            resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
            final boolean permissionGranted =
                    mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId);
            if (!permissionGranted) {
                return null;
            } else {
                connection = mSystemSupport.getConnectionLocked(resolvedWindowId);
                if (connection == null) {
                    return null;
                }
            }
            if (!mSecurityPolicy.computePartialInteractiveRegionForWindowLocked(
                    resolvedWindowId, partialInteractiveRegion)) {
                partialInteractiveRegion.recycle();
                partialInteractiveRegion = null;
            }
            spec = mSystemSupport.getCompatibleMagnificationSpecLocked(resolvedWindowId);
        }
        final int interrogatingPid = Binder.getCallingPid();
        callback = mSystemSupport.replaceCallbackIfNeeded(callback, resolvedWindowId, interactionId,
                interrogatingPid, interrogatingTid);
        final int callingUid = Binder.getCallingUid();
        final long identityToken = Binder.clearCallingIdentity();
        try {
            connection.getRemote().focusSearch(accessibilityNodeId, direction,
                    partialInteractiveRegion, interactionId, callback, mFetchFlags,
                    interrogatingPid, interrogatingTid, spec);
            return mSecurityPolicy.computeValidReportedPackages(callingUid,
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
    }

    @Override
    public boolean performAccessibilityAction(int accessibilityWindowId,
            long accessibilityNodeId, int action, Bundle arguments, int interactionId,
            IAccessibilityInteractionConnectionCallback callback, long interrogatingTid)
            throws RemoteException {
        final int resolvedWindowId;
        IAccessibilityInteractionConnection connection = null;
        synchronized (mLock) {
            if (!isCalledForCurrentUserLocked()) {
                return false;
            }
            resolvedWindowId = resolveAccessibilityWindowIdLocked(accessibilityWindowId);
            if (!mSecurityPolicy.canGetAccessibilityNodeInfoLocked(this, resolvedWindowId)) {
                return false;
            }
        }
        boolean returnValue =
                mSystemSupport.performAccessibilityAction(resolvedWindowId, accessibilityNodeId,
                action, arguments, interactionId, callback, mFetchFlags, interrogatingTid);
        return returnValue;
    }

    @Override
    public boolean performGlobalAction(int action) {
        synchronized (mLock) {
            if (!isCalledForCurrentUserLocked()) {
                return false;
            }
        }
        return mGlobalActionPerformer.performGlobalAction(action);
    }

    @Override
    public boolean isFingerprintGestureDetectionAvailable() {
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

    @Override
    public float getMagnificationScale() {
        synchronized (mLock) {
            if (!isCalledForCurrentUserLocked()) {
                return 1.0f;
            }
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            return mSystemSupport.getMagnificationController().getScale();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public Region getMagnificationRegion() {
        synchronized (mLock) {
            final Region region = Region.obtain();
            if (!isCalledForCurrentUserLocked()) {
                return region;
            }
            MagnificationController magnificationController =
                    mSystemSupport.getMagnificationController();
            boolean registeredJustForThisCall =
                    registerMagnificationIfNeeded(magnificationController);
            final long identity = Binder.clearCallingIdentity();
            try {
                magnificationController.getMagnificationRegion(region);
                return region;
            } finally {
                Binder.restoreCallingIdentity(identity);
                if (registeredJustForThisCall) {
                    magnificationController.unregister();
                }
            }
        }
    }

    @Override
    public float getMagnificationCenterX() {
        synchronized (mLock) {
            if (!isCalledForCurrentUserLocked()) {
                return 0.0f;
            }
            MagnificationController magnificationController =
                    mSystemSupport.getMagnificationController();
            boolean registeredJustForThisCall =
                    registerMagnificationIfNeeded(magnificationController);
            final long identity = Binder.clearCallingIdentity();
            try {
                return magnificationController.getCenterX();
            } finally {
                Binder.restoreCallingIdentity(identity);
                if (registeredJustForThisCall) {
                    magnificationController.unregister();
                }
            }
        }
    }

    @Override
    public float getMagnificationCenterY() {
        synchronized (mLock) {
            if (!isCalledForCurrentUserLocked()) {
                return 0.0f;
            }
            MagnificationController magnificationController =
                    mSystemSupport.getMagnificationController();
            boolean registeredJustForThisCall =
                    registerMagnificationIfNeeded(magnificationController);
            final long identity = Binder.clearCallingIdentity();
            try {
                return magnificationController.getCenterY();
            } finally {
                Binder.restoreCallingIdentity(identity);
                if (registeredJustForThisCall) {
                    magnificationController.unregister();
                }
            }
        }
    }

    private boolean registerMagnificationIfNeeded(
            MagnificationController magnificationController) {
        if (!magnificationController.isRegisteredLocked()
                && mSecurityPolicy.canControlMagnification(this)) {
            magnificationController.register();
            return true;
        }
        return false;
    }

    @Override
    public boolean resetMagnification(boolean animate) {
        synchronized (mLock) {
            if (!isCalledForCurrentUserLocked()) {
                return false;
            }
            if (!mSecurityPolicy.canControlMagnification(this)) {
                return false;
            }
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            return mSystemSupport.getMagnificationController().reset(animate);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public boolean setMagnificationScaleAndCenter(float scale, float centerX, float centerY,
            boolean animate) {
        synchronized (mLock) {
            if (!isCalledForCurrentUserLocked()) {
                return false;
            }
            if (!mSecurityPolicy.canControlMagnification(this)) {
                return false;
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                MagnificationController magnificationController =
                        mSystemSupport.getMagnificationController();
                if (!magnificationController.isRegisteredLocked()) {
                    magnificationController.register();
                }
                return magnificationController
                        .setScaleAndCenter(scale, centerX, centerY, animate, mId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public void setMagnificationCallbackEnabled(boolean enabled) {
        mInvocationHandler.setMagnificationCallbackEnabled(enabled);
    }

    public boolean isMagnificationCallbackEnabled() {
        return mInvocationHandler.mIsMagnificationCallbackEnabled;
    }

    @Override
    public void setSoftKeyboardCallbackEnabled(boolean enabled) {
        mInvocationHandler.setSoftKeyboardCallbackEnabled(enabled);
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
            pw.append("]");
        }
    }

    public void onAdded() {
        final long identity = Binder.clearCallingIdentity();
        try {
            mWindowManagerService.addWindowToken(mOverlayWindowToken,
                    TYPE_ACCESSIBILITY_OVERLAY, DEFAULT_DISPLAY);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void onRemoved() {
        final long identity = Binder.clearCallingIdentity();
        try {
            mWindowManagerService.removeWindowToken(mOverlayWindowToken, true, DEFAULT_DISPLAY);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void resetLocked() {
        mSystemSupport.getKeyEventDispatcher().flush(this);
        try {
            // Clear the proxy in the other process so this
            // IAccessibilityServiceConnection can be garbage collected.
            if (mServiceInterface != null) {
                mServiceInterface.init(null, mId, null);
            }
        } catch (RemoteException re) {
                /* ignore */
        }
        if (mService != null) {
            mService.unlinkToDeath(this, 0);
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

        if ((event.getWindowId() != AccessibilityWindowInfo.UNDEFINED_WINDOW_ID)
                && !event.isImportantForAccessibility()
                && (mFetchFlags & AccessibilityNodeInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS) == 0) {
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

    public void notifyGesture(int gestureId) {
        mInvocationHandler.obtainMessage(InvocationHandler.MSG_ON_GESTURE,
                gestureId, 0).sendToTarget();
    }

    public void notifyClearAccessibilityNodeInfoCache() {
        mInvocationHandler.sendEmptyMessage(
                InvocationHandler.MSG_CLEAR_ACCESSIBILITY_CACHE);
    }

    public void notifyMagnificationChangedLocked(@NonNull Region region,
            float scale, float centerX, float centerY) {
        mInvocationHandler
                .notifyMagnificationChangedLocked(region, scale, centerX, centerY);
    }

    public void notifySoftKeyboardShowModeChangedLocked(int showState) {
        mInvocationHandler.notifySoftKeyboardShowModeChangedLocked(showState);
    }

    public void notifyAccessibilityButtonClickedLocked() {
        mInvocationHandler.notifyAccessibilityButtonClickedLocked();
    }

    public void notifyAccessibilityButtonAvailabilityChangedLocked(boolean available) {
        mInvocationHandler.notifyAccessibilityButtonAvailabilityChangedLocked(available);
    }

    /**
     * Called by the invocation handler to notify the service that the
     * state of magnification has changed.
     */
    private void notifyMagnificationChangedInternal(@NonNull Region region,
            float scale, float centerX, float centerY) {
        final IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                listener.onMagnificationChanged(region, scale, centerX, centerY);
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
                listener.onSoftKeyboardShowModeChanged(showState);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error sending soft keyboard show mode changes to " + mService,
                        re);
            }
        }
    }

    private void notifyAccessibilityButtonClickedInternal() {
        final IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                listener.onAccessibilityButtonClicked();
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
                listener.onAccessibilityButtonAvailabilityChanged(available);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG,
                        "Error sending accessibility button availability change to " + mService,
                        re);
            }
        }
    }

    private void notifyGestureInternal(int gestureId) {
        final IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                listener.onGesture(gestureId);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error during sending gesture " + gestureId
                        + " to " + mService, re);
            }
        }
    }

    private void notifyClearAccessibilityCacheInternal() {
        final IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                listener.clearAccessibilityCache();
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error during requesting accessibility info cache"
                        + " to be cleared.", re);
            }
        }
    }

    private IAccessibilityServiceClient getServiceInterfaceSafely() {
        synchronized (mLock) {
            return mServiceInterface;
        }
    }

    private int resolveAccessibilityWindowIdLocked(int accessibilityWindowId) {
        if (accessibilityWindowId == AccessibilityWindowInfo.ACTIVE_WINDOW_ID) {
            return mSecurityPolicy.getActiveWindowId();
        }
        return accessibilityWindowId;
    }

    private int resolveAccessibilityWindowIdForFindFocusLocked(int windowId, int focusType) {
        if (windowId == AccessibilityWindowInfo.ACTIVE_WINDOW_ID) {
            return mSecurityPolicy.mActiveWindowId;
        }
        if (windowId == AccessibilityWindowInfo.ANY_WINDOW_ID) {
            if (focusType == AccessibilityNodeInfo.FOCUS_INPUT) {
                return mSecurityPolicy.mFocusedWindowId;
            } else if (focusType == AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) {
                return mSecurityPolicy.mAccessibilityFocusedWindowId;
            }
        }
        return windowId;
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

        private boolean mIsMagnificationCallbackEnabled = false;
        private boolean mIsSoftKeyboardCallbackEnabled = false;

        public InvocationHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message message) {
            final int type = message.what;
            switch (type) {
                case MSG_ON_GESTURE: {
                    final int gestureId = message.arg1;
                    notifyGestureInternal(gestureId);
                } break;

                case MSG_CLEAR_ACCESSIBILITY_CACHE: {
                    notifyClearAccessibilityCacheInternal();
                } break;

                case MSG_ON_MAGNIFICATION_CHANGED: {
                    final SomeArgs args = (SomeArgs) message.obj;
                    final Region region = (Region) args.arg1;
                    final float scale = (float) args.arg2;
                    final float centerX = (float) args.arg3;
                    final float centerY = (float) args.arg4;
                    notifyMagnificationChangedInternal(region, scale, centerX, centerY);
                    args.recycle();
                } break;

                case MSG_ON_SOFT_KEYBOARD_STATE_CHANGED: {
                    final int showState = (int) message.arg1;
                    notifySoftKeyboardShowModeChangedInternal(showState);
                } break;

                case MSG_ON_ACCESSIBILITY_BUTTON_CLICKED: {
                    notifyAccessibilityButtonClickedInternal();
                } break;

                case MSG_ON_ACCESSIBILITY_BUTTON_AVAILABILITY_CHANGED: {
                    final boolean available = (message.arg1 != 0);
                    notifyAccessibilityButtonAvailabilityChangedInternal(available);
                } break;

                default: {
                    throw new IllegalArgumentException("Unknown message: " + type);
                }
            }
        }

        public void notifyMagnificationChangedLocked(@NonNull Region region, float scale,
                float centerX, float centerY) {
            if (!mIsMagnificationCallbackEnabled) {
                // Callback is disabled, don't bother packing args.
                return;
            }

            final SomeArgs args = SomeArgs.obtain();
            args.arg1 = region;
            args.arg2 = scale;
            args.arg3 = centerX;
            args.arg4 = centerY;

            final Message msg = obtainMessage(MSG_ON_MAGNIFICATION_CHANGED, args);
            msg.sendToTarget();
        }

        public void setMagnificationCallbackEnabled(boolean enabled) {
            mIsMagnificationCallbackEnabled = enabled;
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

        public void notifyAccessibilityButtonClickedLocked() {
            final Message msg = obtainMessage(MSG_ON_ACCESSIBILITY_BUTTON_CLICKED);
            msg.sendToTarget();
        }

        public void notifyAccessibilityButtonAvailabilityChangedLocked(boolean available) {
            final Message msg = obtainMessage(MSG_ON_ACCESSIBILITY_BUTTON_AVAILABILITY_CHANGED,
                    (available ? 1 : 0), 0);
            msg.sendToTarget();
        }
    }
}
