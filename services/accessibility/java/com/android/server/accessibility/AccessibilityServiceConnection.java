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

import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Slog;

import com.android.server.accessibility.AccessibilityManagerService.SecurityPolicy;
import com.android.server.accessibility.AccessibilityManagerService.UserState;
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
    /*
     Holding a weak reference so there isn't a loop of references. UserState keeps lists of bound
     and binding services. These are freed on user changes, but just in case it somehow gets lost
     the weak reference will let the memory get GCed.

     Having the reference be null when being called is a very bad sign, but we check the condition.
    */
    final WeakReference<UserState> mUserStateWeakReference;
    final Intent mIntent;

    private final Handler mMainHandler;

    private boolean mWasConnectedAndDied;


    public AccessibilityServiceConnection(UserState userState, Context context,
            ComponentName componentName,
            AccessibilityServiceInfo accessibilityServiceInfo, int id, Handler mainHandler,
            Object lock, SecurityPolicy securityPolicy, SystemSupport systemSupport,
            WindowManagerInternal windowManagerInternal,
            GlobalActionPerformer globalActionPerfomer) {
        super(context, componentName, accessibilityServiceInfo, id, mainHandler, lock,
                securityPolicy, systemSupport, windowManagerInternal, globalActionPerfomer);
        mUserStateWeakReference = new WeakReference<UserState>(userState);
        mIntent = new Intent().setComponent(mComponentName);
        mMainHandler = mainHandler;
        mIntent.putExtra(Intent.EXTRA_CLIENT_LABEL,
                com.android.internal.R.string.accessibility_binding_label);
        final long identity = Binder.clearCallingIdentity();
        try {
            mIntent.putExtra(Intent.EXTRA_CLIENT_INTENT, mSystemSupport.getPendingIntentActivity(
                    mContext, 0, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), 0));
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void bindLocked() {
        UserState userState = mUserStateWeakReference.get();
        if (userState == null) return;
        final long identity = Binder.clearCallingIdentity();
        try {
            int flags = Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE
                    | Context.BIND_ALLOW_BACKGROUND_ACTIVITY_STARTS;
            if (userState.getBindInstantServiceAllowed()) {
                flags |= Context.BIND_ALLOW_INSTANT;
            }
            if (mService == null && mContext.bindServiceAsUser(
                    mIntent, this, flags, new UserHandle(userState.mUserId))) {
                userState.getBindingServicesLocked().add(mComponentName);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public void unbindLocked() {
        mContext.unbindService(this);
        UserState userState = mUserStateWeakReference.get();
        if (userState == null) return;
        userState.removeServiceLocked(this);
        mSystemSupport.getMagnificationController().resetAllIfNeeded(mId);
        resetLocked();
    }

    public boolean canRetrieveInteractiveWindowsLocked() {
        return mSecurityPolicy.canRetrieveWindowContentLocked(this) && mRetrieveInteractiveWindows;
    }

    @Override
    public void disableSelf() {
        synchronized (mLock) {
            UserState userState = mUserStateWeakReference.get();
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
            UserState userState = mUserStateWeakReference.get();
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
        // Update crashed data
        mAccessibilityServiceInfo.crashed = mWasConnectedAndDied;
        return mAccessibilityServiceInfo;
    }

    private void initializeService() {
        IAccessibilityServiceClient serviceInterface = null;
        synchronized (mLock) {
            UserState userState = mUserStateWeakReference.get();
            if (userState == null) return;
            Set<ComponentName> bindingServices = userState.getBindingServicesLocked();
            if (bindingServices.contains(mComponentName) || mWasConnectedAndDied) {
                bindingServices.remove(mComponentName);
                mWasConnectedAndDied = false;
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
            serviceInterface.init(this, mId, mOverlayWindowToken);
        } catch (RemoteException re) {
            Slog.w(LOG_TAG, "Error while setting connection for service: "
                    + serviceInterface, re);
            binderDied();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
        binderDied();
    }

    @Override
    protected boolean isCalledForCurrentUserLocked() {
        // We treat calls from a profile as if made by its parent as profiles
        // share the accessibility state of the parent. The call below
        // performs the current profile parent resolution.
        final int resolvedUserId = mSecurityPolicy
                .resolveCallingUserIdEnforcingPermissionsLocked(UserHandle.USER_CURRENT);
        return resolvedUserId == mSystemSupport.getCurrentUserIdLocked();
    }

    @Override
    public boolean setSoftKeyboardShowMode(int showMode) {
        synchronized (mLock) {
            if (!isCalledForCurrentUserLocked()) {
                return false;
            }
            final UserState userState = mUserStateWeakReference.get();
            if (userState == null) return false;
            return userState.setSoftKeyboardModeLocked(showMode, mComponentName);
        }
    }

    @Override
    public int getSoftKeyboardShowMode() {
        final UserState userState = mUserStateWeakReference.get();
        return (userState != null) ? userState.getSoftKeyboardShowMode() : 0;
    }


    @Override
    public boolean isAccessibilityButtonAvailable() {
        synchronized (mLock) {
            if (!isCalledForCurrentUserLocked()) {
                return false;
            }
            UserState userState = mUserStateWeakReference.get();
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
            mWasConnectedAndDied = true;
            UserState userState = mUserStateWeakReference.get();
            if (userState != null) {
                userState.serviceDisconnectedLocked(this);
            }
            resetLocked();
            mSystemSupport.getMagnificationController().resetAllIfNeeded(mId);
            mSystemSupport.onClientChangeLocked(false);
        }
    }

    public boolean isAccessibilityButtonAvailableLocked(UserState userState) {
        // If the service does not request the accessibility button, it isn't available
        if (!mRequestAccessibilityButton) {
            return false;
        }

        // If the accessibility button isn't currently shown, it cannot be available to services
        if (!mSystemSupport.isAccessibilityButtonShown()) {
            return false;
        }

        // If magnification is on and assigned to the accessibility button, services cannot be
        if (userState.mIsNavBarMagnificationEnabled
                && userState.mIsNavBarMagnificationAssignedToAccessibilityButton) {
            return false;
        }

        int requestingServices = 0;
        for (int i = userState.mBoundServices.size() - 1; i >= 0; i--) {
            final AccessibilityServiceConnection service = userState.mBoundServices.get(i);
            if (service.mRequestAccessibilityButton) {
                requestingServices++;
            }
        }

        if (requestingServices == 1) {
            // If only a single service is requesting, it must be this service, and the
            // accessibility button is available to it
            return true;
        } else {
            // With more than one active service, we derive the target from the user's settings
            if (userState.mServiceAssignedToAccessibilityButton == null) {
                // If the user has not made an assignment, we treat the button as available to
                // all services until the user interacts with the button to make an assignment
                return true;
            } else {
                // If an assignment was made, it defines availability
                return mComponentName.equals(userState.mServiceAssignedToAccessibilityButton);
            }
        }
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
                mServiceInterface.onFingerprintGesture(gesture);
            } catch (RemoteException e) {
            }
        }
    }

    @Override
    public void sendGesture(int sequence, ParceledListSlice gestureSteps) {
        synchronized (mLock) {
            if (mSecurityPolicy.canPerformGestures(this)) {
                MotionEventInjector motionEventInjector =
                        mSystemSupport.getMotionEventInjectorLocked();
                if (motionEventInjector != null) {
                    motionEventInjector.injectEvents(
                            gestureSteps.getList(), mServiceInterface, sequence);
                } else {
                    try {
                        mServiceInterface.onPerformGestureResult(sequence, false);
                    } catch (RemoteException re) {
                        Slog.e(LOG_TAG, "Error sending motion event injection failure to "
                                + mServiceInterface, re);
                    }
                }
            }
        }
    }
}
