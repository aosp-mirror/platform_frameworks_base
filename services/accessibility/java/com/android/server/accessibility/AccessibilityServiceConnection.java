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

import static android.accessibilityservice.AccessibilityService.SoftKeyboardController.ENABLE_IME_FAIL_UNKNOWN;
import static android.accessibilityservice.AccessibilityService.SoftKeyboardController.ENABLE_IME_SUCCESS;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.AccessibilityTrace;
import android.accessibilityservice.BrailleDisplayController;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.accessibilityservice.IBrailleDisplayController;
import android.accessibilityservice.TouchInteractionController;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;
import android.view.Display;
import android.view.MotionEvent;

import com.android.internal.inputmethod.IAccessibilityInputMethodSession;
import com.android.internal.inputmethod.IAccessibilityInputMethodSessionCallback;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * This class represents an accessibility service. It stores all per service
 * data required for the service management, provides API for starting/stopping the
 * service and is responsible for adding/removing the service in the data structures
 * for service management. The class also exposes configuration interface that is
 * passed to the service it represents as soon it is bound. It also serves as the
 * connection for the service.
 */
@SuppressWarnings("MissingPermissionAnnotation")
class AccessibilityServiceConnection extends AbstractAccessibilityServiceConnection {
    private static final String LOG_TAG = "AccessibilityServiceConnection";

    /*
     Holding a weak reference so there isn't a loop of references. AccessibilityUserState keeps
     lists of bound and binding services. These are freed on user changes, but just in case it
     somehow gets lost the weak reference will let the memory get GCed.

     Having the reference be null when being called is a very bad sign, but we check the condition.
    */
    final WeakReference<AccessibilityUserState> mUserStateWeakReference;
    @UserIdInt
    final int mUserId;
    final Intent mIntent;
    final ActivityTaskManagerInternal mActivityTaskManagerService;

    private BrailleDisplayConnection mBrailleDisplayConnection;
    private List<Bundle> mTestBrailleDisplays = null;

    private final Handler mMainHandler;

    private static final class AccessibilityInputMethodSessionCallback
            extends IAccessibilityInputMethodSessionCallback.Stub {
        @UserIdInt
        private final int mUserId;

        AccessibilityInputMethodSessionCallback(@UserIdInt int userId) {
            mUserId = userId;
        }

        @Override
        public void sessionCreated(IAccessibilityInputMethodSession session, int id) {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "ASC.sessionCreated");
            final long ident = Binder.clearCallingIdentity();
            try {
                InputMethodManagerInternal.get()
                        .onSessionForAccessibilityCreated(id, session, mUserId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    AccessibilityServiceConnection(@Nullable AccessibilityUserState userState,
            Context context, ComponentName componentName,
            AccessibilityServiceInfo accessibilityServiceInfo, int id, Handler mainHandler,
            Object lock, AccessibilitySecurityPolicy securityPolicy, SystemSupport systemSupport,
            AccessibilityTrace trace, WindowManagerInternal windowManagerInternal,
            SystemActionPerformer systemActionPerfomer, AccessibilityWindowManager awm,
            ActivityTaskManagerInternal activityTaskManagerService) {
        super(context, componentName, accessibilityServiceInfo, id, mainHandler, lock,
                securityPolicy, systemSupport, trace, windowManagerInternal, systemActionPerfomer,
                awm);
        mUserStateWeakReference = new WeakReference<AccessibilityUserState>(userState);
        // the user ID doesn't matter when userState is null, because it is null only when this is a
        // ProxyAccessibilityServiceConnection, for which it never creates an IME session and uses
        // the user ID.
        mUserId = userState == null ? UserHandle.USER_NULL : userState.mUserId;
        mIntent = new Intent().setComponent(mComponentName);
        mMainHandler = mainHandler;
        mIntent.putExtra(Intent.EXTRA_CLIENT_LABEL,
                com.android.internal.R.string.accessibility_binding_label);
        mActivityTaskManagerService = activityTaskManagerService;
        final long identity = Binder.clearCallingIdentity();
        try {
            mIntent.putExtra(Intent.EXTRA_CLIENT_INTENT, mSystemSupport.getPendingIntentActivity(
                    mContext, 0, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                    PendingIntent.FLAG_IMMUTABLE));
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void bindLocked() {
        AccessibilityUserState userState = mUserStateWeakReference.get();
        if (userState == null) return;
        final long identity = Binder.clearCallingIdentity();
        try {
            int flags = Context.BIND_AUTO_CREATE
                    | Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE
                    | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS
                    | Context.BIND_INCLUDE_CAPABILITIES;
            if (userState.getBindInstantServiceAllowedLocked()) {
                flags |= Context.BIND_ALLOW_INSTANT;
            }
            if (mService == null && mContext.bindServiceAsUser(
                    mIntent, this, flags, new UserHandle(userState.mUserId))) {
                userState.getBindingServicesLocked().add(mComponentName);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        mActivityTaskManagerService.setAllowAppSwitches(mComponentName.flattenToString(),
                mAccessibilityServiceInfo.getResolveInfo().serviceInfo.applicationInfo.uid,
                userState.mUserId);
    }

    public void unbindLocked() {
        if (requestImeApis()) {
            mSystemSupport.unbindImeLocked(this);
        }
        mContext.unbindService(this);
        AccessibilityUserState userState = mUserStateWeakReference.get();
        if (userState == null) return;
        userState.removeServiceLocked(this);
        mSystemSupport.getMagnificationProcessor().resetAllIfNeeded(mId);
        mActivityTaskManagerService.setAllowAppSwitches(mComponentName.flattenToString(), -1,
                userState.mUserId);
        resetLocked();
    }

    public boolean canRetrieveInteractiveWindowsLocked() {
        return mSecurityPolicy.canRetrieveWindowContentLocked(this) && mRetrieveInteractiveWindows;
    }

    @Override
    public void disableSelf() {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("disableSelf", "");
        }
        synchronized (mLock) {
            AccessibilityUserState userState = mUserStateWeakReference.get();
            if (userState == null) return;
            if (userState.getEnabledServicesLocked().remove(mComponentName)) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    mSystemSupport.persistComponentNamesToSettingLocked(
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                            userState.getEnabledServicesLocked(), userState.mUserId);

                    mSystemSupport.onClientChangeLocked(false);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        }
    }

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        AccessibilityUserState userState = mUserStateWeakReference.get();
        if (userState != null && Flags.addWindowTokenWithoutLock()) {
            addWindowTokensForAllDisplays();
        }
        synchronized (mLock) {
            if (mService != service) {
                if (mService != null) {
                    mService.unlinkToDeath(this, 0);
                }
                mService = service;
                try {
                    mService.linkToDeath(this, 0);
                } catch (RemoteException re) {
                    Slog.e(LOG_TAG, "Failed registering death link");
                    binderDied();
                    return;
                }
            }
            mServiceInterface = IAccessibilityServiceClient.Stub.asInterface(service);
            if (userState == null) return;
            userState.addServiceLocked(this);
            mSystemSupport.onClientChangeLocked(false);
            // Initialize the service on the main handler after we're done setting up for
            // the new configuration (for example, initializing the input filter).
            mMainHandler.sendMessage(obtainMessage(
                    AccessibilityServiceConnection::initializeService, this));
            if (requestImeApis()) {
                mSystemSupport.requestImeLocked(this);
            }
        }
    }

    @Override
    public AccessibilityServiceInfo getServiceInfo() {
        return mAccessibilityServiceInfo;
    }

    private void initializeService() {
        IAccessibilityServiceClient serviceInterface = null;
        synchronized (mLock) {
            AccessibilityUserState userState = mUserStateWeakReference.get();
            if (userState == null) return;
            final Set<ComponentName> bindingServices = userState.getBindingServicesLocked();
            final Set<ComponentName> crashedServices = userState.getCrashedServicesLocked();
            if (bindingServices.contains(mComponentName)
                    || crashedServices.contains(mComponentName)) {
                bindingServices.remove(mComponentName);
                crashedServices.remove(mComponentName);
                mAccessibilityServiceInfo.crashed = false;
                serviceInterface = mServiceInterface;
            }
            // There's a chance that service is removed from enabled_accessibility_services setting
            // key, but skip unbinding because of it's in binding state. Unbinds it if it's
            // not in enabled service list.
            if (serviceInterface != null
                    && !userState.getEnabledServicesLocked().contains(mComponentName)) {
                mSystemSupport.onClientChangeLocked(false);
                return;
            }
        }
        if (serviceInterface == null) {
            binderDied();
            return;
        }
        try {
            if (svcClientTracingEnabled()) {
                logTraceSvcClient("init",
                        this + "," + mId + "," + mOverlayWindowTokens.get(Display.DEFAULT_DISPLAY));
            }
            serviceInterface.init(this, mId, mOverlayWindowTokens.get(Display.DEFAULT_DISPLAY));
        } catch (RemoteException re) {
            Slog.w(LOG_TAG, "Error while setting connection for service: "
                    + serviceInterface, re);
            binderDied();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
        binderDied();
        AccessibilityUserState userState = mUserStateWeakReference.get();
        if (userState != null) {
            mActivityTaskManagerService.setAllowAppSwitches(mComponentName.flattenToString(), -1,
                    userState.mUserId);
        }
    }

    @Override
    protected boolean hasRightsToCurrentUserLocked() {
        // We treat calls from a profile as if made by its parent as profiles
        // share the accessibility state of the parent. The call below
        // performs the current profile parent resolution.
        final int callingUid = Binder.getCallingUid();
        if (callingUid == Process.ROOT_UID
                || callingUid == Process.SYSTEM_UID
                || callingUid == Process.SHELL_UID) {
            return true;
        }
        if (mSecurityPolicy.resolveProfileParentLocked(UserHandle.getUserId(callingUid))
                == mSystemSupport.getCurrentUserIdLocked()) {
            return true;
        }
        if (mSecurityPolicy.hasPermission(Manifest.permission.INTERACT_ACROSS_USERS)
                || mSecurityPolicy.hasPermission(Manifest.permission.INTERACT_ACROSS_USERS_FULL)) {
            return true;
        }
        return false;
    }

    @Override
    public boolean setSoftKeyboardShowMode(int showMode) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("setSoftKeyboardShowMode", "showMode=" + showMode);
        }
        synchronized (mLock) {
            if (!hasRightsToCurrentUserLocked()) {
                return false;
            }
            final AccessibilityUserState userState = mUserStateWeakReference.get();
            if (userState == null) return false;

            final long identity = Binder.clearCallingIdentity();
            try {
                return userState.setSoftKeyboardModeLocked(showMode, mComponentName);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public int getSoftKeyboardShowMode() {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("getSoftKeyboardShowMode", "");
        }
        final AccessibilityUserState userState = mUserStateWeakReference.get();
        final long identity = Binder.clearCallingIdentity();
        try {
            return (userState != null) ? userState.getSoftKeyboardShowModeLocked() : 0;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public boolean switchToInputMethod(String imeId) {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("switchToInputMethod", "imeId=" + imeId);
        }
        synchronized (mLock) {
            if (!hasRightsToCurrentUserLocked()) {
                return false;
            }
        }
        final boolean result;
        final int callingUserId = UserHandle.getCallingUserId();
        final long identity = Binder.clearCallingIdentity();
        try {
            result = InputMethodManagerInternal.get().switchToInputMethod(imeId, callingUserId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return result;
    }

    @Override
    @AccessibilityService.SoftKeyboardController.EnableImeResult
    public int setInputMethodEnabled(String imeId, boolean enabled) throws SecurityException {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("switchToInputMethod", "imeId=" + imeId);
        }
        synchronized (mLock) {
            if (!hasRightsToCurrentUserLocked()) {
                return ENABLE_IME_FAIL_UNKNOWN;
            }
        }

        final int callingUserId = UserHandle.getCallingUserId();
        final InputMethodManagerInternal inputMethodManagerInternal =
                InputMethodManagerInternal.get();

        final @AccessibilityService.SoftKeyboardController.EnableImeResult int checkResult;
        final long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                checkResult = mSecurityPolicy.canEnableDisableInputMethod(imeId, this);
            }
            if (checkResult != ENABLE_IME_SUCCESS) {
                return checkResult;
            }
            if (inputMethodManagerInternal.setInputMethodEnabled(imeId,
                        enabled, callingUserId)) {
                return ENABLE_IME_SUCCESS;
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return ENABLE_IME_FAIL_UNKNOWN;
    }

    @Override
    public boolean isAccessibilityButtonAvailable() {
        if (svcConnTracingEnabled()) {
            logTraceSvcConn("isAccessibilityButtonAvailable", "");
        }
        synchronized (mLock) {
            if (!hasRightsToCurrentUserLocked()) {
                return false;
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                AccessibilityUserState userState = mUserStateWeakReference.get();
                return (userState != null) && isAccessibilityButtonAvailableLocked(userState);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @Override
    public void binderDied() {
        synchronized (mLock) {
            // It is possible that this service's package was force stopped during
            // whose handling the death recipient is unlinked and still get a call
            // on binderDied since the call was made before we unlink but was
            // waiting on the lock we held during the force stop handling.
            if (!isConnectedLocked()) {
                return;
            }
            if (requestImeApis()) {
                mSystemSupport.unbindImeLocked(this);
            }
            mAccessibilityServiceInfo.crashed = true;
            AccessibilityUserState userState = mUserStateWeakReference.get();
            if (userState != null) {
                userState.serviceDisconnectedLocked(this);
            }
            resetLocked();
            mSystemSupport.getMagnificationProcessor().resetAllIfNeeded(mId);
            mSystemSupport.onClientChangeLocked(false);
        }
    }

    @Override
    public void resetLocked() {
        super.resetLocked();
        if (android.view.accessibility.Flags.brailleDisplayHid()) {
            if (mBrailleDisplayConnection != null) {
                mBrailleDisplayConnection.disconnect();
            }
        }
    }

    public boolean isAccessibilityButtonAvailableLocked(AccessibilityUserState userState) {
        // If the service does not request the accessibility button, it isn't available
        if (!mRequestAccessibilityButton) {
            return false;
        }
        // If the accessibility button isn't currently shown, it cannot be available to services
        if (!mSystemSupport.isAccessibilityButtonShown()) {
            return false;
        }
        return true;
    }

    @Override
    public boolean isCapturingFingerprintGestures() {
        return (mServiceInterface != null)
                && mSecurityPolicy.canCaptureFingerprintGestures(this)
                && mCaptureFingerprintGestures;
    }

    @Override
    public void onFingerprintGestureDetectionActiveChanged(boolean active) {
        if (!isCapturingFingerprintGestures()) {
            return;
        }
        IAccessibilityServiceClient serviceInterface;
        synchronized (mLock) {
            serviceInterface = mServiceInterface;
        }
        if (serviceInterface != null) {
            try {
                if (svcClientTracingEnabled()) {
                    logTraceSvcClient(
                            "onFingerprintCapturingGesturesChanged", String.valueOf(active));
                }
                mServiceInterface.onFingerprintCapturingGesturesChanged(active);
            } catch (RemoteException e) {
            }
        }
    }

    @Override
    public void onFingerprintGesture(int gesture) {
        if (!isCapturingFingerprintGestures()) {
            return;
        }
        IAccessibilityServiceClient serviceInterface;
        synchronized (mLock) {
            serviceInterface = mServiceInterface;
        }
        if (serviceInterface != null) {
            try {
                if (svcClientTracingEnabled()) {
                    logTraceSvcClient("onFingerprintGesture", String.valueOf(gesture));
                }
                mServiceInterface.onFingerprintGesture(gesture);
            } catch (RemoteException e) {
            }
        }
    }

    @Override
    public void dispatchGesture(int sequence, ParceledListSlice gestureSteps, int displayId) {
        synchronized (mLock) {
            if (mServiceInterface != null && mSecurityPolicy.canPerformGestures(this)) {
                final long identity = Binder.clearCallingIdentity();
                try {
                    MotionEventInjector motionEventInjector =
                            mSystemSupport.getMotionEventInjectorForDisplayLocked(displayId);
                    if (wmTracingEnabled()) {
                        logTraceWM("isTouchOrFaketouchDevice", "");
                    }
                    if (motionEventInjector != null
                            && mWindowManagerService.isTouchOrFaketouchDevice()) {
                        motionEventInjector.injectEvents(
                                gestureSteps.getList(), mServiceInterface, sequence, displayId);
                    } else {
                        try {
                            if (svcClientTracingEnabled()) {
                                logTraceSvcClient("onPerformGestureResult", sequence + ", false");
                            }
                            mServiceInterface.onPerformGestureResult(sequence, false);
                        } catch (RemoteException re) {
                            Slog.e(LOG_TAG, "Error sending motion event injection failure to "
                                    + mServiceInterface, re);
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }

            }
        }
    }

    @Override
    public void setFocusAppearance(int strokeWidth, int color) {
        AccessibilityUserState userState = mUserStateWeakReference.get();
        if (userState == null) {
            return;
        }

        synchronized (mLock) {
            if (!hasRightsToCurrentUserLocked()) {
                return;
            }

            if (!mSecurityPolicy.checkAccessibilityAccess(this)) {
                return;
            }

            if (userState.getFocusStrokeWidthLocked() == strokeWidth
                    && userState.getFocusColorLocked() == color) {
                return;
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                // Sets the appearance data in the A11yUserState.
                userState.setFocusAppearanceLocked(strokeWidth, color);
                // Updates the appearance data in the A11yManager.
                mSystemSupport.onClientChangeLocked(false);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    public void notifyMotionEvent(MotionEvent event) {
        final Message msg = obtainMessage(
                AccessibilityServiceConnection::notifyMotionEventInternal,
                AccessibilityServiceConnection.this, event);
        mMainHandler.sendMessage(msg);
    }

    public void notifyTouchState(int displayId, int state) {
        final Message msg = obtainMessage(
                AccessibilityServiceConnection::notifyTouchStateInternal,
                AccessibilityServiceConnection.this, displayId, state);
        mMainHandler.sendMessage(msg);
    }

    public boolean requestImeApis() {
        return mRequestImeApis;
    }

    @Override
    protected void createImeSessionInternal() {
        final IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                if (svcClientTracingEnabled()) {
                    logTraceSvcClient("createImeSession", "");
                }
                AccessibilityInputMethodSessionCallback
                        callback = new AccessibilityInputMethodSessionCallback(mUserId);
                listener.createImeSession(callback);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG,
                        "Error requesting IME session from " + mService, re);
            }
        }
    }

    private void notifyMotionEventInternal(MotionEvent event) {
        final IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                if (mTrace.isA11yTracingEnabled()) {
                    logTraceSvcClient(".onMotionEvent ",
                            event.toString());
                }
                listener.onMotionEvent(event);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error sending motion event to" + mService, re);
            }
        }
    }

    private void notifyTouchStateInternal(int displayId, int state) {
        final IAccessibilityServiceClient listener = getServiceInterfaceSafely();
        if (listener != null) {
            try {
                if (mTrace.isA11yTracingEnabled()) {
                    logTraceSvcClient(".onTouchStateChanged ",
                            TouchInteractionController.stateToString(state));
                }
                listener.onTouchStateChanged(displayId, state);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error sending motion event to" + mService, re);
            }
        }
    }

    private void checkAccessibilityAccessLocked() {
        if (!hasRightsToCurrentUserLocked()
                || !mSecurityPolicy.checkAccessibilityAccess(this)) {
            throw new SecurityException("Caller does not have accessibility access");
        }
    }

    /**
     * Sets up a BrailleDisplayConnection interface for the requested Bluetooth-connected
     * Braille display.
     *
     * @param bluetoothAddress The address from
     *                         {@link android.bluetooth.BluetoothDevice#getAddress()}.
     */
    @Override
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    public void connectBluetoothBrailleDisplay(
            @NonNull String bluetoothAddress, @NonNull IBrailleDisplayController controller) {
        if (!android.view.accessibility.Flags.brailleDisplayHid()) {
            throw new IllegalStateException("Flag BRAILLE_DISPLAY_HID not enabled");
        }
        Objects.requireNonNull(bluetoothAddress);
        Objects.requireNonNull(controller);
        mContext.enforceCallingPermission(Manifest.permission.BLUETOOTH_CONNECT,
                "Missing BLUETOOTH_CONNECT permission");
        if (!BluetoothAdapter.checkBluetoothAddress(bluetoothAddress)) {
            throw new IllegalArgumentException(
                    bluetoothAddress + " is not a valid Bluetooth address");
        }
        synchronized (mLock) {
            checkAccessibilityAccessLocked();
            if (mBrailleDisplayConnection != null) {
                throw new IllegalStateException(
                        "This service already has a connected Braille display");
            }
            BrailleDisplayConnection connection = new BrailleDisplayConnection(mLock, this);
            if (mTestBrailleDisplays != null) {
                connection.setTestData(mTestBrailleDisplays);
            }
            connection.connectLocked(
                    bluetoothAddress, BrailleDisplayConnection.BUS_BLUETOOTH, controller);
        }
    }

    /**
     * Sets up a BrailleDisplayConnection interface for the requested USB-connected
     * Braille display.
     *
     * <p>The caller package must already have USB permission for this {@link UsbDevice}.
     */
    @SuppressLint("MissingPermission") // system_server has the required MANAGE_USB permission
    @Override
    @NonNull
    public void connectUsbBrailleDisplay(@NonNull UsbDevice usbDevice,
            @NonNull IBrailleDisplayController controller) {
        if (!android.view.accessibility.Flags.brailleDisplayHid()) {
            throw new IllegalStateException("Flag BRAILLE_DISPLAY_HID not enabled");
        }
        Objects.requireNonNull(usbDevice);
        Objects.requireNonNull(controller);
        final UsbManager usbManager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
        final String usbSerialNumber;
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final long identity = Binder.clearCallingIdentity();
        try {
            if (usbManager == null || !usbManager.hasPermission(
                    usbDevice, mComponentName.getPackageName(), /*pid=*/ pid, /*uid=*/ uid)) {
                throw new SecurityException(
                        "Caller does not have permission to access this UsbDevice");
            }
            usbSerialNumber = usbDevice.getSerialNumber();
            if (TextUtils.isEmpty(usbSerialNumber)) {
                // If the UsbDevice does not report a serial number for locating the HIDRAW
                // node then notify connection error ERROR_BRAILLE_DISPLAY_NOT_FOUND.
                try {
                    controller.onConnectionFailed(BrailleDisplayController.BrailleDisplayCallback
                            .FLAG_ERROR_BRAILLE_DISPLAY_NOT_FOUND);
                } catch (RemoteException e) {
                    Slog.e(LOG_TAG, "Error calling onConnectionFailed", e);
                }
                return;
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        synchronized (mLock) {
            checkAccessibilityAccessLocked();
            if (mBrailleDisplayConnection != null) {
                throw new IllegalStateException(
                        "This service already has a connected Braille display");
            }
            BrailleDisplayConnection connection = new BrailleDisplayConnection(mLock, this);
            if (mTestBrailleDisplays != null) {
                connection.setTestData(mTestBrailleDisplays);
            }
            connection.connectLocked(
                    usbSerialNumber, BrailleDisplayConnection.BUS_USB, controller);
        }
    }

    @Override
    @RequiresPermission(Manifest.permission.MANAGE_ACCESSIBILITY)
    public void setTestBrailleDisplayData(List<Bundle> brailleDisplays) {
        // Enforce that this TestApi is only called by trusted (test) callers.
        mContext.enforceCallingPermission(Manifest.permission.MANAGE_ACCESSIBILITY,
                "Missing MANAGE_ACCESSIBILITY permission");
        mTestBrailleDisplays = brailleDisplays;
    }

    void onBrailleDisplayConnectedLocked(BrailleDisplayConnection connection) {
        mBrailleDisplayConnection = connection;
    }

    // Reset state when the BrailleDisplayConnection object disconnects itself.
    void onBrailleDisplayDisconnectedLocked() {
        mBrailleDisplayConnection = null;
    }
}
