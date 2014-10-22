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
import android.content.ComponentName;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;

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
    private static final String COMMAND_SET_PROFILE_OWNER = "set-profile-owner";

    private IDevicePolicyManager mDevicePolicyManager;

    @Override
    public void onShowUsage(PrintStream out) {
        out.println(
                "usage: dpm [subcommand] [options]\n" +
                "usage: dpm set-device-owner <COMPONENT>\n" +
                "usage: dpm set-profile-owner <COMPONENT> <USER_ID>\n" +
                "\n" +
                "dpm set-device-owner: Sets the given component as active admin, and its\n" +
                "  package as device owner.\n" +
                "\n" +
                "dpm set-profile-owner: Sets the given component as active admin and profile" +
                "  owner for an existing user.\n");
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
                runSetDeviceOwner();
                break;
            case COMMAND_SET_PROFILE_OWNER:
                runSetProfileOwner();
                break;
            default:
                throw new IllegalArgumentException ("unknown command '" + command + "'");
        }
    }

    private void runSetDeviceOwner() throws RemoteException {
        ComponentName component = parseComponentName(nextArgRequired());
        mDevicePolicyManager.setActiveAdmin(component, true /*refreshing*/, UserHandle.USER_OWNER);

        String packageName = component.getPackageName();
        try {
            if (!mDevicePolicyManager.setDeviceOwner(packageName, null /*ownerName*/)) {
                throw new RuntimeException(
                        "Can't set package " + packageName + " as device owner.");
            }
        } catch (Exception e) {
            // Need to remove the admin that we just added.
            mDevicePolicyManager.removeActiveAdmin(component, UserHandle.USER_OWNER);
            throw e;
        }
        System.out.println("Success: Device owner set to package " + packageName);
        System.out.println("Active admin set to component " + component.toShortString());
    }

    private void runSetProfileOwner() throws RemoteException {
        ComponentName component = parseComponentName(nextArgRequired());
        int userId = parseInt(nextArgRequired());
        mDevicePolicyManager.setActiveAdmin(component, true /*refreshing*/, userId);

        try {
            if (!mDevicePolicyManager.setProfileOwner(component, "" /*ownerName*/, userId)) {
                throw new RuntimeException("Can't set component " + component.toShortString() +
                        " as profile owner for user " + userId);
            }
        } catch (Exception e) {
            // Need to remove the admin that we just added.
            mDevicePolicyManager.removeActiveAdmin(component, userId);
            throw e;
        }
        System.out.println("Success: Active admin and profile owner set to "
                + component.toShortString() + " for user " + userId);
    }

    private ComponentName parseComponentName(String component) {
        ComponentName cn = ComponentName.unflattenFromString(component);
        if (cn == null) {
            throw new IllegalArgumentException ("Invalid component " + component);
        }
        return cn;
    }

    private int parseInt(String argument) {
        try {
            return Integer.parseInt(argument);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException ("Invalid integer argument '" + argument + "'", e);
        }
    }
}