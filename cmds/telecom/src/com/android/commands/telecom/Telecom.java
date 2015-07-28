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

package com.android.commands.telecom;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.text.TextUtils;

import com.android.internal.os.BaseCommand;
import com.android.internal.telecom.ITelecomService;

import java.io.PrintStream;

public final class Telecom extends BaseCommand {

    /**
     * Command-line entry point.
     *
     * @param args The command-line arguments
     */
    public static void main(String[] args) {
      (new Telecom()).run(args);
    }

    private static final String COMMAND_SET_PHONE_ACCOUNT_ENABLED = "set-phone-account-enabled";
    private static final String COMMAND_SET_PHONE_ACCOUNT_DISABLED = "set-phone-account-disabled";
    private static final String COMMAND_REGISTER_PHONE_ACCOUNT = "register-phone-account";
    private static final String COMMAND_REGISTER_SIM_PHONE_ACCOUNT = "register-sim-phone-account";
    private static final String COMMAND_UNREGISTER_PHONE_ACCOUNT = "unregister-phone-account";
    private static final String COMMAND_SET_DEFAULT_DIALER = "set-default-dialer";
    private static final String COMMAND_GET_DEFAULT_DIALER = "get-default-dialer";
    private static final String COMMAND_GET_SYSTEM_DIALER = "get-system-dialer";

    private ComponentName mComponent;
    private String mAccountId;
    private ITelecomService mTelecomService;

    @Override
    public void onShowUsage(PrintStream out) {
        out.println(
                "usage: telecom [subcommand] [options]\n" +
                "usage: telecom set-phone-account-enabled <COMPONENT> <ID>\n" +
                "usage: telecom set-phone-account-disabled <COMPONENT> <ID>\n" +
                "usage: telecom register-phone-account <COMPONENT> <ID> <LABEL>\n" +
                "usage: telecom register-sim-phone-account <COMPONENT> <ID> <LABEL> <ADDRESS>\n" +
                "usage: telecom unregister-phone-account <COMPONENT> <ID>\n" +
                "usage: telecom set-default-dialer <PACKAGE>\n" +
                "usage: telecom get-default-dialer\n" +
                "usage: telecom get-system-dialer\n" +
                "\n" +
                "telecom set-phone-account-enabled: Enables the given phone account, if it has \n" +
                " already been registered with Telecom.\n" +
                "\n" +
                "telecom set-phone-account-disabled: Disables the given phone account, if it \n" +
                " has already been registered with telecom.\n" +
                "\n" +
                "telecom set-default-dialer: Sets the default dialer to the given component. \n" +
                "\n" +
                "telecom get-default-dialer: Displays the current default dialer. \n" +
                "\n" +
                "telecom get-system-dialer: Displays the current system dialer. \n"
                );
    }

    @Override
    public void onRun() throws Exception {
        mTelecomService = ITelecomService.Stub.asInterface(
                ServiceManager.getService(Context.TELECOM_SERVICE));
        if (mTelecomService == null) {
            showError("Error: Could not access the Telecom Manager. Is the system running?");
            return;
        }

        String command = nextArgRequired();
        switch (command) {
            case COMMAND_SET_PHONE_ACCOUNT_ENABLED:
                runSetPhoneAccountEnabled(true);
                break;
            case COMMAND_SET_PHONE_ACCOUNT_DISABLED:
                runSetPhoneAccountEnabled(false);
                break;
            case COMMAND_REGISTER_PHONE_ACCOUNT:
                runRegisterPhoneAccount();
                break;
            case COMMAND_REGISTER_SIM_PHONE_ACCOUNT:
                runRegisterSimPhoneAccount();
                break;
            case COMMAND_UNREGISTER_PHONE_ACCOUNT:
                runUnregisterPhoneAccount();
                break;
            case COMMAND_SET_DEFAULT_DIALER:
                runSetDefaultDialer();
                break;
            case COMMAND_GET_DEFAULT_DIALER:
                runGetDefaultDialer();
                break;
            case COMMAND_GET_SYSTEM_DIALER:
                runGetSystemDialer();
                break;
            default:
                throw new IllegalArgumentException ("unknown command '" + command + "'");
        }
    }

    private void runSetPhoneAccountEnabled(boolean enabled) throws RemoteException {
        final PhoneAccountHandle handle = getPhoneAccountHandleFromArgs();
        final boolean success =  mTelecomService.enablePhoneAccount(handle, enabled);
        if (success) {
            System.out.println("Success - " + handle + (enabled ? " enabled." : " disabled."));
        } else {
            System.out.println("Error - is " + handle + " a valid PhoneAccount?");
        }
    }

    private void runRegisterPhoneAccount() throws RemoteException {
        final PhoneAccountHandle handle = getPhoneAccountHandleFromArgs();
        final String label = nextArgRequired();
        PhoneAccount account = PhoneAccount.builder(handle, label)
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER).build();
        mTelecomService.registerPhoneAccount(account);
        System.out.println("Success - " + handle + " registered.");
    }

    private void runRegisterSimPhoneAccount() throws RemoteException {
        final PhoneAccountHandle handle = getPhoneAccountHandleFromArgs();
        final String label = nextArgRequired();
        final String address = nextArgRequired();
        PhoneAccount account = PhoneAccount.builder(
            handle, label)
            .setAddress(Uri.parse(address))
            .setSubscriptionAddress(Uri.parse(address))
            .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER |
                    PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)
            .setShortDescription(label)
            .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
            .addSupportedUriScheme(PhoneAccount.SCHEME_VOICEMAIL)
            .build();
        mTelecomService.registerPhoneAccount(account);
        System.out.println("Success - " + handle + " registered.");
    }

    private void runUnregisterPhoneAccount() throws RemoteException {
        final PhoneAccountHandle handle = getPhoneAccountHandleFromArgs();
        mTelecomService.unregisterPhoneAccount(handle);
        System.out.println("Success - " + handle + " unregistered.");
    }

    private void runSetDefaultDialer() throws RemoteException {
        final String packageName = nextArgRequired();
        final boolean success = mTelecomService.setDefaultDialer(packageName);
        if (success) {
            System.out.println("Success - " + packageName + " set as default dialer.");
        } else {
            System.out.println("Error - " + packageName + " is not an installed Dialer app, \n"
                    + " or is already the default dialer.");
        }
    }

    private void runGetDefaultDialer() throws RemoteException {
        System.out.println(mTelecomService.getDefaultDialerPackage());
    }

    private void runGetSystemDialer() throws RemoteException {
        System.out.println(mTelecomService.getSystemDialerPackage());
    }

    private PhoneAccountHandle getPhoneAccountHandleFromArgs() {
        final ComponentName component = parseComponentName(nextArgRequired());
        final String accountId = nextArgRequired();
        return new PhoneAccountHandle(component, accountId);
    }

    private ComponentName parseComponentName(String component) {
        ComponentName cn = ComponentName.unflattenFromString(component);
        if (cn == null) {
            throw new IllegalArgumentException ("Invalid component " + component);
        }
        return cn;
    }
}