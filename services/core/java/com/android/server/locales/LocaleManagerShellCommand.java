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
import android.app.LocaleConfig;
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
            case "set-app-localeconfig":
                return runSetAppOverrideLocaleConfig();
            case "get-app-localeconfig":
                return runGetAppOverrideLocaleConfig();
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
        pw.println("  set-app-locales <PACKAGE_NAME> [--user <USER_ID>] [--locales <LOCALE_INFO>]"
                + "[--delegate <FROM_DELEGATE>]");
        pw.println("      Set the locales for the specified app.");
        pw.println("      --user <USER_ID>: apply for the given user, "
                + "the current user is used when unspecified.");
        pw.println("      --locales <LOCALE_INFO>: The language tags of locale to be included "
                + "as a single String separated by commas.");
        pw.println("                 eg. en,en-US,hi ");
        pw.println("                 Empty locale list is used when unspecified.");
        pw.println("      --delegate <FROM_DELEGATE>: The locales are set from a delegate, "
                + "the value could be true or false. false is the default when unspecified.");
        pw.println("  get-app-locales <PACKAGE_NAME> [--user <USER_ID>]");
        pw.println("      Get the locales for the specified app.");
        pw.println("      --user <USER_ID>: get for the given user, "
                + "the current user is used when unspecified.");
        pw.println(
                "  set-app-localeconfig <PACKAGE_NAME> [--user <USER_ID>] [--locales "
                        + "<LOCALE_INFO>]");
        pw.println("      Set the override LocaleConfig for the specified app.");
        pw.println("      --user <USER_ID>: apply for the given user, "
                + "the current user is used when unspecified.");
        pw.println("      --locales <LOCALE_INFO>: The language tags of locale to be included "
                + "as a single String separated by commas.");
        pw.println("                 eg. en,en-US,hi ");
        pw.println("                 Empty locale list is used when typing a 'empty' word");
        pw.println("                 NULL is used when unspecified.");
        pw.println("  get-app-localeconfig <PACKAGE_NAME> [--user <USER_ID>]");
        pw.println("      Get the locales within the override LocaleConfig for the specified app.");
        pw.println("      --user <USER_ID>: get for the given user, "
                + "the current user is used when unspecified.");
    }

    private int runSetAppLocales() {
        final PrintWriter err = getErrPrintWriter();
        String packageName = getNextArg();

        if (packageName != null) {
            int userId = ActivityManager.getCurrentUser();
            LocaleList locales = LocaleList.getEmptyLocaleList();
            boolean fromDelegate = false;
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
                    case "--delegate": {
                        fromDelegate = parseFromDelegate();
                        break;
                    }
                    default: {
                        throw new IllegalArgumentException("Unknown option: " + option);
                    }
                }
            } while (true);

            try {
                mBinderService.setApplicationLocales(packageName, userId, locales, fromDelegate);
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

    private int runSetAppOverrideLocaleConfig() {
        String packageName = getNextArg();

        if (packageName != null) {
            int userId = ActivityManager.getCurrentUser();
            LocaleList locales = null;
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
                        locales = parseOverrideLocales();
                        break;
                    }
                    default: {
                        throw new IllegalArgumentException("Unknown option: " + option);
                    }
                }
            } while (true);

            try {
                LocaleConfig localeConfig = locales == null ? null : new LocaleConfig(locales);
                mBinderService.setOverrideLocaleConfig(packageName, userId, localeConfig);
            } catch (RemoteException e) {
                getOutPrintWriter().println("Remote Exception: " + e);
            }
        } else {
            final PrintWriter err = getErrPrintWriter();
            err.println("Error: no package specified");
            return -1;
        }
        return 0;
    }

    private int runGetAppOverrideLocaleConfig() {
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
                LocaleConfig localeConfig = mBinderService.getOverrideLocaleConfig(packageName,
                        userId);
                if (localeConfig == null) {
                    getOutPrintWriter().println("LocaleConfig for " + packageName
                            + " for user " + userId + " is null");
                } else {
                    LocaleList locales = localeConfig.getSupportedLocales();
                    if (locales == null) {
                        getOutPrintWriter().println(
                                "Locales within the LocaleConfig for " + packageName + " for user "
                                        + userId + " are null");
                    } else {
                        getOutPrintWriter().println(
                                "Locales within the LocaleConfig for " + packageName + " for user "
                                        + userId + " are [" + locales.toLanguageTags() + "]");
                    }
                }
            } catch (RemoteException e) {
                getOutPrintWriter().println("Remote Exception: " + e);
            }
        } else {
            final PrintWriter err = getErrPrintWriter();
            err.println("Error: no package specified");
            return -1;
        }
        return 0;
    }

    private LocaleList parseOverrideLocales() {
        String locales = getNextArg();
        if (locales == null) {
            return null;
        } else if (locales.equals("empty")) {
            return LocaleList.getEmptyLocaleList();
        } else {
            if (locales.startsWith("-")) {
                throw new IllegalArgumentException("Unknown locales: " + locales);
            }
            return LocaleList.forLanguageTags(locales);
        }
    }

    private LocaleList parseLocales() {
        String locales = getNextArg();
        if (locales == null) {
            return LocaleList.getEmptyLocaleList();
        } else {
            if (locales.startsWith("-")) {
                throw new IllegalArgumentException("Unknown locales: " + locales);
            }
            return LocaleList.forLanguageTags(locales);
        }
    }

    private boolean parseFromDelegate() {
        String result = getNextArg();
        if (result == null) {
            return false;
        } else {
            if (result.startsWith("-")) {
                throw new IllegalArgumentException("Unknown source: " + result);
            }
            return Boolean.parseBoolean(result);
        }
    }
}
