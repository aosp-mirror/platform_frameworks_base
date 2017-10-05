package android.app;

import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.RemoteException;
import android.service.vr.IPersistentVrStateCallbacks;
import android.service.vr.IVrManager;
import android.service.vr.IVrStateCallbacks;
import android.util.ArrayMap;

import java.util.Map;

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
                mHandler.post(() -> mCallback.onVrStateChanged(enabled));
            }

        };
        final IPersistentVrStateCallbacks mPersistentStateCallback =
                new IPersistentVrStateCallbacks.Stub() {
            @Override
            public void onPersistentVrStateChanged(boolean enabled) {
                mHandler.post(() -> mCallback.onPersistentVrStateChanged(enabled));
            }
        };
        final VrStateCallback mCallback;
        final Handler mHandler;

        CallbackEntry(VrStateCallback callback, Handler handler) {
            mCallback = callback;
            mHandler = handler;
        }
    }

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
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.RESTRICTED_VR_ACCESS,
            android.Manifest.permission.ACCESS_VR_STATE
    })
    public void registerVrStateCallback(VrStateCallback callback, @NonNull Handler handler) {
        if (callback == null || mCallbackMap.containsKey(callback)) {
            return;
        }

        CallbackEntry entry = new CallbackEntry(callback, handler);
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
     * @hide
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
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.RESTRICTED_VR_ACCESS,
            android.Manifest.permission.ACCESS_VR_STATE
    })
    public boolean getVrModeEnabled() {
        try {
            return mService.getVrModeState();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return false;
    }

    /**
     * Returns the current VrMode state.
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.RESTRICTED_VR_ACCESS,
            android.Manifest.permission.ACCESS_VR_STATE
    })
    public boolean getPersistentVrModeEnabled() {
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
     * {@hide}
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
}
