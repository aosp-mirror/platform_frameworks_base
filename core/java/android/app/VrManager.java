package android.app;


import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.ComponentName;
import android.content.Context;
import android.os.RemoteException;
import android.service.vr.IVrManager;

/**
 * Used to control aspects of a devices Virtual Reality (VR) capabilities.
 * @hide
 */
@SystemApi
@SystemService(Context.VR_SERVICE)
public class VrManager {
    private final IVrManager mService;

    /**
     * {@hide}
     */
    public VrManager(IVrManager service) {
        mService = service;
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
}
