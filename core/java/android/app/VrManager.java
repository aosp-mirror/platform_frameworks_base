package android.app;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.os.RemoteException;
import android.service.vr.IPersistentVrStateCallbacks;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.util.ArrayMap;
import android.view.Display;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Used to control aspects of a devices Virtual Reality (VR) capabilities.
 * @hide
 */
@SystemApi
@SystemService(Context.VR_SERVICE)
public class VrManager {

    private static class CallbackEntry {
        final IVrStateCallbacks mStateCallback = new IVrStateCallbacks.Stub() {
            @Override
            public void onVrStateChanged(boolean enabled) {
                mExecutor.execute(() -> mCallback.onVrStateChanged(enabled));
            }

        };
        final IPersistentVrStateCallbacks mPersistentStateCallback =
                new IPersistentVrStateCallbacks.Stub() {
            @Override
            public void onPersistentVrStateChanged(boolean enabled) {
                mExecutor.execute(() -> mCallback.onPersistentVrStateChanged(enabled));
            }
        };
        final VrStateCallback mCallback;
        final Executor mExecutor;

        CallbackEntry(VrStateCallback callback, Executor executor) {
            mCallback = callback;
            mExecutor = executor;
        }
    }

    @UnsupportedAppUsage
    private final IVrManager mService;
    private Map<VrStateCallback, CallbackEntry> mCallbackMap = new ArrayMap<>();

    /**
     * {@hide}
     */
    public VrManager(IVrManager service) {
        mService = service;
    }

    /**
     * Registers a callback to be notified of changes to the VR Mode state.
     *
     * @param callback The callback to register.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.RESTRICTED_VR_ACCESS,
            android.Manifest.permission.ACCESS_VR_STATE
    })
    public void registerVrStateCallback(@NonNull @CallbackExecutor Executor executor,
            VrStateCallback callback) {
        if (callback == null || mCallbackMap.containsKey(callback)) {
            return;
        }

        CallbackEntry entry = new CallbackEntry(callback, executor);
        mCallbackMap.put(callback, entry);
        try {
            mService.registerListener(entry.mStateCallback);
            mService.registerPersistentVrStateListener(entry.mPersistentStateCallback);
        } catch (RemoteException e) {
            try {
                unregisterVrStateCallback(callback);
            } catch (Exception ignore) {
                e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Deregisters VR State callbacks.
     *
     * @param callback The callback to deregister.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.RESTRICTED_VR_ACCESS,
            android.Manifest.permission.ACCESS_VR_STATE
    })
    public void unregisterVrStateCallback(VrStateCallback callback) {
        CallbackEntry entry = mCallbackMap.remove(callback);
        if (entry != null) {
            try {
                mService.unregisterListener(entry.mStateCallback);
            } catch (RemoteException ignore) {
                // Dont rethrow exceptions from requests to unregister.
            }

            try {
                mService.unregisterPersistentVrStateListener(entry.mPersistentStateCallback);
            } catch (RemoteException ignore) {
                // Dont rethrow exceptions from requests to unregister.
            }
        }
    }

    /**
     * Returns the current VrMode state.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.RESTRICTED_VR_ACCESS,
            android.Manifest.permission.ACCESS_VR_STATE
    })
    public boolean isVrModeEnabled() {
        try {
            return mService.getVrModeState();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return false;
    }

    /**
     * Returns the current VrMode state.
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.RESTRICTED_VR_ACCESS,
            android.Manifest.permission.ACCESS_VR_STATE
    })
    public boolean isPersistentVrModeEnabled() {
        try {
            return mService.getPersistentVrModeEnabled();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return false;
    }

    /**
     * Sets the persistent VR mode state of a device. When a device is in persistent VR mode it will
     * remain in VR mode even if the foreground does not specify Vr mode being enabled. Mainly used
     * by VR viewers to indicate that a device is placed in a VR viewer.
     *
     * @see Activity#setVrModeEnabled(boolean, ComponentName)
     * @param enabled true if the device should be placed in persistent VR mode.
     */
    @RequiresPermission(android.Manifest.permission.RESTRICTED_VR_ACCESS)
    public void setPersistentVrModeEnabled(boolean enabled) {
        try {
            mService.setPersistentVrModeEnabled(enabled);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the resolution and DPI of the vr2d virtual display used to display 2D
     * applications in VR mode.
     *
     * @param vr2dDisplayProp properties to be set to the virtual display for
     * 2D applications in VR mode.
     *
     */
    @RequiresPermission(android.Manifest.permission.RESTRICTED_VR_ACCESS)
    public void setVr2dDisplayProperties(
            Vr2dDisplayProperties vr2dDisplayProp) {
        try {
            mService.setVr2dDisplayProperties(vr2dDisplayProp);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the component name of the compositor service to bind.
     *
     * @param componentName ComponentName of a Service in the application's compositor process to
     * bind to, or null to clear the current binding.
     */
    @RequiresPermission(android.Manifest.permission.RESTRICTED_VR_ACCESS)
    public void setAndBindVrCompositor(ComponentName componentName) {
        try {
            mService.setAndBindCompositor(
                    (componentName == null) ? null : componentName.flattenToString());
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the current standby status of the VR device. Standby mode is only used on standalone vr
     * devices. Standby mode is a deep sleep state where it's appropriate to turn off vr mode.
     *
     * @param standby True if the device is entering standby, false if it's exiting standby.
     */
    @RequiresPermission(android.Manifest.permission.ACCESS_VR_MANAGER)
    public void setStandbyEnabled(boolean standby) {
        try {
            mService.setStandbyEnabled(standby);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * This method is not implemented.
     *
     * @param componentName not used
     */
    @RequiresPermission(android.Manifest.permission.RESTRICTED_VR_ACCESS)
    public void setVrInputMethod(ComponentName componentName) {
    }

    /**
     * Returns the display id of VR's {@link VirtualDisplay}.
     *
     * @see DisplayManager#getDisplay(int)
     */
    @RequiresPermission(android.Manifest.permission.RESTRICTED_VR_ACCESS)
    public int getVr2dDisplayId() {
        try {
            return mService.getVr2dDisplayId();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return Display.INVALID_DISPLAY;
    }
}
