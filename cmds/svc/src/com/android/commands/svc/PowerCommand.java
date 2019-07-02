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
import android.os.BatteryManager;
import android.os.IPowerManager;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;

public class PowerCommand extends Svc.Command {
    private static final int FORCE_SUSPEND_DELAY_DEFAULT_MILLIS = 0;

    public PowerCommand() {
        super("power");
    }

    public String shortHelp() {
        return "Control the power manager";
    }

    public String longHelp() {
        return shortHelp() + "\n"
                + "\n"
                + "usage: svc power stayon [true|false|usb|ac|wireless]\n"
                + "         Set the 'keep awake while plugged in' setting.\n"
                + "       svc power reboot [reason]\n"
                + "         Perform a runtime shutdown and reboot device with specified reason.\n"
                + "       svc power shutdown\n"
                + "         Perform a runtime shutdown and power off the device.\n"
                + "       svc power forcesuspend [t]\n"
                + "         Force the system into suspend, ignoring all wakelocks.\n"
                + "         t - Number of milliseconds to wait before issuing force-suspend.\n"
                + "             Helps with devices that can't suspend while plugged in.\n"
                + "             Defaults to " + FORCE_SUSPEND_DELAY_DEFAULT_MILLIS + ".\n"
                + "             When using a delay, you must use the nohup shell modifier:\n"
                + "             'adb shell nohup svc power forcesuspend [time]'\n"
                + "         Use caution; this is dangerous. It puts the device to sleep\n"
                + "         immediately without giving apps or the system an opportunity to\n"
                + "         save their state.\n";
    }

    public void run(String[] args) {
        fail: {
            if (args.length >= 2) {
                IPowerManager pm = IPowerManager.Stub.asInterface(
                        ServiceManager.getService(Context.POWER_SERVICE));
                if ("stayon".equals(args[1]) && args.length == 3) {
                    int val;
                    if ("true".equals(args[2])) {
                        val = BatteryManager.BATTERY_PLUGGED_AC |
                                BatteryManager.BATTERY_PLUGGED_USB |
                                BatteryManager.BATTERY_PLUGGED_WIRELESS;
                    }
                    else if ("false".equals(args[2])) {
                        val = 0;
                    } else if ("usb".equals(args[2])) {
                        val = BatteryManager.BATTERY_PLUGGED_USB;
                    } else if ("ac".equals(args[2])) {
                        val = BatteryManager.BATTERY_PLUGGED_AC;
                    } else if ("wireless".equals(args[2])) {
                        val = BatteryManager.BATTERY_PLUGGED_WIRELESS;
                    } else {
                        break fail;
                    }
                    try {
                        if (val != 0) {
                            // if the request is not to set it to false, wake up the screen so that
                            // it can stay on as requested
                            pm.wakeUp(SystemClock.uptimeMillis(),
                                    PowerManager.WAKE_REASON_UNKNOWN, "PowerCommand", null);
                        }
                        pm.setStayOnSetting(val);
                    }
                    catch (RemoteException e) {
                        System.err.println("Faild to set setting: " + e);
                    }
                    return;
                } else if ("reboot".equals(args[1])) {
                    String mode = null;
                    if (args.length == 3) {
                        mode = args[2];
                    }
                    try {
                        // no confirm, wait till device is rebooted
                        pm.reboot(false, mode, true);
                    } catch (RemoteException e) {
                        maybeLogRemoteException("Failed to reboot.");
                    }
                    return;
                } else if ("shutdown".equals(args[1])) {
                    try {
                        // no confirm, wait till device is off
                        pm.shutdown(false, null, true);
                    } catch (RemoteException e) {
                        maybeLogRemoteException("Failed to shutdown.");
                    }
                    return;
                } else if ("forcesuspend".equals(args[1])) {
                    int delayMillis = args.length > 2
                            ? Integer.parseInt(args[2]) : FORCE_SUSPEND_DELAY_DEFAULT_MILLIS;
                    try {
                        Thread.sleep(delayMillis);
                        if (!pm.forceSuspend()) {
                            System.err.println("Failed to force suspend.");
                        }
                    } catch (InterruptedException e) {
                        System.err.println("Failed to force suspend: " + e);
                    } catch (RemoteException e) {
                        maybeLogRemoteException("Failed to force-suspend with exception: " + e);
                    }
                    return;
                }
            }
        }
        System.err.println(longHelp());
    }

    // Check if remote exception is benign during shutdown. Pm can be killed
    // before system server during shutdown, so remote exception can be ignored
    // if it is already in shutdown flow.
    private void maybeLogRemoteException(String msg) {
        String powerProp = SystemProperties.get("sys.powerctl");
        if (powerProp.isEmpty()) {
            System.err.println(msg);
        }
    }
}
