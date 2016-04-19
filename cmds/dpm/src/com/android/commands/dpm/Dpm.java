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

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.admin.DevicePolicyManager;
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

    private static final String COMMAND_SET_ACTIVE_ADMIN = "set-active-admin";
    private static final String COMMAND_SET_DEVICE_OWNER = "set-device-owner";
    private static final String COMMAND_SET_PROFILE_OWNER = "set-profile-owner";
    private static final String COMMAND_REMOVE_ACTIVE_ADMIN = "remove-active-admin";

    private IDevicePolicyManager mDevicePolicyManager;
    private int mUserId = UserHandle.USER_SYSTEM;
    private String mName = "";
    private ComponentName mComponent = null;

    @Override
    public void onShowUsage(PrintStream out) {
        out.println(
                "usage: dpm [subcommand] [options]\n" +
                "usage: dpm set-active-admin [ --user <USER_ID> | current ] <COMPONENT>\n" +
                // STOPSHIP Finalize it
                "usage: dpm set-device-owner [ --user <USER_ID> | current *EXPERIMENTAL* ] " +
                "[ --name <NAME> ] <COMPONENT>\n" +
                "usage: dpm set-profile-owner [ --user <USER_ID> | current ] [ --name <NAME> ] " +
                "<COMPONENT>\n" +
                "usage: dpm remove-active-admin [ --user <USER_ID> | current ] [ --name <NAME> ] " +
                "<COMPONENT>\n" +
                "\n" +
                "dpm set-active-admin: Sets the given component as active admin" +
                " for an existing user.\n" +
                "\n" +
                "dpm set-device-owner: Sets the given component as active admin, and its" +
                " package as device owner.\n" +
                "\n" +
                "dpm set-profile-owner: Sets the given component as active admin and profile" +
                " owner for an existing user.\n" +
                "\n" +
                "dpm remove-active-admin: Disables an active admin, the admin must have declared" +
                " android:testOnly in the application in its manifest. This will also remove" +
                " device and profile owners\n");
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
            case COMMAND_SET_ACTIVE_ADMIN:
                runSetActiveAdmin();
                break;
            case COMMAND_SET_DEVICE_OWNER:
                runSetDeviceOwner();
                break;
            case COMMAND_SET_PROFILE_OWNER:
                runSetProfileOwner();
                break;
            case COMMAND_REMOVE_ACTIVE_ADMIN:
                runRemoveActiveAdmin();
                break;
            default:
                throw new IllegalArgumentException ("unknown command '" + command + "'");
        }
    }

    private void parseArgs(boolean canHaveName) {
        String opt;
        while ((opt = nextOption()) != null) {
            if ("--user".equals(opt)) {
                String arg = nextArgRequired();
                if ("current".equals(arg) || "cur".equals(arg)) {
                    mUserId = UserHandle.USER_CURRENT;
                } else {
                    mUserId = parseInt(arg);
                }
                if (mUserId == UserHandle.USER_CURRENT) {
                    IActivityManager activityManager = ActivityManagerNative.getDefault();
                    try {
                        mUserId = activityManager.getCurrentUser().id;
                    } catch (RemoteException e) {
                        e.rethrowAsRuntimeException();
                    }
                }
            } else if (canHaveName && "--name".equals(opt)) {
                mName = nextArgRequired();
            } else {
                throw new IllegalArgumentException("Unknown option: " + opt);
            }
        }
        mComponent = parseComponentName(nextArgRequired());
    }

    private void runSetActiveAdmin() throws RemoteException {
        parseArgs(/*canHaveName=*/ false);
        mDevicePolicyManager.setActiveAdmin(mComponent, true /*refreshing*/, mUserId);

        System.out.println("Success: Active admin set to component " + mComponent.toShortString());
    }

    private void runSetDeviceOwner() throws RemoteException {
        parseArgs(/*canHaveName=*/ true);
        mDevicePolicyManager.setActiveAdmin(mComponent, true /*refreshing*/, mUserId);

        try {
            if (!mDevicePolicyManager.setDeviceOwner(mComponent, mName, mUserId)) {
                throw new RuntimeException(
                        "Can't set package " + mComponent + " as device owner.");
            }
        } catch (Exception e) {
            // Need to remove the admin that we just added.
            mDevicePolicyManager.removeActiveAdmin(mComponent, UserHandle.USER_SYSTEM);
            throw e;
        }

        mDevicePolicyManager.setUserProvisioningState(
                DevicePolicyManager.STATE_USER_SETUP_FINALIZED, mUserId);

        System.out.println("Success: Device owner set to package " + mComponent);
        System.out.println("Active admin set to component " + mComponent.toShortString());
    }

    private void runRemoveActiveAdmin() throws RemoteException {
        parseArgs(/*canHaveName=*/ false);
        mDevicePolicyManager.forceRemoveActiveAdmin(mComponent, mUserId);
        System.out.println("Success: Admin removed " + mComponent);
    }

    private void runSetProfileOwner() throws RemoteException {
        parseArgs(/*canHaveName=*/ true);
        mDevicePolicyManager.setActiveAdmin(mComponent, true /*refreshing*/, mUserId);

        try {
            if (!mDevicePolicyManager.setProfileOwner(mComponent, mName, mUserId)) {
                throw new RuntimeException("Can't set component " + mComponent.toShortString() +
                        " as profile owner for user " + mUserId);
            }
        } catch (Exception e) {
            // Need to remove the admin that we just added.
            mDevicePolicyManager.removeActiveAdmin(mComponent, mUserId);
            throw e;
        }

        mDevicePolicyManager.setUserProvisioningState(
                DevicePolicyManager.STATE_USER_SETUP_FINALIZED, mUserId);

        System.out.println("Success: Active admin and profile owner set to "
                + mComponent.toShortString() + " for user " + mUserId);
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
