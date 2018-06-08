/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.commands.svc;

import android.content.Context;
import android.hardware.usb.IUsbManager;
import android.hardware.usb.UsbManager;
import android.os.RemoteException;
import android.os.ServiceManager;

public class UsbCommand extends Svc.Command {
    public UsbCommand() {
        super("usb");
    }

    @Override
    public String shortHelp() {
        return "Control Usb state";
    }

    @Override
    public String longHelp() {
        return shortHelp() + "\n"
                + "\n"
                + "usage: svc usb setFunctions [function]\n"
                + "         Set the current usb function. If function is blank, sets to charging.\n"
                + "       svc usb setScreenUnlockedFunctions [function]\n"
                + "         Sets the functions which, if the device was charging, become current on"
                    + "screen unlock. If function is blank, turn off this feature.\n"
                + "       svc usb getFunctions\n"
                + "          Gets the list of currently enabled functions\n\n"
                + "possible values of [function] are any of 'mtp', 'ptp', 'rndis', 'midi'\n";
    }

    @Override
    public void run(String[] args) {
        if (args.length >= 2) {
            IUsbManager usbMgr = IUsbManager.Stub.asInterface(ServiceManager.getService(
                    Context.USB_SERVICE));
            if ("setFunctions".equals(args[1])) {
                try {
                    usbMgr.setCurrentFunctions(UsbManager.usbFunctionsFromString(
                            args.length >= 3 ? args[2] : ""));
                } catch (RemoteException e) {
                    System.err.println("Error communicating with UsbManager: " + e);
                }
                return;
            } else if ("getFunctions".equals(args[1])) {
                try {
                    System.err.println(
                            UsbManager.usbFunctionsToString(usbMgr.getCurrentFunctions()));
                } catch (RemoteException e) {
                    System.err.println("Error communicating with UsbManager: " + e);
                }
                return;
            } else if ("setScreenUnlockedFunctions".equals(args[1])) {
                try {
                    usbMgr.setScreenUnlockedFunctions(UsbManager.usbFunctionsFromString(
                            args.length >= 3 ? args[2] : ""));
                } catch (RemoteException e) {
                    System.err.println("Error communicating with UsbManager: " + e);
                }
                return;
            }
        }
        System.err.println(longHelp());
    }
}
