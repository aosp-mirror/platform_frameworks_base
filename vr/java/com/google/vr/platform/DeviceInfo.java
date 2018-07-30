package com.google.vr.platform;

import android.os.SystemProperties;

/**
 * Class to get information about the vr device.
 * @hide
 */
public class DeviceInfo {

    private static final String VR_MODE_BOOT = "ro.boot.vr";

    /**
     * Returns true if this device boots directly in VR mode.
     */
    public static boolean getVrBoot() {
        return SystemProperties.getBoolean(VR_MODE_BOOT, false);
    }
}
