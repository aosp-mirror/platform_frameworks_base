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

import android.os.Binder;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.PowerManager;
import android.os.ServiceManager;
import android.os.RemoteException;
import android.os.BatteryManager;
import android.content.Context;

public class PowerCommand extends Svc.Command {
    public PowerCommand() {
        super("power");
    }

    public String shortHelp() {
        return "Control the power manager";
    }

    public String longHelp() {
        return shortHelp() + "\n"
                + "\n"
                + "usage: svc power stayon [true|false|usb|ac]\n"
                + "         Set the 'keep awake while plugged in' setting.\n";
    }

    public void run(String[] args) {
        fail: {
            if (args.length >= 2) {
                if ("stayon".equals(args[1]) && args.length == 3) {
                    int val;
                    if ("true".equals(args[2])) {
                        val = BatteryManager.BATTERY_PLUGGED_AC |
                                BatteryManager.BATTERY_PLUGGED_USB;
                    }
                    else if ("false".equals(args[2])) {
                        val = 0;
                    } else if ("usb".equals(args[2])) {
                        val = BatteryManager.BATTERY_PLUGGED_USB;
                    } else if ("ac".equals(args[2])) {
                        val = BatteryManager.BATTERY_PLUGGED_AC;
                    }
                    else {
                        break fail;
                    }
                    IPowerManager pm
                            = IPowerManager.Stub.asInterface(ServiceManager.getService(Context.POWER_SERVICE));
                    try {
                        IBinder lock = new Binder();
                        pm.acquireWakeLock(PowerManager.FULL_WAKE_LOCK, lock, "svc power", null);
                        pm.setStayOnSetting(val);
                        pm.releaseWakeLock(lock, 0);
                    }
                    catch (RemoteException e) {
                        System.err.println("Faild to set setting: " + e);
                    }
                    return;
                }
            }
        }
        System.err.println(longHelp());
    }
}
