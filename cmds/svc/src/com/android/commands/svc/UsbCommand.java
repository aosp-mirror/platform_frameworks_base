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
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;

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
                + "usage: svc usb setFunction [function]\n"
                + "         Set the current usb function.\n\n"
                + "       svc usb getFunction\n"
                + "          Gets the list of currently enabled functions\n";
    }

    @Override
    public void run(String[] args) {
        boolean validCommand = false;
        if (args.length >= 2) {
            if ("setFunction".equals(args[1])) {
                IUsbManager usbMgr = IUsbManager.Stub.asInterface(ServiceManager.getService(
                        Context.USB_SERVICE));
                try {
                    usbMgr.setCurrentFunction((args.length >=3 ? args[2] : null), false);
                } catch (RemoteException e) {
                    System.err.println("Error communicating with UsbManager: " + e);
                }
                return;
            } else if ("getFunction".equals(args[1])) {
                System.err.println(SystemProperties.get("sys.usb.config"));
                return;
            }
        }
        System.err.println(longHelp());
    }
}
