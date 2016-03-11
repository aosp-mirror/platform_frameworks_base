/**
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.server.vr;

import android.app.AppOpsManager;
import android.annotation.NonNull;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.vr.IVrListener;
import android.service.vr.VrListenerService;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.R;
import com.android.server.SystemService;
import com.android.server.vr.EnabledComponentsObserver.EnabledComponentChangeListener;
import com.android.server.utils.ManagedApplicationService;
import com.android.server.utils.ManagedApplicationService.BinderChecker;

import java.util.ArrayList;
import java.util.Set;

/**
 * Service tracking whether VR mode is active, and notifying listening services of state changes.
 * <p/>
 * Services running in system server may modify the state of VrManagerService via the interface in
 * VrManagerInternal, and may register to receive callbacks when the system VR mode changes via the
 * interface given in VrStateListener.
 * <p/>
 * Device vendors may choose to receive VR state changes by implementing the VR mode HAL, e.g.:
 *  hardware/libhardware/modules/vr
 * <p/>
 * In general applications may enable or disable VR mode by calling
 * {@link android.app.Activity#setVrMode)}.  An application may also implement a service to be run
 * while in VR mode by implementing {@link android.service.vr.VrListenerService}.
 *
 * @see {@link android.service.vr.VrListenerService}
 * @see {@link com.android.server.vr.VrManagerInternal}
 * @see {@link com.android.server.vr.VrStateListener}
 *
 * @hide
 */
public class VrManagerService extends SystemService implements EnabledComponentChangeListener{

    public static final String TAG = "VrManagerService";

    private static native void initializeNative();
    private static native void setVrModeNative(boolean enabled);

    private final Object mLock = new Object();

    private final IBinder mOverlayToken = new Binder();

    // State protected by mLock
    private boolean mVrModeEnabled = false;
    private final Set<VrStateListener> mListeners = new ArraySet<>();
    private EnabledComponentsObserver mComponentObserver;
    private ManagedApplicationService mCurrentVrService;
    private Context mContext;

    private static final BinderChecker sBinderChecker = new BinderChecker() {
        @Override
        public IInterface asInterface(IBinder binder) {
            return IVrListener.Stub.asInterface(binder);
        }

        @Override
        public boolean checkType(IInterface service) {
            return service instanceof IVrListener;
        }
    };

    /**
     * Called when a user, package, or setting changes that could affect whether or not the
     * currently bound VrListenerService is changed.
     */
    @Override
    public void onEnabledComponentChanged() {
        synchronized (mLock) {
            if (mCurrentVrService == null) {
                return; // No active services
            }

            // There is an active service, update it if needed
            updateCurrentVrServiceLocked(mVrModeEnabled, mCurrentVrService.getComponent(),
                    mCurrentVrService.getUserId());
        }
    }

    /**
     * Implementation of VrManagerInternal.  Callable only from system services.
     */
    private final class LocalService extends VrManagerInternal {
        @Override
        public boolean isInVrMode() {
            return VrManagerService.this.getVrMode();
        }

        @Override
        public void setVrMode(boolean enabled, ComponentName packageName, int userId) {
            VrManagerService.this.setVrMode(enabled, packageName, userId);
        }

        @Override
        public void registerListener(VrStateListener listener) {
            VrManagerService.this.addListener(listener);
        }

        @Override
        public void unregisterListener(VrStateListener listener) {
            VrManagerService.this.removeListener(listener);
        }

        @Override
        public int hasVrPackage(ComponentName packageName, int userId) {
            return VrManagerService.this.hasVrPackage(packageName, userId);
        }
    }

    public VrManagerService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        synchronized(mLock) {
            initializeNative();
            mContext = getContext();
        }

        publishLocalService(VrManagerInternal.class, new LocalService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            synchronized (mLock) {
                Looper looper = Looper.getMainLooper();
                Handler handler = new Handler(looper);
                ArrayList<EnabledComponentChangeListener> listeners = new ArrayList<>();
                listeners.add(this);
                mComponentObserver = EnabledComponentsObserver.build(mContext, handler,
                        Settings.Secure.ENABLED_VR_LISTENERS, looper,
                        android.Manifest.permission.BIND_VR_LISTENER_SERVICE,
                        VrListenerService.SERVICE_INTERFACE, mLock, listeners);

                mComponentObserver.rebuildAll();
            }
        }
    }

    @Override
    public void onStartUser(int userHandle) {
        synchronized (mLock) {
            mComponentObserver.onUsersChanged();
        }
    }

    @Override
    public void onSwitchUser(int userHandle) {
        synchronized (mLock) {
            mComponentObserver.onUsersChanged();
        }

    }

    @Override
    public void onStopUser(int userHandle) {
        synchronized (mLock) {
            mComponentObserver.onUsersChanged();
        }

    }

    @Override
    public void onCleanupUser(int userHandle) {
        synchronized (mLock) {
            mComponentObserver.onUsersChanged();
        }
    }

    private void updateOverlayStateLocked(ComponentName exemptedComponent) {
        final long identity = Binder.clearCallingIdentity();
        try {
            AppOpsManager appOpsManager = getContext().getSystemService(AppOpsManager.class);
            if (appOpsManager != null) {
                String[] exemptions = (exemptedComponent == null) ? new String[0] :
                        new String[] { exemptedComponent.getPackageName() };

                appOpsManager.setUserRestriction(AppOpsManager.OP_SYSTEM_ALERT_WINDOW,
                        mVrModeEnabled, mOverlayToken, exemptions);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Send VR mode changes (if the mode state has changed), and update the bound/unbound state of
     * the currently selected VR listener service.  If the component selected for the VR listener
     * service has changed, unbind the previous listener and bind the new listener (if enabled).
     * <p/>
     * Note: Must be called while holding {@code mLock}.
     *
     * @param enabled new state for VR mode.
     * @param component new component to be bound as a VR listener.
     * @param userId user owning the component to be bound.
     *
     * @return {@code true} if the component/user combination specified is valid.
     */
    private boolean updateCurrentVrServiceLocked(boolean enabled,
            @NonNull ComponentName component, int userId) {

        boolean validUserComponent = (mComponentObserver.isValid(component, userId) ==
                EnabledComponentsObserver.NO_ERROR);

        // Always send mode change events.
        changeVrModeLocked(enabled, (enabled && validUserComponent) ? component : null);

        if (!enabled || !validUserComponent) {
            // Unbind whatever is running
            if (mCurrentVrService != null) {
                Slog.i(TAG, "Disconnecting " + mCurrentVrService.getComponent() + " for user " +
                        mCurrentVrService.getUserId());
                mCurrentVrService.disconnect();
                mCurrentVrService = null;
            }
            return validUserComponent;
        }

        if (mCurrentVrService != null) {
            // Unbind any running service that doesn't match the component/user selection
            if (mCurrentVrService.disconnectIfNotMatching(component, userId)) {
                Slog.i(TAG, "Disconnecting " + mCurrentVrService.getComponent() + " for user " +
                        mCurrentVrService.getUserId());
                mCurrentVrService = VrManagerService.create(mContext, component, userId);
                mCurrentVrService.connect();
                Slog.i(TAG, "Connecting " + mCurrentVrService.getComponent() + " for user " +
                        mCurrentVrService.getUserId());
            }
            // The service with the correct component/user is bound
        } else {
            // Nothing was previously running, bind a new service
            mCurrentVrService = VrManagerService.create(mContext, component, userId);
            mCurrentVrService.connect();
            Slog.i(TAG, "Connecting " + mCurrentVrService.getComponent() + " for user " +
                    mCurrentVrService.getUserId());
        }

        return validUserComponent;
    }

    /**
     * Send VR mode change callbacks to HAL and system services if mode has actually changed.
     * <p/>
     * Note: Must be called while holding {@code mLock}.
     *
     * @param enabled new state of the VR mode.
     * @param exemptedComponent a component to exempt from AppOps restrictions for overlays.
     */
    private void changeVrModeLocked(boolean enabled, ComponentName exemptedComponent) {
        if (mVrModeEnabled != enabled) {
            mVrModeEnabled = enabled;

            // Log mode change event.
            Slog.i(TAG, "VR mode " + ((mVrModeEnabled) ? "enabled" : "disabled"));
            setVrModeNative(mVrModeEnabled);

            updateOverlayStateLocked(exemptedComponent);
            onVrModeChangedLocked();
        }
    }

    /**
     * Notify system services of VR mode change.
     * <p/>
     * Note: Must be called while holding {@code mLock}.
     */
    private void onVrModeChangedLocked() {
        for (VrStateListener l : mListeners) {
            l.onVrStateChanged(mVrModeEnabled);
        }
    }

    /**
     * Helper function for making ManagedApplicationService instances.
     */
    private static ManagedApplicationService create(@NonNull Context context,
            @NonNull ComponentName component, int userId) {
        return ManagedApplicationService.build(context, component, userId,
                R.string.vr_listener_binding_label, Settings.ACTION_VR_LISTENER_SETTINGS,
                sBinderChecker);
    }

    /*
     * Implementation of VrManagerInternal calls.  These are callable from system services.
     */

    private boolean setVrMode(boolean enabled, @NonNull ComponentName targetPackageName,
            int userId) {
        synchronized (mLock) {
            return updateCurrentVrServiceLocked(enabled, targetPackageName, userId);
        }
    }

    private boolean getVrMode() {
        synchronized (mLock) {
            return mVrModeEnabled;
        }
    }

    private void addListener(VrStateListener listener) {
        synchronized (mLock) {
            mListeners.add(listener);
        }
    }

    private void removeListener(VrStateListener listener) {
        synchronized (mLock) {
            mListeners.remove(listener);
        }
    }

    private int hasVrPackage(@NonNull ComponentName targetPackageName, int userId) {
        synchronized (mLock) {
            return mComponentObserver.isValid(targetPackageName, userId);
        }
    }
}
