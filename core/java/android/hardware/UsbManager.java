/*
 * Copyright (C) 2010 The Android Open Source Project
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


package android.hardware;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Class for accessing USB state information.
 * @hide
 */
public class UsbManager {
   /**
     * Broadcast Action:  A sticky broadcast for USB state change events.
     *
     * This is a sticky broadcast for clients that includes USB connected/disconnected state,
     * the USB configuration that is currently set and a bundle containing name/value pairs
     * with the names of the functions and a value of either {@link #USB_FUNCTION_ENABLED}
     * or {@link #USB_FUNCTION_DISABLED}.
     * Possible USB function names include {@link #USB_FUNCTION_MASS_STORAGE},
     * {@link #USB_FUNCTION_ADB}, {@link #USB_FUNCTION_RNDIS} and {@link #USB_FUNCTION_MTP}.
     */
    public static final String ACTION_USB_STATE =
            "android.hardware.action.USB_STATE";

   /**
     * Broadcast Action:  A broadcast for USB camera attached event.
     *
     * This intent is sent when a USB device supporting PTP is attached to the host USB bus.
     * The intent's data contains a Uri for the device in the MTP provider.
     */
    public static final String ACTION_USB_CAMERA_ATTACHED =
            "android.hardware.action.USB_CAMERA_ATTACHED";

   /**
     * Broadcast Action:  A broadcast for USB camera detached event.
     *
     * This intent is sent when a USB device supporting PTP is detached from the host USB bus.
     * The intent's data contains a Uri for the device in the MTP provider.
     */
    public static final String ACTION_USB_CAMERA_DETACHED =
            "android.hardware.action.USB_CAMERA_DETACHED";

    /**
     * Boolean extra indicating whether USB is connected or disconnected.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast.
     */
    public static final String USB_CONNECTED = "connected";

    /**
     * Integer extra containing currently set USB configuration.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast.
     */
    public static final String USB_CONFIGURATION = "configuration";

    /**
     * Name of the USB mass storage USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     */
    public static final String USB_FUNCTION_MASS_STORAGE = "mass_storage";

    /**
     * Name of the adb USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     */
    public static final String USB_FUNCTION_ADB = "adb";

    /**
     * Name of the RNDIS ethernet USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     */
    public static final String USB_FUNCTION_RNDIS = "rndis";

    /**
     * Name of the MTP USB function.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     */
    public static final String USB_FUNCTION_MTP = "mtp";

    /**
     * Value indicating that a USB function is enabled.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     */
    public static final String USB_FUNCTION_ENABLED = "enabled";

    /**
     * Value indicating that a USB function is disabled.
     * Used in extras for the {@link #ACTION_USB_STATE} broadcast
     */
    public static final String USB_FUNCTION_DISABLED = "disabled";

    public static final int getDeviceId(String name) {
        return native_get_device_id(name);
    }

    public static final String getDeviceName(int id) {
        return native_get_device_name(id);
    }

    private static File getFunctionEnableFile(String function) {
        return new File("/sys/class/usb_composite/" + function + "/enable");
    }

    /**
     * Returns true if the specified USB function is supported by the kernel.
     * Note that a USB function maybe supported but disabled.
     */
    public static boolean isFunctionSupported(String function) {
        return getFunctionEnableFile(function).exists();
    }

    /**
     * Returns true if the specified USB function is currently enabled.
     */
    public static boolean isFunctionEnabled(String function) {
        try {
            FileInputStream stream = new FileInputStream(getFunctionEnableFile(function));
            boolean enabled = (stream.read() == '1');
            stream.close();
            return enabled;
        } catch (IOException e) {
            return false;
        }
    }

    private static native int native_get_device_id(String name);
    private static native String native_get_device_name(int id);
}
