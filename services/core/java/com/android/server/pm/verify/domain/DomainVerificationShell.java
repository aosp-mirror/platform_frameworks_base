/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm.verify.domain;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.pm.verify.domain.DomainVerificationState;
import android.content.pm.verify.domain.DomainVerificationUserState;
import android.os.Binder;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.IndentingPrintWriter;

import com.android.modules.utils.BasicShellCommandHandler;
import com.android.server.pm.Computer;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class DomainVerificationShell {

    @NonNull
    private final Callback mCallback;

    public DomainVerificationShell(@NonNull Callback callback) {
        mCallback = callback;
    }

    public void printHelp(@NonNull PrintWriter pw) {
        pw.println("  get-app-links [--user <USER_ID>] [<PACKAGE>]");
        pw.println("    Prints the domain verification state for the given package, or for all");
        pw.println("    packages if none is specified. State codes are defined as follows:");
        pw.println("        - none: nothing has been recorded for this domain");
        pw.println("        - verified: the domain has been successfully verified");
        pw.println("        - approved: force approved, usually through shell");
        pw.println("        - denied: force denied, usually through shell");
        pw.println("        - migrated: preserved verification from a legacy response");
        pw.println("        - restored: preserved verification from a user data restore");
        pw.println("        - legacy_failure: rejected by a legacy verifier, unknown reason");
        pw.println("        - system_configured: automatically approved by the device config");
        pw.println("        - pre_verified: the domain was pre-verified by the installer");
        pw.println("        - >= 1024: Custom error code which is specific to the device verifier");
        pw.println("      --user <USER_ID>: include user selections (includes all domains, not");
        pw.println("        just autoVerify ones)");
        pw.println("  reset-app-links [--user <USER_ID>] [<PACKAGE>]");
        pw.println("    Resets domain verification state for the given package, or for all");
        pw.println("    packages if none is specified.");
        pw.println("      --user <USER_ID>: clear user selection state instead; note this means");
        pw.println("        domain verification state will NOT be cleared");
        pw.println("      <PACKAGE>: the package to reset, or \"all\" to reset all packages");
        pw.println("  verify-app-links [--re-verify] [<PACKAGE>]");
        pw.println("    Broadcasts a verification request for the given package, or for all");
        pw.println("    packages if none is specified. Only sends if the package has previously");
        pw.println("    not recorded a response.");
        pw.println("      --re-verify: send even if the package has recorded a response");
        pw.println("  set-app-links [--package <PACKAGE>] <STATE> <DOMAINS>...");
        pw.println("    Manually set the state of a domain for a package. The domain must be");
        pw.println("    declared by the package as autoVerify for this to work. This command");
        pw.println("    will not report a failure for domains that could not be applied.");
        pw.println("      --package <PACKAGE>: the package to set, or \"all\" to set all packages");
        pw.println("      <STATE>: the code to set the domains to, valid values are:");
        pw.println("        STATE_NO_RESPONSE (0): reset as if no response was ever recorded.");
        pw.println("        STATE_SUCCESS (1): treat domain as successfully verified by domain.");
        pw.println("          verification agent. Note that the domain verification agent can");
        pw.println("          override this.");
        pw.println("        STATE_APPROVED (2): treat domain as always approved, preventing the");
        pw.println("           domain verification agent from changing it.");
        pw.println("        STATE_DENIED (3): treat domain as always denied, preveting the domain");
        pw.println("          verification agent from changing it.");
        pw.println("      <DOMAINS>: space separated list of domains to change, or \"all\" to");
        pw.println("        change every domain.");
        pw.println("  set-app-links-user-selection --user <USER_ID> [--package <PACKAGE>]");
        pw.println("      <ENABLED> <DOMAINS>...");
        pw.println("    Manually set the state of a host user selection for a package. The domain");
        pw.println("    must be declared by the package for this to work. This command will not");
        pw.println("    report a failure for domains that could not be applied.");
        pw.println("      --user <USER_ID>: the user to change selections for");
        pw.println("      --package <PACKAGE>: the package to set");
        pw.println("      <ENABLED>: whether or not to approve the domain");
        pw.println("      <DOMAINS>: space separated list of domains to change, or \"all\" to");
        pw.println("        change every domain.");
        pw.println("  set-app-links-allowed --user <USER_ID> [--package <PACKAGE>] <ALLOWED>");
        pw.println("    Toggle the auto verified link handling setting for a package.");
        pw.println("      --user <USER_ID>: the user to change selections for");
        pw.println("      --package <PACKAGE>: the package to set, or \"all\" to set all packages");
        pw.println("        packages will be reset if no one package is specified.");
        pw.println("      <ALLOWED>: true to allow the package to open auto verified links, false");
        pw.println("        to disable");
        pw.println("  get-app-link-owners [--user <USER_ID>] [--package <PACKAGE>] [<DOMAINS>]");
        pw.println("    Print the owners for a specific domain for a given user in low to high");
        pw.println("    priority order.");
        pw.println("      --user <USER_ID>: the user to query for");
        pw.println("      --package <PACKAGE>: optionally also print for all web domains declared");
        pw.println("        by a package, or \"all\" to print all packages");
        pw.println("      --<DOMAINS>: space separated list of domains to query for");
    }

    /**
     * Run a shell/debugging command.
     *
     * @return null if the command is unhandled, true if the command succeeded, false if it failed
     */
    public Boolean runCommand(@NonNull BasicShellCommandHandler commandHandler,
            @NonNull String command) {
        switch (command) {
            case "get-app-links":
                return runGetAppLinks(commandHandler);
            case "reset-app-links":
                return runResetAppLinks(commandHandler);
            case "verify-app-links":
                return runVerifyAppLinks(commandHandler);
            case "set-app-links":
                return runSetAppLinks(commandHandler);
            case "set-app-links-user-selection":
                return runSetAppLinksUserState(commandHandler);
            case "set-app-links-allowed":
                return runSetAppLinksAllowed(commandHandler);
            case "get-app-link-owners":
                return runGetAppLinkOwners(commandHandler);
        }

        return null;
    }


    // pm set-app-links [--package <PACKAGE>] <STATE> <DOMAINS>...
    private boolean runSetAppLinks(@NonNull BasicShellCommandHandler commandHandler) {
        String packageName = null;

        String option;
        while ((option = commandHandler.getNextOption()) != null) {
            if (option.equals("--package")) {
                packageName = commandHandler.getNextArgRequired();
            } else {
                commandHandler.getErrPrintWriter().println("Error: unknown option: " + option);
                return false;
            }
        }

        if (TextUtils.isEmpty(packageName)) {
            commandHandler.getErrPrintWriter().println("Error: no package specified");
            return false;
        } else if (packageName.equalsIgnoreCase("all")) {
            packageName = null;
        }

        String state = commandHandler.getNextArgRequired();
        int stateInt;
        switch (state) {
            case "STATE_NO_RESPONSE":
            case "0":
                stateInt = DomainVerificationState.STATE_NO_RESPONSE;
                break;
            case "STATE_SUCCESS":
            case "1":
                stateInt = DomainVerificationState.STATE_SUCCESS;
                break;
            case "STATE_APPROVED":
            case "2":
                stateInt = DomainVerificationState.STATE_APPROVED;
                break;
            case "STATE_DENIED":
            case "3":
                stateInt = DomainVerificationState.STATE_DENIED;
                break;
            default:
                commandHandler.getErrPrintWriter().println("Invalid state option: " + state);
                return false;
        }

        ArraySet<String> domains = new ArraySet<>(getRemainingArgs(commandHandler));
        if (domains.isEmpty()) {
            commandHandler.getErrPrintWriter().println("No domains specified");
            return false;
        }

        if (domains.size() == 1 && domains.contains("all")) {
            domains = null;
        }

        try {
            mCallback.setDomainVerificationStatusInternal(packageName, stateInt,
                    domains);
        } catch (NameNotFoundException e) {
            commandHandler.getErrPrintWriter().println("Package not found: " + packageName);
            return false;
        }
        return true;
    }

    // pm set-app-links-user-selection --user <USER_ID> [--package <PACKAGE>] <ENABLED> <DOMAINS>...
    private boolean runSetAppLinksUserState(@NonNull BasicShellCommandHandler commandHandler) {
        Integer userId = null;
        String packageName = null;

        String option;
        while ((option = commandHandler.getNextOption()) != null) {
            switch (option) {
                case "--user":
                    userId = UserHandle.parseUserArg(commandHandler.getNextArgRequired());
                    break;
                case "--package":
                    packageName = commandHandler.getNextArgRequired();
                    break;
                default:
                    commandHandler.getErrPrintWriter().println("Error: unknown option: " + option);
                    return false;
            }
        }

        if (TextUtils.isEmpty(packageName)) {
            commandHandler.getErrPrintWriter().println("Error: no package specified");
            return false;
        }

        if (userId == null) {
            commandHandler.getErrPrintWriter().println("Error: User ID not specified");
            return false;
        }

        userId = translateUserId(userId, "runSetAppLinksUserState");

        String enabledArg = commandHandler.getNextArg();
        if (TextUtils.isEmpty(enabledArg)) {
            commandHandler.getErrPrintWriter().println("Error: enabled param not specified");
            return false;
        }

        boolean enabled;
        try {
            enabled = parseEnabled(enabledArg);
        } catch (IllegalArgumentException e) {
            commandHandler.getErrPrintWriter()
                    .println("Error: invalid enabled param: " + e.getMessage());
            return false;
        }

        ArraySet<String> domains = new ArraySet<>(getRemainingArgs(commandHandler));
        if (domains.isEmpty()) {
            commandHandler.getErrPrintWriter().println("No domains specified");
            return false;
        }

        if (domains.size() == 1 && domains.contains("all")) {
            domains = null;
        }

        try {
            mCallback.setDomainVerificationUserSelectionInternal(userId, packageName, enabled,
                    domains);
        } catch (NameNotFoundException e) {
            commandHandler.getErrPrintWriter().println("Package not found: " + packageName);
            return false;
        }
        return true;
    }

    // pm get-app-links [--user <USER_ID>] [<PACKAGE>]
    private boolean runGetAppLinks(@NonNull BasicShellCommandHandler commandHandler) {
        Integer userId = null;

        String option;
        while ((option = commandHandler.getNextOption()) != null) {
            if (option.equals("--user")) {
                userId = UserHandle.parseUserArg(commandHandler.getNextArgRequired());
            } else {
                commandHandler.getErrPrintWriter().println("Error: unknown option: " + option);
                return false;
            }
        }

        userId = userId == null ? null : translateUserId(userId, "runGetAppLinks");

        String packageName = commandHandler.getNextArg();

        try (IndentingPrintWriter writer = new IndentingPrintWriter(
                commandHandler.getOutPrintWriter(), /* singleIndent */ "  ", /* wrapLength */
                120)) {
            writer.increaseIndent();
            try {
                mCallback.printState(writer, packageName, userId);
            } catch (NameNotFoundException e) {
                commandHandler.getErrPrintWriter().println(
                        "Error: package " + packageName + " unavailable");
                return false;
            }
            writer.decreaseIndent();
            return true;
        }
    }

    // pm reset-app-links [--user USER_ID] [<PACKAGE>]
    private boolean runResetAppLinks(@NonNull BasicShellCommandHandler commandHandler) {
        Integer userId = null;

        String option;
        while ((option = commandHandler.getNextOption()) != null) {
            if (option.equals("--user")) {
                userId = UserHandle.parseUserArg(commandHandler.getNextArgRequired());
            } else {
                commandHandler.getErrPrintWriter().println("Error: unknown option: " + option);
                return false;
            }
        }

        userId = userId == null ? null : translateUserId(userId, "runResetAppLinks");

        List<String> packageNames;
        String pkgNameArg = commandHandler.peekNextArg();
        if (TextUtils.isEmpty(pkgNameArg)) {
            commandHandler.getErrPrintWriter().println("Error: no package specified");
            return false;
        } else if (pkgNameArg.equalsIgnoreCase("all")) {
            packageNames = null;
        } else {
            packageNames = Arrays.asList(commandHandler.peekRemainingArgs());
        }

        if (userId != null) {
            mCallback.clearUserStates(packageNames, userId);
        } else {
            mCallback.clearDomainVerificationState(packageNames);
        }

        return true;
    }

    // pm verify-app-links [--re-verify] [<PACKAGE>]
    private boolean runVerifyAppLinks(@NonNull BasicShellCommandHandler commandHandler) {
        boolean reVerify = false;
        String option;
        while ((option = commandHandler.getNextOption()) != null) {
            if (option.equals("--re-verify")) {
                reVerify = true;
            } else {
                commandHandler.getErrPrintWriter().println("Error: unknown option: " + option);
                return false;
            }
        }

        List<String> packageNames = null;
        String pkgNameArg = commandHandler.getNextArg();
        if (!TextUtils.isEmpty(pkgNameArg)) {
            packageNames = Collections.singletonList(pkgNameArg);
        }

        mCallback.verifyPackages(packageNames, reVerify);

        return true;
    }

    // pm set-app-links-allowed [--package <PACKAGE>] [--user <USER_ID>] <ALLOWED>
    private boolean runSetAppLinksAllowed(@NonNull BasicShellCommandHandler commandHandler) {
        String packageName = null;
        Integer userId = null;
        String option;
        while ((option = commandHandler.getNextOption()) != null) {
            if (option.equals("--package")) {
                packageName = commandHandler.getNextArg();
            } else if (option.equals("--user")) {
                userId = UserHandle.parseUserArg(commandHandler.getNextArgRequired());
            } else {
                commandHandler.getErrPrintWriter().println("Error: unexpected option: " + option);
                return false;
            }
        }

        if (TextUtils.isEmpty(packageName)) {
            commandHandler.getErrPrintWriter().println("Error: no package specified");
            return false;
        } else if (packageName.equalsIgnoreCase("all")) {
            packageName = null;
        }

        if (userId == null) {
            commandHandler.getErrPrintWriter().println("Error: user ID not specified");
            return false;
        }

        String allowedArg = commandHandler.getNextArg();
        if (TextUtils.isEmpty(allowedArg)) {
            commandHandler.getErrPrintWriter().println("Error: allowed setting not specified");
            return false;
        }

        boolean allowed;
        try {
            allowed = parseEnabled(allowedArg);
        } catch (IllegalArgumentException e) {
            commandHandler.getErrPrintWriter()
                    .println("Error: invalid allowed setting: " + e.getMessage());
            return false;
        }

        userId = translateUserId(userId, "runSetAppLinksAllowed");

        try {
            mCallback.setDomainVerificationLinkHandlingAllowedInternal(packageName, allowed,
                    userId);
        } catch (NameNotFoundException e) {
            commandHandler.getErrPrintWriter().println("Package not found: " + packageName);
            return false;
        }

        return true;
    }

    // pm get-app-link-owners [--user <USER_ID>] [--package <PACKAGE>] [<DOMAINS>]
    private boolean runGetAppLinkOwners(@NonNull BasicShellCommandHandler commandHandler) {
        String packageName = null;
        Integer userId = null;
        String option;
        while ((option = commandHandler.getNextOption()) != null) {
            switch (option) {
                case "--user":
                    userId = UserHandle.parseUserArg(commandHandler.getNextArgRequired());
                    break;
                case "--package":
                    packageName = commandHandler.getNextArgRequired();
                    if (TextUtils.isEmpty(packageName)) {
                        commandHandler.getErrPrintWriter().println("Error: no package specified");
                        return false;
                    }
                    break;
                default:
                    commandHandler.getErrPrintWriter().println(
                            "Error: unexpected option: " + option);
                    return false;
            }
        }

        ArrayList<String> domains = getRemainingArgs(commandHandler);
        if (domains.isEmpty() && TextUtils.isEmpty(packageName)) {
            commandHandler.getErrPrintWriter()
                    .println("Error: no package name or domain specified");
            return false;
        }

        if (userId != null) {
            userId = translateUserId(userId, "runSetAppLinksAllowed");
        }

        try (IndentingPrintWriter writer = new IndentingPrintWriter(
                commandHandler.getOutPrintWriter(), /* singleIndent */ "  ", /* wrapLength */
                120)) {
            writer.increaseIndent();
            if (packageName != null) {
                if (packageName.equals("all")) {
                    packageName = null;
                }

                try {
                    mCallback.printOwnersForPackage(writer, packageName, userId);
                } catch (NameNotFoundException e) {
                    commandHandler.getErrPrintWriter()
                            .println("Error: package not found: " + packageName);
                    return false;
                }
            }
            if (!domains.isEmpty()) {
                mCallback.printOwnersForDomains(writer, domains, userId);
            }
            writer.decreaseIndent();
            return true;
        }
    }

    @NonNull
    private ArrayList<String> getRemainingArgs(@NonNull BasicShellCommandHandler commandHandler) {
        ArrayList<String> args = new ArrayList<>();
        String arg;
        while ((arg = commandHandler.getNextArg()) != null) {
            args.add(arg);
        }
        return args;
    }

    private int translateUserId(@UserIdInt int userId, @NonNull String logContext) {
        return ActivityManager.handleIncomingUser(Binder.getCallingPid(), Binder.getCallingUid(),
                userId, true, true, logContext, "pm command");
    }

    /**
     * Manually ensure that "true" and "false" are the only options, to ensure a domain isn't
     * accidentally parsed as a boolean.
     */
    @NonNull
    private boolean parseEnabled(@NonNull String arg) throws IllegalArgumentException {
        switch (arg.toLowerCase(Locale.US)) {
            case "true":
                return true;
            case "false":
                return false;
            default:
                throw new IllegalArgumentException(arg + " is not a valid boolean");
        }
    }

    /**
     * Separated interface from {@link DomainVerificationManagerInternal} to hide methods that are
     * even more internal, and so that testing is easier.
     */
    public interface Callback {

        /**
         * Variant for use by PackageManagerShellCommand to allow the system/developer to override
         * the state for a domain.
         *
         * @param packageName the package whose state to change, or all packages if none is
         *                    specified
         * @param state       the new state code, valid values are
         *                    {@link DomainVerificationState#STATE_NO_RESPONSE},
         *                    {@link DomainVerificationState#STATE_SUCCESS}, {@link
         *                    DomainVerificationState#STATE_APPROVED}, and {@link
         *                    DomainVerificationState#STATE_DENIED}
         * @param domains     the set of domains to change, or null to change all of them
         */
        void setDomainVerificationStatusInternal(@Nullable String packageName, int state,
                @Nullable ArraySet<String> domains) throws PackageManager.NameNotFoundException;

        /**
         * Variant for use by PackageManagerShellCommand to allow the system/developer to override
         * the state for a domain.
         *
         * If an approval fails because of a higher level owner, this method will silently skip the
         * domain.
         *
         * @param packageName the package whose state to change
         * @param enabled     whether the domain is now approved by the user
         * @param domains     the set of domains to change, or null to affect all domains
         */
        void setDomainVerificationUserSelectionInternal(@UserIdInt int userId,
                @NonNull String packageName, boolean enabled, @Nullable ArraySet<String> domains)
                throws PackageManager.NameNotFoundException;

        /**
         * @see DomainVerificationManager#getDomainVerificationUserState(String)
         */
        @Nullable
        DomainVerificationUserState getDomainVerificationUserState(
                @NonNull String packageName, @UserIdInt int userId)
                throws PackageManager.NameNotFoundException;

        /**
         * Variant for use by PackageManagerShellCommand to allow the system/developer to override
         * the setting for a package.
         *
         * @param packageName the package whose state to change, or all packages if non is
         *                    specified
         * @param allowed     whether the package is allowed to automatically open links through
         *                    domain verification
         */
        void setDomainVerificationLinkHandlingAllowedInternal(@Nullable String packageName,
                boolean allowed, @UserIdInt int userId) throws NameNotFoundException;

        /**
         * Reset all the domain verification states for all domains for the given package names, or
         * all package names if null is provided.
         */
        void clearDomainVerificationState(@Nullable List<String> packageNames);

        /**
         * Reset all the user selections for the given package names, or all package names if null
         * is provided.
         */
        void clearUserStates(@Nullable List<String> packageNames, @UserIdInt int userId);

        /**
         * Broadcast a verification request for the given package names, or all package names if
         * null is provided. By default only re-broadcasts if a package has not recorded a
         * response.
         *
         * @param reVerify send even if the package has previously recorded a response
         */
        void verifyPackages(@Nullable List<String> packageNames, boolean reVerify);

        /**
         * @see DomainVerificationManagerInternal#printState(Computer, IndentingPrintWriter, String,
         * Integer)
         */
        void printState(@NonNull IndentingPrintWriter writer, @Nullable String packageName,
                @Nullable @UserIdInt Integer userId) throws NameNotFoundException;

        /**
         * Print the owners for all domains in a given package.
         */
        void printOwnersForPackage(@NonNull IndentingPrintWriter writer,
                @Nullable String packageName, @Nullable @UserIdInt Integer userId)
                throws NameNotFoundException;

        /**
         * Print the owners for the given domains.
         */
        void printOwnersForDomains(@NonNull IndentingPrintWriter writer,
                @NonNull List<String> domains, @Nullable @UserIdInt Integer userId);
    }
}
