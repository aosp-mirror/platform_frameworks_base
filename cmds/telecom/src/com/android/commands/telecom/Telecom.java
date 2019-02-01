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
import android.os.IUserManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.telecom.Log;
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
    private static final String COMMAND_SET_USER_SELECTED_OUTGOING_PHONE_ACCOUNT =
            "set-user-selected-outgoing-phone-account";
    private static final String COMMAND_REGISTER_SIM_PHONE_ACCOUNT = "register-sim-phone-account";
    private static final String COMMAND_SET_TEST_CALL_REDIRECTION_APP = "set-test-call-redirection-app";
    private static final String COMMAND_SET_TEST_CALL_SCREENING_APP = "set-test-call-screening-app";
    private static final String COMMAND_ADD_OR_REMOVE_CALL_COMPANION_APP =
            "add-or-remove-call-companion-app";
    private static final String COMMAND_SET_TEST_AUTO_MODE_APP = "set-test-auto-mode-app";
    private static final String COMMAND_SET_PHONE_ACCOUNT_SUGGESTION_COMPONENT =
            "set-phone-acct-suggestion-component";
    private static final String COMMAND_UNREGISTER_PHONE_ACCOUNT = "unregister-phone-account";
    private static final String COMMAND_SET_DEFAULT_DIALER = "set-default-dialer";
    private static final String COMMAND_GET_DEFAULT_DIALER = "get-default-dialer";
    private static final String COMMAND_GET_SYSTEM_DIALER = "get-system-dialer";
    private static final String COMMAND_WAIT_ON_HANDLERS = "wait-on-handlers";

    private ComponentName mComponent;
    private String mAccountId;
    private ITelecomService mTelecomService;
    private IUserManager mUserManager;

    @Override
    public void onShowUsage(PrintStream out) {
        out.println("usage: telecom [subcommand] [options]\n"
                + "usage: telecom set-phone-account-enabled <COMPONENT> <ID> <USER_SN>\n"
                + "usage: telecom set-phone-account-disabled <COMPONENT> <ID> <USER_SN>\n"
                + "usage: telecom register-phone-account <COMPONENT> <ID> <USER_SN> <LABEL>\n"
                + "usage: telecom set-user-selected-outgoing-phone-account <COMPONENT> <ID> "
                + "<USER_SN>\n"
                + "usage: telecom set-test-call-redirection-app <PACKAGE>\n"
                + "usage: telecom set-test-call-screening-app <PACKAGE>\n"
                + "usage: telecom set-test-auto-mode-app <PACKAGE>\n"
                + "usage: telecom set-phone-acct-suggestion-component <COMPONENT>\n"
                + "usage: telecom add-or-remove-call-companion-app <PACKAGE> <1/0>\n"
                + "usage: telecom register-sim-phone-account <COMPONENT> <ID> <USER_SN>"
                + " <LABEL> <ADDRESS>\n"
                + "usage: telecom unregister-phone-account <COMPONENT> <ID> <USER_SN>\n"
                + "usage: telecom set-default-dialer <PACKAGE>\n"
                + "usage: telecom get-default-dialer\n"
                + "usage: telecom get-system-dialer\n"
                + "usage: telecom wait-on-handlers\n"
                + "\n"
                + "telecom set-phone-account-enabled: Enables the given phone account, if it has \n"
                + " already been registered with Telecom.\n"
                + "\n"
                + "telecom set-phone-account-disabled: Disables the given phone account, if it \n"
                + " has already been registered with telecom.\n"
                + "\n"
                + "telecom set-default-dialer: Sets the default dialer to the given component. \n"
                + "\n"
                + "telecom get-default-dialer: Displays the current default dialer. \n"
                + "\n"
                + "telecom get-system-dialer: Displays the current system dialer. \n"
                + "\n"
                + "telecom wait-on-handlers: Wait until all handlers finish their work. \n"
        );
    }

    @Override
    public void onRun() throws Exception {
        mTelecomService = ITelecomService.Stub.asInterface(
                ServiceManager.getService(Context.TELECOM_SERVICE));
        if (mTelecomService == null) {
            Log.w(this, "onRun: Can't access telecom manager.");
            showError("Error: Could not access the Telecom Manager. Is the system running?");
            return;
        }
        mUserManager = IUserManager.Stub
                .asInterface(ServiceManager.getService(Context.USER_SERVICE));
        if (mUserManager == null) {
            Log.w(this, "onRun: Can't access user manager.");
            showError("Error: Could not access the User Manager. Is the system running?");
            return;
        }
        Log.i(this, "onRun: parsing command.");
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
            case COMMAND_SET_TEST_CALL_REDIRECTION_APP:
                runSetTestCallRedirectionApp();
                break;
            case COMMAND_SET_TEST_CALL_SCREENING_APP:
                runSetTestCallScreeningApp();
                break;
            case COMMAND_ADD_OR_REMOVE_CALL_COMPANION_APP:
                runAddOrRemoveCallCompanionApp();
                break;
            case COMMAND_SET_TEST_AUTO_MODE_APP:
                runSetTestAutoModeApp();
                break;
            case COMMAND_SET_PHONE_ACCOUNT_SUGGESTION_COMPONENT:
                runSetTestPhoneAcctSuggestionComponent();
                break;
            case COMMAND_REGISTER_SIM_PHONE_ACCOUNT:
                runRegisterSimPhoneAccount();
                break;
            case COMMAND_SET_USER_SELECTED_OUTGOING_PHONE_ACCOUNT:
                runSetUserSelectedOutgoingPhoneAccount();
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
            case COMMAND_WAIT_ON_HANDLERS:
                runWaitOnHandler();
                break;
            default:
                Log.w(this, "onRun: unknown command: %s", command);
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

    private void runSetTestCallRedirectionApp() throws RemoteException {
        final String packageName = nextArg();
        mTelecomService.setTestDefaultCallRedirectionApp(packageName);
    }

    private void runSetTestCallScreeningApp() throws RemoteException {
        final String packageName = nextArg();
        mTelecomService.setTestDefaultCallScreeningApp(packageName);
    }

    private void runAddOrRemoveCallCompanionApp() throws RemoteException {
        final String packageName = nextArgRequired();
        String isAdded = nextArgRequired();
        boolean isAddedBool = "1".equals(isAdded);
        mTelecomService.addOrRemoveTestCallCompanionApp(packageName, isAddedBool);
    }

    private void runSetTestAutoModeApp() throws RemoteException {
        final String packageName = nextArg();
        mTelecomService.setTestAutoModeApp(packageName);
    }

    private void runSetTestPhoneAcctSuggestionComponent() throws RemoteException {
        final String componentName = nextArg();
        mTelecomService.setTestPhoneAcctSuggestionComponent(componentName);
    }

    private void runSetUserSelectedOutgoingPhoneAccount() throws RemoteException {
        Log.i(this, "runSetUserSelectedOutgoingPhoneAccount");
        final PhoneAccountHandle handle = getPhoneAccountHandleFromArgs();
        mTelecomService.setUserSelectedOutgoingPhoneAccount(handle);
        System.out.println("Success - " + handle + " set as default outgoing account.");
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

    private void runWaitOnHandler() throws RemoteException {

    }

    private PhoneAccountHandle getPhoneAccountHandleFromArgs() throws RemoteException {
        if (TextUtils.isEmpty(mArgs.peekNextArg())) {
            return null;
        }
        final ComponentName component = parseComponentName(nextArgRequired());
        final String accountId = nextArgRequired();
        final String userSnInStr = nextArgRequired();
        UserHandle userHandle;
        try {
            final int userSn = Integer.parseInt(userSnInStr);
            userHandle = UserHandle.of(mUserManager.getUserHandle(userSn));
        } catch (NumberFormatException ex) {
            Log.w(this, "getPhoneAccountHandleFromArgs - invalid user %s", userSnInStr);
            throw new IllegalArgumentException ("Invalid user serial number " + userSnInStr);
        }
        return new PhoneAccountHandle(component, accountId, userHandle);
    }

    private ComponentName parseComponentName(String component) {
        ComponentName cn = ComponentName.unflattenFromString(component);
        if (cn == null) {
            throw new IllegalArgumentException ("Invalid component " + component);
        }
        return cn;
    }
}
