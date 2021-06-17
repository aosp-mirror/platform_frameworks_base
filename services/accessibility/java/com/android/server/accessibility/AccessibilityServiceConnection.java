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

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;
import android.view.Display;

import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerInternal;

import java.lang.ref.WeakReference;
import java.util.Set;

/**
 * This class represents an accessibility service. It stores all per service
 * data required for the service management, provides API for starting/stopping the
 * service and is responsible for adding/removing the service in the data structures
 * for service management. The class also exposes configuration interface that is
 * passed to the service it represents as soon it is bound. It also serves as the
 * connection for the service.
 */
class AccessibilityServiceConnection extends AbstractAccessibilityServiceConnection {
    private static final String LOG_TAG = "AccessibilityServiceConnection";
    private static final String TRACE_A11Y_SERVICE_CONNECTION =
            LOG_TAG + ".IAccessibilityServiceConnection";
    private static final String TRACE_A11Y_SERVICE_CLIENT =
            LOG_TAG + ".IAccessibilityServiceClient";
    /*
     Holding a weak reference so there isn't a loop of references. AccessibilityUserState keeps
     lists of bound and binding services. These are freed on user changes, but just in case it
     somehow gets lost the weak reference will let the memory get GCed.

     Having the reference be null when being called is a very bad sign, but we check the condition.
    */
    final WeakReference<AccessibilityUserState> mUserStateWeakReference;
    final Intent mIntent;
    final ActivityTaskManagerInternal mActivityTaskManagerService;

    private final Handler mMainHandler;

    AccessibilityServiceConnection(AccessibilityUserState userState, Context context,
            ComponentName componentName,
            AccessibilityServiceInfo accessibilityServiceInfo, int id, Handler mainHandler,
            Object lock, AccessibilitySecurityPolicy securityPolicy, SystemSupport systemSupport,
            AccessibilityTrace trace, WindowManagerInternal windowManagerInternal,
            SystemActionPerformer systemActionPerfomer, AccessibilityWindowManager awm,
            ActivityTaskManagerInternal activityTaskManagerService) {
        super(context, componentName, accessibilityServiceInfo, id, mainHandler, lock,
                securityPolicy, systemSupport, trace, windowManagerInternal, systemActionPerfomer,
                awm);
        mUserStateWeakReference = new WeakReference<AccessibilityUserState>(userState);
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
        mContext.unbindService(this);
        AccessibilityUserState userState = mUserStateWeakReference.get();
        if (userState == null) return;
        userState.removeServiceLocked(this);
        mSystemSupport.getFullScreenMagnificationController().resetAllIfNeeded(mId);
        mActivityTaskManagerService.setAllowAppSwitches(mComponentName.flattenToString(), -1,
                userState.mUserId);
        resetLocked();
    }

    public boolean canRetrieveInteractiveWindowsLocked() {
        return mSecurityPolicy.canRetrieveWindowContentLocked(this) && mRetrieveInteractiveWindows;
    }

    @Override
    public void disableSelf() {
        if (mTrace.isA11yTracingEnabled()) {
            mTrace.logTrace(TRACE_A11Y_SERVICE_CONNECTION + ".disableSelf");
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
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
                mSystemSupport.onClientChangeLocked(false);
            }
        }
    }

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
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
            AccessibilityUserState userState = mUserStateWeakReference.get();
            if (userState == null) return;
            userState.addServiceLocked(this);
            mSystemSupport.onClientChangeLocked(false);
            // Initialize the service on the main handler after we're done setting up for
            // the new configuration (for example, initializing the input filter).
            mMainHandler.sendMessage(obtainMessage(
                    AccessibilityServiceConnection::initializeService, this));
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
            if (mTrace.isA11yTracingEnabled()) {
                mTrace.logTrace(TRACE_A11Y_SERVICE_CLIENT + ".init", this + ", " + mId + ", "
                        + mOverlayWindowTokens.get(Display.DEFAULT_DISPLAY));
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
        if (mTrace.isA11yTracingEnabled()) {
            mTrace.logTrace(TRACE_A11Y_SERVICE_CONNECTION + ".setSoftKeyboardShowMode",
                    "showMode=" + showMode);
        }
        synchronized (mLock) {
            if (!hasRightsToCurrentUserLocked()) {
                return false;
            }
            final AccessibilityUserState userState = mUserStateWeakReference.get();
            if (userState == null) return false;
            return userState.setSoftKeyboardModeLocked(showMode, mComponentName);
        }
    }

    @Override
    public int getSoftKeyboardShowMode() {
        if (mTrace.isA11yTracingEnabled()) {
            mTrace.logTrace(TRACE_A11Y_SERVICE_CONNECTION + ".getSoftKeyboardShowMode");
        }
        final AccessibilityUserState userState = mUserStateWeakReference.get();
        return (userState != null) ? userState.getSoftKeyboardShowModeLocked() : 0;
    }

    @Override
    public boolean switchToInputMethod(String imeId) {
        if (mTrace.isA11yTracingEnabled()) {
            mTrace.logTrace(TRACE_A11Y_SERVICE_CONNECTION + ".switchToInputMethod",
                    "imeId=" + imeId);
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
    public boolean isAccessibilityButtonAvailable() {
        if (mTrace.isA11yTracingEnabled()) {
            mTrace.logTrace(TRACE_A11Y_SERVICE_CONNECTION + ".isAccessibilityButtonAvailable");
        }
        synchronized (mLock) {
            if (!hasRightsToCurrentUserLocked()) {
                return false;
            }
            AccessibilityUserState userState = mUserStateWeakReference.get();
            return (userState != null) && isAccessibilityButtonAvailableLocked(userState);
        }
    }

    public void binderDied() {
        synchronized (mLock) {
            // It is possible that this service's package was force stopped during
            // whose handling the death recipient is unlinked and still get a call
            // on binderDied since the call was made before we unlink but was
            // waiting on the lock we held during the force stop handling.
            if (!isConnectedLocked()) {
                return;
            }
            mAccessibilityServiceInfo.crashed = true;
            AccessibilityUserState userState = mUserStateWeakReference.get();
            if (userState != null) {
                userState.serviceDisconnectedLocked(this);
            }
            resetLocked();
            mSystemSupport.getFullScreenMagnificationController().resetAllIfNeeded(mId);
            mSystemSupport.onClientChangeLocked(false);
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
                if (mTrace.isA11yTracingEnabled()) {
                    mTrace.logTrace(TRACE_A11Y_SERVICE_CLIENT
                            + ".onFingerprintCapturingGesturesChanged", String.valueOf(active));
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
                if (mTrace.isA11yTracingEnabled()) {
                    mTrace.logTrace(TRACE_A11Y_SERVICE_CLIENT + ".onFingerprintGesture",
                            String.valueOf(gesture));
                }
                mServiceInterface.onFingerprintGesture(gesture);
            } catch (RemoteException e) {
            }
        }
    }

    @Override
    public void dispatchGesture(int sequence, ParceledListSlice gestureSteps, int displayId) {
        synchronized (mLock) {
            if (mSecurityPolicy.canPerformGestures(this)) {
                MotionEventInjector motionEventInjector =
                        mSystemSupport.getMotionEventInjectorForDisplayLocked(displayId);
                if (motionEventInjector != null
                        && mWindowManagerService.isTouchOrFaketouchDevice()) {
                    motionEventInjector.injectEvents(
                            gestureSteps.getList(), mServiceInterface, sequence, displayId);
                } else {
                    try {
                        if (mTrace.isA11yTracingEnabled()) {
                            mTrace.logTrace(TRACE_A11Y_SERVICE_CLIENT + ".onPerformGestureResult",
                                    sequence + ", false");
                        }
                        mServiceInterface.onPerformGestureResult(sequence, false);
                    } catch (RemoteException re) {
                        Slog.e(LOG_TAG, "Error sending motion event injection failure to "
                                + mServiceInterface, re);
                    }
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

            // Sets the appearance data in the A11yUserState.
            userState.setFocusAppearanceLocked(strokeWidth, color);
            // Updates the appearance data in the A11yManager.
            mSystemSupport.onClientChangeLocked(false);
        }
    }
}
