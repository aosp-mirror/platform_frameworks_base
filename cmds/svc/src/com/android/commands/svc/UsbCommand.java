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

import android.app.ActivityThread;
import android.content.Context;
import android.hardware.usb.IUsbManager;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbPort;
import android.hardware.usb.UsbPortStatus;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;

import java.util.List;

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
                + "         Sets the functions which, if the device was charging,\n"
                + "         become current on screen unlock.\n"
                + "         If function is blank, turn off this feature.\n"
                + "       svc usb getFunctions\n"
                + "         Gets the list of currently enabled functions\n"
                + "         possible values of [function] are any of 'mtp', 'ptp', 'rndis',\n"
                + "         'midi', 'ncm (if supporting gadget hal v1.2)'\n"
                + "       svc usb resetUsbGadget\n"
                + "         Reset usb gadget\n"
                + "       svc usb getUsbSpeed\n"
                + "         Gets current USB speed\n"
                + "         possible values of USB speed are any of 'low speed', 'full speed',\n"
                + "         'high speed', 'super speed', 'super speed (10G)',\n"
                + "         'super speed (20G)', or higher (future extension)\n"
                + "       svc usb getGadgetHalVersion\n"
                + "         Gets current Gadget Hal Version\n"
                + "         possible values of Hal version are any of 'unknown', 'V1_0', 'V1_1',\n"
                + "         'V1_2'\n"
                + "       svc usb getUsbHalVersion\n"
                + "         Gets current USB Hal Version\n"
                + "         possible values of Hal version are any of 'unknown', 'V1_0', 'V1_1',\n"
                + "         'V1_2', 'V1_3'\n"
                + "       svc usb resetUsbPort [port number]\n"
                + "         Reset the specified connected usb port\n"
                + "         default: the first connected usb port\n";
    }

    @Override
    public void run(String[] args) {
        if (args.length >= 2) {
            Looper.prepareMainLooper();
            Context context = ActivityThread.systemMain().getSystemContext();
            UsbManager usbManager = context.getSystemService(UsbManager.class);
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
            } else if ("resetUsbGadget".equals(args[1])) {
                try {
                    usbMgr.resetUsbGadget();
                } catch (RemoteException e) {
                    System.err.println("Error communicating with UsbManager: " + e);
                }
                return;
            } else if ("getUsbSpeed".equals(args[1])) {
                try {
                    System.err.println(
                            UsbManager.usbSpeedToBandwidth(usbMgr.getCurrentUsbSpeed()));
                } catch (RemoteException e) {
                    System.err.println("Error communicating with UsbManager: " + e);
                }
                return;
            } else if ("getGadgetHalVersion".equals(args[1])) {
                try {
                    System.err.println(
                            UsbManager.usbGadgetHalVersionToString(
                                    usbMgr.getGadgetHalVersion()));
                } catch (RemoteException e) {
                    System.err.println("Error communicating with UsbManager: " + e);
                }
                return;
            } else if ("getUsbHalVersion".equals(args[1])) {
                try {
                    int version = usbMgr.getUsbHalVersion();

                    if (version == 13) {
                        System.err.println("V1_3");
                    } else if (version == 12) {
                        System.err.println("V1_2");
                    } else if (version == 11) {
                        System.err.println("V1_1");
                    } else if (version == 10) {
                        System.err.println("V1_0");
                    } else {
                        System.err.println("unknown");
                    }
                } catch (RemoteException e) {
                    System.err.println("Error communicating with UsbManager: " + e);
                }
                return;
            } else if ("resetUsbPort".equals(args[1])) {
                try {
                    int portNum = args.length >= 3 ? Integer.parseInt(args[2]) : -1;
                    UsbPort port = null;
                    UsbPortStatus portStatus = null;
                    List<UsbPort> ports = usbManager.getPorts();
                    final int numPorts = ports.size();

                    if (numPorts > 0) {
                        if (portNum != -1 && portNum < numPorts) {
                            portStatus = ports.get(portNum).getStatus();
                            if (portStatus.isConnected()) {
                                port = ports.get(portNum);
                                System.err.println(
                                        "Get the USB port: port" + portNum);
                            }
                        } else {
                            for (portNum = 0; portNum < numPorts; portNum++) {
                                UsbPortStatus status = ports.get(portNum).getStatus();
                                if (status.isConnected()) {
                                    port = ports.get(portNum);
                                    portStatus = status;
                                    System.err.println(
                                            "Use the default USB port: port" + portNum);
                                    break;
                                }
                            }
                        }
                        if (port != null && portStatus.isConnected()) {
                            System.err.println(
                                    "Reset the USB port: port" + portNum);
                            port.resetUsbPort();
                        } else {
                            System.err.println(
                                    "There is no available reset USB port");
                        }
                    } else {
                        System.err.println("No USB ports");
                    }
                } catch (Exception e) {
                    System.err.println("Error communicating with UsbManager: " + e);
                }
                return;
            }
        }
        System.err.println(longHelp());
    }
}
