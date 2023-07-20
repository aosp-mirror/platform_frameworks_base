/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.server.usb.hal.port;

import android.annotation.IntDef;
import android.hardware.usb.IUsbOperationInternal;
import android.hardware.usb.UsbManager.UsbHalVersion;
import android.os.RemoteException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.String;

/**
 * @hide
 */
public interface UsbPortHal {
    /**
     * Power role: This USB port can act as a source (provide power).
     * @hide
     */
    public static final int HAL_POWER_ROLE_SOURCE = 1;

    /**
     * Power role: This USB port can act as a sink (receive power).
     * @hide
     */
    public static final int HAL_POWER_ROLE_SINK = 2;

    @IntDef(prefix = { "HAL_POWER_ROLE_" }, value = {
            HAL_POWER_ROLE_SOURCE,
            HAL_POWER_ROLE_SINK
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface HalUsbPowerRole{}

    /**
     * Data role: This USB port can act as a host (access data services).
     * @hide
     */
    public static final int HAL_DATA_ROLE_HOST = 1;

    /**
     * Data role: This USB port can act as a device (offer data services).
     * @hide
     */
    public static final int HAL_DATA_ROLE_DEVICE = 2;

    @IntDef(prefix = { "HAL_DATA_ROLE_" }, value = {
            HAL_DATA_ROLE_HOST,
            HAL_DATA_ROLE_DEVICE
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface HalUsbDataRole{}

    /**
     * This USB port can act as a downstream facing port (host).
     *
     * @hide
     */
    public static final int HAL_MODE_UFP = 1;

    /**
     * This USB port can act as an upstream facing port (device).
     *
     * @hide
     */
    public static final int HAL_MODE_DFP = 2;
    @IntDef(prefix = { "HAL_MODE_" }, value = {
            HAL_MODE_UFP,
            HAL_MODE_DFP,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface HalUsbPortMode{}

    /**
     * UsbPortManager would call this when the system is done booting.
     */
    public void systemReady();

    /**
     * Invoked to enable/disable contaminant presence detection on the USB port.
     *
     * @param portName Port Identifier.
     * @param enable Enable contaminant presence detection when true.
     *               Disable when false.
     * @param transactionId Used for tracking the current request and is passed down to the HAL
     *                      implementation as needed.
     */
    public void enableContaminantPresenceDetection(String portName, boolean enable,
            long transactionId);

    /**
     * Invoked to query port status of all the ports.
     *
     * @param transactionId Used for tracking the current request and is passed down to the HAL
     *                      implementation as needed.
     */
    public void queryPortStatus(long transactionId);

    /**
     * Invoked to switch USB port mode.
     *
     * @param portName Port Identifier.
     * @param mode New mode that the port is switching into.
     * @param transactionId Used for tracking the current request and is passed down to the HAL
     *                      implementation as needed.
     */
    public void switchMode(String portName, @HalUsbPortMode int mode, long transactionId);

    /**
     * Invoked to switch USB port power role.
     *
     * @param portName Port Identifier.
     * @param powerRole New power role that the port is switching into.
     * @param transactionId Used for tracking the current request and is passed down to the HAL
     *                      implementation as needed.
     */
    public void switchPowerRole(String portName, @HalUsbPowerRole int powerRole,
            long transactionId);

    /**
     * Invoked to switch USB port data role.
     *
     * @param portName Port Identifier.
     * @param dataRole New data role that the port is switching into.
     * @param transactionId Used for tracking the current request and is passed down to the HAL
     *                      implementation as needed.
     */
    public void switchDataRole(String portName, @HalUsbDataRole int dataRole, long transactionId);

    /**
     * Invoked to query the version of current hal implementation.
     */
    public @UsbHalVersion int getUsbHalVersion() throws RemoteException;

    /**
     * Invoked to enable/disable UsbData on the specified port.
     *
     * @param portName Port Identifier.
     * @param enable Enable USB data when true.
     *               Disable when false.
     * @param transactionId Used for tracking the current request and is passed down to the HAL
     *                      implementation as needed.
     * @param callback callback object to be invoked to invoke the status of the operation upon
     *                 completion.
     * @param callback callback object to be invoked when the operation is complete.
     * @return True when the operation is asynchronous. The caller of
     *         {@link UsbOperationInternal} must therefore call
     *         {@link UsbOperationInternal#waitForOperationComplete} for processing
     *         the result.
     *         False when the operation is synchronous. Caller can proceed reading the result
     *         through {@link UsbOperationInternal#getStatus}
     */
    public boolean enableUsbData(String portName, boolean enable, long transactionId,
            IUsbOperationInternal callback);

    /**
     * Invoked to enable  UsbData when disabled due to docking event.
     *
     * @param portName Port Identifier.
     * @param transactionId Used for tracking the current request and is passed down to the HAL
     *                      implementation as needed.
     * @param callback callback object to be invoked to invoke the status of the operation upon
     *                 completion.
     */
    public void enableUsbDataWhileDocked(String portName, long transactionId,
            IUsbOperationInternal callback);

    /**
     * Invoked to enableLimitPowerTransfer on the specified port.
     *
     * @param portName Port Identifier.
     * @param limit limit power transfer when true. Port wouldn't charge or power USB accessoried
     *              when set.
     *              Lift power transfer restrictions when false.
     * @param transactionId Used for tracking the current request and is passed down to the HAL
     *                      implementation as needed.
     * @param callback callback object to be invoked to invoke the status of the operation upon
     *                 completion.
     */
    public void enableLimitPowerTransfer(String portName, boolean limit, long transactionId,
            IUsbOperationInternal callback);

    /**
     * Invoked to reset UsbData on the specified port.
     *
     * @param portName Port Identifier.
     * @param transactionId Used for tracking the current request and is passed down to the HAL
     *                      implementation as needed.
     * @param callback callback object to be invoked to invoke the status of the operation upon
     *                 completion.
     */
    public void resetUsbPort(String portName, long transactionId,
            IUsbOperationInternal callback);
}
