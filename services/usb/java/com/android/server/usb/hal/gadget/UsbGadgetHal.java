/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.server.usb.hal.gadget;

import android.annotation.IntDef;
import android.hardware.usb.gadget.IUsbGadgetCallback;
import android.hardware.usb.IUsbOperationInternal;
import android.hardware.usb.UsbManager.UsbHalVersion;
import android.os.RemoteException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.String;

/**
 * @hide
 */
public interface UsbGadgetHal {
    /**
     * This function is used to query the USB functions included in the
     * current USB configuration.
     *
     * @param transactionId Used for tracking the current request and is passed down to the HAL
     *                      implementation as needed.
     */
    public void getCurrentUsbFunctions(long transactionId);

    /**
     * The function is used to query current USB speed.
     *
     * @param transactionId Used for tracking the current request and is passed down to the HAL
     *                      implementation as needed.
     */
    public void getUsbSpeed(long transactionId);

    /**
     * This function is used to reset USB gadget driver.
     * Performs USB data connection reset. The connection will disconnect and
     * reconnect.
     *
     * @param transactionId Used for tracking the current request and is passed down to the HAL
     *                      implementation as needed.
     */
    public void reset(long transactionId);

    /**
     * Invoked to query the version of current gadget hal implementation.
     */
    public @UsbHalVersion int getGadgetHalVersion() throws RemoteException;

    /**
     * This function is used to set the current USB gadget configuration.
     * The USB gadget needs to be torn down if a USB configuration is already
     * active.
     *
     * @param functions list of functions defined by GadgetFunction to be
     *                  included in the gadget composition.
     * @param timeout The maximum time (in milliseconds) within which the
     *                IUsbGadgetCallback needs to be returned.
     * @param transactionId Used for tracking the current request and is passed down to the HAL
     *                      implementation as needed.
     */
    public void setCurrentUsbFunctions(int request, long functions,
        boolean chargingFunctions, int timeout, long transactionId);
}

