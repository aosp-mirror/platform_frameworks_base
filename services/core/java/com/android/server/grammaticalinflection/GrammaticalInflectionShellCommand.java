/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.grammaticalinflection;

import static android.content.res.Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED;

import android.app.ActivityManager;
import android.app.GrammaticalInflectionManager;
import android.app.IGrammaticalInflectionManager;
import android.content.res.Configuration;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.util.SparseArray;

import java.io.PrintWriter;

/**
 * Shell commands for {@link GrammaticalInflectionService}
 */
class GrammaticalInflectionShellCommand extends ShellCommand {

    private static final SparseArray<String> GRAMMATICAL_GENDER_MAP = new SparseArray<>();
    static {
        GRAMMATICAL_GENDER_MAP.put(Configuration.GRAMMATICAL_GENDER_NOT_SPECIFIED,
                "Not specified (0)");
        GRAMMATICAL_GENDER_MAP.put(Configuration.GRAMMATICAL_GENDER_NEUTRAL, "Neuter (1)");
        GRAMMATICAL_GENDER_MAP.put(Configuration.GRAMMATICAL_GENDER_FEMININE, "Feminine (2)");
        GRAMMATICAL_GENDER_MAP.put(Configuration.GRAMMATICAL_GENDER_MASCULINE, "Masculine (3)");
    }

    private final IGrammaticalInflectionManager mBinderService;

    GrammaticalInflectionShellCommand(IGrammaticalInflectionManager grammaticalInflectionManager) {
        mBinderService = grammaticalInflectionManager;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        switch (cmd) {
            case "set-system-grammatical-gender":
                return runSetSystemWideGrammaticalGender();
            case "get-system-grammatical-gender":
                return runGetSystemGrammaticalGender();
            default: {
                return handleDefaultCommands(cmd);
            }
        }
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Grammatical inflection manager (grammatical_inflection) shell commands:");
        pw.println("  help");
        pw.println("      Print this help text.");
        pw.println(
                "  set-system-grammatical-gender [--user <USER_ID>] [--grammaticalGender "
                        + "<GRAMMATICAL_GENDER>]");
        pw.println("      Set the system grammatical gender for system.");
        pw.println("      --user <USER_ID>: apply for the given user, "
                + "the current user is used when unspecified.");
        pw.println(
                "      --grammaticalGender <GRAMMATICAL_GENDER>: The terms of address the user "
                        + "preferred in system, not specified (0) is used when unspecified.");
        pw.println(
                "                 eg. 0 = not_specified, 1 = neuter, 2 = feminine, 3 = masculine"
                        + ".");
        pw.println(
                "  get-system-grammatical-gender [--user <USER_ID>]");
        pw.println("      Get the system grammatical gender for system.");
        pw.println("      --user <USER_ID>: apply for the given user, "
                + "the current user is used when unspecified.");
    }

    private int runSetSystemWideGrammaticalGender() {
        int userId = ActivityManager.getCurrentUser();
        int grammaticalGender = GRAMMATICAL_GENDER_NOT_SPECIFIED;
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
                case "-g":
                case "--grammaticalGender": {
                    grammaticalGender = parseGrammaticalGender();
                    break;
                }
                default: {
                    throw new IllegalArgumentException("Unknown option: " + option);
                }
            }
        } while (true);

        try {
            mBinderService.setSystemWideGrammaticalGender(userId, grammaticalGender);
        } catch (RemoteException e) {
            getOutPrintWriter().println("Remote Exception: " + e);
        }
        return 0;
    }

    private int runGetSystemGrammaticalGender() {
        int userId = ActivityManager.getCurrentUser();
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
                default: {
                    throw new IllegalArgumentException("Unknown option: " + option);
                }
            }
        } while (true);

        try {
            int grammaticalGender = mBinderService.getSystemGrammaticalGender(userId);
            getOutPrintWriter().println(GRAMMATICAL_GENDER_MAP.get(grammaticalGender));
        } catch (RemoteException e) {
            getOutPrintWriter().println("Remote Exception: " + e);
        }
        return 0;
    }

    private int parseGrammaticalGender() {
        String arg = getNextArg();
        if (arg == null) {
            return GRAMMATICAL_GENDER_NOT_SPECIFIED;
        } else {
            int grammaticalGender = Integer.parseInt(arg);
            if (GrammaticalInflectionManager.VALID_GRAMMATICAL_GENDER_VALUES.contains(
                    grammaticalGender)) {
                return grammaticalGender;
            } else {
                return GRAMMATICAL_GENDER_NOT_SPECIFIED;
            }
        }
    }
}
