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

import android.app.ActivityThread;
import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.os.IUserManager;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.sysprop.TelephonyProperties;
import android.telecom.Log;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.internal.os.BaseCommand;
import com.android.internal.telecom.ITelecomService;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.stream.Collectors;

public final class Telecom extends BaseCommand {

    /**
     * Command-line entry point.
     *
     * @param args The command-line arguments
     */
    public static void main(String[] args) {
        // Initialize the telephony module.
        // TODO: Do it in zygote and RuntimeInit. b/148897549
        ActivityThread.initializeMainlineModules();

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
    private static final String COMMAND_SET_PHONE_ACCOUNT_SUGGESTION_COMPONENT =
            "set-phone-acct-suggestion-component";
    private static final String COMMAND_UNREGISTER_PHONE_ACCOUNT = "unregister-phone-account";
    private static final String COMMAND_SET_CALL_DIAGNOSTIC_SERVICE = "set-call-diagnostic-service";
    private static final String COMMAND_SET_DEFAULT_DIALER = "set-default-dialer";
    private static final String COMMAND_GET_DEFAULT_DIALER = "get-default-dialer";
    private static final String COMMAND_STOP_BLOCK_SUPPRESSION = "stop-block-suppression";
    private static final String COMMAND_CLEANUP_STUCK_CALLS = "cleanup-stuck-calls";
    private static final String COMMAND_CLEANUP_ORPHAN_PHONE_ACCOUNTS =
            "cleanup-orphan-phone-accounts";
    private static final String COMMAND_RESET_CAR_MODE = "reset-car-mode";

    /**
     * Change the system dialer package name if a package name was specified,
     * Example: adb shell telecom set-system-dialer <PACKAGE>
     *
     * Restore it to the default if if argument is "default" or no argument is passed.
     * Example: adb shell telecom set-system-dialer default
     */
    private static final String COMMAND_SET_SYSTEM_DIALER = "set-system-dialer";
    private static final String COMMAND_GET_SYSTEM_DIALER = "get-system-dialer";
    private static final String COMMAND_WAIT_ON_HANDLERS = "wait-on-handlers";
    private static final String COMMAND_SET_SIM_COUNT = "set-sim-count";
    private static final String COMMAND_GET_SIM_CONFIG = "get-sim-config";
    private static final String COMMAND_GET_MAX_PHONES = "get-max-phones";
    private static final String COMMAND_SET_TEST_EMERGENCY_PHONE_ACCOUNT_PACKAGE_FILTER =
            "set-test-emergency-phone-account-package-filter";
    /**
     * Command used to emit a distinct "mark" in the logs.
     */
    private static final String COMMAND_LOG_MARK = "log-mark";

    private ComponentName mComponent;
    private String mAccountId;
    private ITelecomService mTelecomService;
    private TelephonyManager mTelephonyManager;
    private IUserManager mUserManager;

    @Override
    public void onShowUsage(PrintStream out) {
        out.println("usage: telecom [subcommand] [options]\n"
                + "usage: telecom set-phone-account-enabled <COMPONENT> <ID> <USER_SN>\n"
                + "usage: telecom set-phone-account-disabled <COMPONENT> <ID> <USER_SN>\n"
                + "usage: telecom register-phone-account <COMPONENT> <ID> <USER_SN> <LABEL>\n"
                + "usage: telecom register-sim-phone-account [-e] <COMPONENT> <ID> <USER_SN>"
                        + " <LABEL>: registers a PhoneAccount with CAPABILITY_SIM_SUBSCRIPTION"
                        + " and optionally CAPABILITY_PLACE_EMERGENCY_CALLS if \"-e\" is provided\n"
                + "usage: telecom set-user-selected-outgoing-phone-account [-e] <COMPONENT> <ID> "
                + "<USER_SN>\n"
                + "usage: telecom set-test-call-redirection-app <PACKAGE>\n"
                + "usage: telecom set-test-call-screening-app <PACKAGE>\n"
                + "usage: telecom set-phone-acct-suggestion-component <COMPONENT>\n"
                + "usage: telecom add-or-remove-call-companion-app <PACKAGE> <1/0>\n"
                + "usage: telecom register-sim-phone-account <COMPONENT> <ID> <USER_SN>"
                + " <LABEL> <ADDRESS>\n"
                + "usage: telecom unregister-phone-account <COMPONENT> <ID> <USER_SN>\n"
                + "usage: telecom set-call-diagnostic-service <PACKAGE>\n"
                + "usage: telecom set-default-dialer <PACKAGE>\n"
                + "usage: telecom get-default-dialer\n"
                + "usage: telecom get-system-dialer\n"
                + "usage: telecom wait-on-handlers\n"
                + "usage: telecom set-sim-count <COUNT>\n"
                + "usage: telecom get-sim-config\n"
                + "usage: telecom get-max-phones\n"
                + "usage: telecom stop-block-suppression: Stop suppressing the blocked number"
                        + " provider after a call to emergency services.\n"
                + "usage: telecom cleanup-stuck-calls: Clear any disconnected calls that have"
                + " gotten wedged in Telecom.\n"
                + "usage: telecom cleanup-orphan-phone-accounts: remove any phone accounts that"
                + " no longer have a valid UserHandle or accounts that no longer belongs to an"
                + " installed package.\n"
                + "usage: telecom set-emer-phone-account-filter <PACKAGE>\n"
                + "\n"
                + "telecom set-phone-account-enabled: Enables the given phone account, if it has"
                        + " already been registered with Telecom.\n"
                + "\n"
                + "telecom set-phone-account-disabled: Disables the given phone account, if it"
                        + " has already been registered with telecom.\n"
                + "\n"
                + "telecom set-call-diagnostic-service: overrides call diagnostic service.\n"
                + "telecom set-default-dialer: Sets the override default dialer to the given"
                        + " component; this will override whatever the dialer role is set to.\n"
                + "\n"
                + "telecom get-default-dialer: Displays the current default dialer.\n"
                + "\n"
                + "telecom get-system-dialer: Displays the current system dialer.\n"
                + "telecom set-system-dialer: Set the override system dialer to the given"
                        + " component. To remove the override, send \"default\"\n"
                + "\n"
                + "telecom wait-on-handlers: Wait until all handlers finish their work.\n"
                + "\n"
                + "telecom set-sim-count: Set num SIMs (2 for DSDS, 1 for single SIM."
                        + " This may restart the device.\n"
                + "\n"
                + "telecom get-sim-config: Get the mSIM config string. \"DSDS\" for DSDS mode,"
                        + " or \"\" for single SIM\n"
                + "\n"
                + "telecom get-max-phones: Get the max supported phones from the modem.\n"
                + "telecom set-test-emergency-phone-account-package-filter <PACKAGE>: sets a"
                        + " package name that will be used for test emergency calls. To clear,"
                        + " send an empty package name. Real emergency calls will still be placed"
                        + " over Telephony.\n"
                + "telecom log-mark <MESSAGE>: emits a message into the telecom logs.  Useful for "
                        + "testers to indicate where in the logs various test steps take place.\n"
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

        Looper.prepareMainLooper();
        Context context = ActivityThread.systemMain().getSystemContext();
        mTelephonyManager = context.getSystemService(TelephonyManager.class);
        if (mTelephonyManager == null) {
            Log.w(this, "onRun: Can't access telephony service.");
            showError("Error: Could not access the Telephony Service. Is the system running?");
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
            case COMMAND_SET_PHONE_ACCOUNT_SUGGESTION_COMPONENT:
                runSetTestPhoneAcctSuggestionComponent();
                break;
            case COMMAND_SET_CALL_DIAGNOSTIC_SERVICE:
                runSetCallDiagnosticService();
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
            case COMMAND_STOP_BLOCK_SUPPRESSION:
                runStopBlockSuppression();
                break;
            case COMMAND_CLEANUP_STUCK_CALLS:
                runCleanupStuckCalls();
                break;
            case COMMAND_CLEANUP_ORPHAN_PHONE_ACCOUNTS:
                runCleanupOrphanPhoneAccounts();
                break;
            case COMMAND_RESET_CAR_MODE:
                runResetCarMode();
                break;
            case COMMAND_SET_DEFAULT_DIALER:
                runSetDefaultDialer();
                break;
            case COMMAND_GET_DEFAULT_DIALER:
                runGetDefaultDialer();
                break;
            case COMMAND_SET_SYSTEM_DIALER:
                runSetSystemDialer();
                break;
            case COMMAND_GET_SYSTEM_DIALER:
                runGetSystemDialer();
                break;
            case COMMAND_WAIT_ON_HANDLERS:
                runWaitOnHandler();
                break;
            case COMMAND_SET_SIM_COUNT:
                runSetSimCount();
                break;
            case COMMAND_GET_SIM_CONFIG:
                runGetSimConfig();
                break;
            case COMMAND_GET_MAX_PHONES:
                runGetMaxPhones();
                break;
            case COMMAND_SET_TEST_EMERGENCY_PHONE_ACCOUNT_PACKAGE_FILTER:
                runSetEmergencyPhoneAccountPackageFilter();
                break;
            case COMMAND_LOG_MARK:
                runLogMark();
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
        boolean isEmergencyAccount = false;
        String opt;
        while ((opt = nextOption()) != null) {
            switch (opt) {
                case "-e": {
                    isEmergencyAccount = true;
                    break;
                }
            }
        }
        final PhoneAccountHandle handle = getPhoneAccountHandleFromArgs();
        final String label = nextArgRequired();
        final String address = nextArgRequired();
        int capabilities = PhoneAccount.CAPABILITY_CALL_PROVIDER
                | PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION
                | (isEmergencyAccount ? PhoneAccount.CAPABILITY_PLACE_EMERGENCY_CALLS : 0);
        PhoneAccount account = PhoneAccount.builder(
            handle, label)
                .setAddress(Uri.parse(address))
                .setSubscriptionAddress(Uri.parse(address))
                .setCapabilities(capabilities)
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

    private void runSetCallDiagnosticService() throws RemoteException {
        String packageName = nextArg();
        if ("default".equals(packageName)) packageName = null;
        mTelecomService.setTestCallDiagnosticService(packageName);
        System.out.println("Success - " + packageName + " set as call diagnostic service.");
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

    private void runStopBlockSuppression() throws RemoteException {
        mTelecomService.stopBlockSuppression();
    }

    private void runCleanupStuckCalls() throws RemoteException {
        mTelecomService.cleanupStuckCalls();
    }

    private void runCleanupOrphanPhoneAccounts() throws RemoteException {
        System.out.println("Success - cleaned up " + mTelecomService.cleanupOrphanPhoneAccounts()
                + "  phone accounts.");
    }

    private void runResetCarMode() throws RemoteException {
        mTelecomService.resetCarMode();
    }

    private void runSetDefaultDialer() throws RemoteException {
        String packageName = nextArg();
        if ("default".equals(packageName)) packageName = null;
        mTelecomService.setTestDefaultDialer(packageName);
        System.out.println("Success - " + packageName + " set as override default dialer.");
    }

    private void runSetSystemDialer() throws RemoteException {
        final String flatComponentName = nextArg();
        final ComponentName componentName = (flatComponentName.equals("default")
                ? null : parseComponentName(flatComponentName));
        mTelecomService.setSystemDialer(componentName);
        System.out.println("Success - " + componentName + " set as override system dialer.");
    }

    private void runGetDefaultDialer() throws RemoteException {
        System.out.println(mTelecomService.getDefaultDialerPackage());
    }

    private void runGetSystemDialer() throws RemoteException {
        System.out.println(mTelecomService.getSystemDialerPackage());
    }

    private void runWaitOnHandler() throws RemoteException {

    }

    private void runSetSimCount() throws RemoteException {
        if (!callerIsRoot()) {
            System.out.println("set-sim-count requires adb root");
            return;
        }
        int numSims = Integer.parseInt(nextArgRequired());
        System.out.println("Setting sim count to " + numSims + ". Device may reboot");
        mTelephonyManager.switchMultiSimConfig(numSims);
    }

    /**
     * Prints the mSIM config to the console.
     * "DSDS" for a phone in DSDS mode
     * "" (empty string) for a phone in SS mode
     */
    private void runGetSimConfig() throws RemoteException {
        System.out.println(TelephonyProperties.multi_sim_config().orElse(""));
    }

    private void runGetMaxPhones() throws RemoteException {
        // how many logical modems can be potentially active simultaneously
        System.out.println(mTelephonyManager.getSupportedModemCount());
    }

    private void runSetEmergencyPhoneAccountPackageFilter() throws RemoteException {
        String packageName = mArgs.getNextArg();
        if (TextUtils.isEmpty(packageName)) {
            mTelecomService.setTestEmergencyPhoneAccountPackageNameFilter(null);
            System.out.println("Success - filter cleared");
        } else {
            mTelecomService.setTestEmergencyPhoneAccountPackageNameFilter(packageName);
            System.out.println("Success = filter set to " + packageName);
        }

    }

    private void runLogMark() throws RemoteException {
        String message = Arrays.stream(mArgs.peekRemainingArgs()).collect(Collectors.joining(" "));
        mTelecomService.requestLogMark(message);
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

    private boolean callerIsRoot() {
        return Process.ROOT_UID == Process.myUid();
    }

    private ComponentName parseComponentName(String component) {
        ComponentName cn = ComponentName.unflattenFromString(component);
        if (cn == null) {
            throw new IllegalArgumentException ("Invalid component " + component);
        }
        return cn;
    }
}
