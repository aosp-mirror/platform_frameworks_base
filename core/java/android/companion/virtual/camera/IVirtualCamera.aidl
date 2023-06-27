package android.companion.virtual.camera;

import android.companion.virtual.camera.IVirtualCameraSession;
import android.companion.virtual.camera.VirtualCameraHalConfig;

/**
 * Counterpart of ICameraDevice for virtual camera.
 *
 * @hide
 */
interface IVirtualCamera {

    IVirtualCameraSession open();

    VirtualCameraHalConfig getHalConfig();

}