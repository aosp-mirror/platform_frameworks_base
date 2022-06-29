/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.locales;

import android.app.ActivityManager;
import android.app.ILocaleManager;
import android.os.LocaleList;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.UserHandle;

import java.io.PrintWriter;

/**
 * Shell commands for {@link LocaleManagerService}
 */
public class LocaleManagerShellCommand extends ShellCommand {
    private final ILocaleManager mBinderService;

    LocaleManagerShellCommand(ILocaleManager localeManager) {
        mBinderService = localeManager;
    }
    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        switch (cmd) {
            case "set-app-locales":
                return runSetAppLocales();
            case "get-app-locales":
                return runGetAppLocales();
            default: {
                return handleDefaultCommands(cmd);
            }
        }
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Locale manager (locale) shell commands:");
        pw.println("  help");
        pw.println("      Print this help text.");
        pw.println("  set-app-locales <PACKAGE_NAME> [--user <USER_ID>] [--locales <LOCALE_INFO>]");
        pw.println("      Set the locales for the specified app.");
        pw.println("      --user <USER_ID>: apply for the given user, "
                + "the current user is used when unspecified.");
        pw.println("      --locales <LOCALE_INFO>: The language tags of locale to be included "
                + "as a single String separated by commas");
        pw.println("                 Empty locale list is used when unspecified.");
        pw.println("                 eg. en,en-US,hi ");
        pw.println("  get-app-locales <PACKAGE_NAME> [--user <USER_ID>]");
        pw.println("      Get the locales for the specified app.");
        pw.println("      --user <USER_ID>: get for the given user, "
                + "the current user is used when unspecified.");
    }

    private int runSetAppLocales() {
        final PrintWriter err = getErrPrintWriter();
        String packageName = getNextArg();

        if (packageName != null) {
            int userId = ActivityManager.getCurrentUser();
            LocaleList locales = LocaleList.getEmptyLocaleList();
            do {
                String option = getNextOption();
                if (option == null) {
                    break;
                }
                switch (option) {
                    case "--user": {
                        userId = UserHandle.parseUserArg(getNextArgRequired());
                        break;
                    }
                    case "--locales": {
                        locales = parseLocales();
                        break;
                    }
                    default: {
                        throw new IllegalArgumentException("Unknown option: " + option);
                    }
                }
            } while (true);

            try {
                mBinderService.setApplicationLocales(packageName, userId, locales);
            } catch (RemoteException e) {
                getOutPrintWriter().println("Remote Exception: " + e);
            } catch (IllegalArgumentException e) {
                getOutPrintWriter().println("Unknown package " + packageName
                        + " for userId " + userId);
            }
        } else {
            err.println("Error: no package specified");
            return -1;
        }
        return 0;
    }

    private int runGetAppLocales() {
        final PrintWriter err = getErrPrintWriter();
        String packageName = getNextArg();

        if (packageName != null) {
            int userId = ActivityManager.getCurrentUser();
            do {
                String option = getNextOption();
                if (option == null) {
                    break;
                }
                if ("--user".equals(option)) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                    break;
                } else {
                    throw new IllegalArgumentException("Unknown option: " + option);
                }
            } while (true);
            try {
                LocaleList locales = mBinderService.getApplicationLocales(packageName, userId);
                getOutPrintWriter().println("Locales for " + packageName
                        + " for user " + userId + " are [" + locales.toLanguageTags() + "]");
            } catch (RemoteException e) {
                getOutPrintWriter().println("Remote Exception: " + e);
            } catch (IllegalArgumentException e) {
                getOutPrintWriter().println("Unknown package " + packageName
                        + " for userId " + userId);
            }
        } else {
            err.println("Error: no package specified");
            return -1;
        }
        return 0;
    }

    private LocaleList parseLocales() {
        if (getRemainingArgsCount() <= 0) {
            return LocaleList.getEmptyLocaleList();
        }
        String[] args = peekRemainingArgs();
        String inputLocales = args[0];
        LocaleList locales = LocaleList.forLanguageTags(inputLocales);
        return locales;
    }
}
