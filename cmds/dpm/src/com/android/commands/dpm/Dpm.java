/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.commands.dpm;

import android.app.admin.IDevicePolicyManager;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.os.BaseCommand;

import java.io.PrintStream;

public final class Dpm extends BaseCommand {

    /**
     * Command-line entry point.
     *
     * @param args The command-line arguments
     */
    public static void main(String[] args) {
      (new Dpm()).run(args);
    }

    private static final String COMMAND_SET_DEVICE_OWNER = "set-device-owner";

    private IDevicePolicyManager mDevicePolicyManager;

    @Override
    public void onShowUsage(PrintStream out) {
        out.println("usage: adb shell dpm [subcommand] [options]\n" +
                "\n" +
                "usage: adb shell dpm set-device-owner <PACKAGE>\n" +
                "  <PACKAGE> an Android package name.\n");
    }

    @Override
    public void onRun() throws Exception {
        mDevicePolicyManager = IDevicePolicyManager.Stub.asInterface(
                ServiceManager.getService(Context.DEVICE_POLICY_SERVICE));
        if (mDevicePolicyManager == null) {
            showError("Error: Could not access the Device Policy Manager. Is the system running?");
            return;
        }

        String command = nextArgRequired();
        switch (command) {
            case COMMAND_SET_DEVICE_OWNER:
                runSetDeviceOwner(nextArgRequired());
                break;
            default:
                showError("Error: unknown command '" + command + "'");
        }
    }

    private void runSetDeviceOwner(String packageName) throws RemoteException {
        if (mDevicePolicyManager.setDeviceOwner(packageName, null)) {
            System.out.println("Device owner set to package " + packageName);
        } else {
            showError("Error: Can't set package " + packageName + " as device owner.");
        }
    }
}