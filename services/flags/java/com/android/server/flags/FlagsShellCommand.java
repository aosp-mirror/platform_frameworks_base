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

package com.android.server.flags;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastPrintWriter;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.Map;

/**
 * Process command line input for the flags service.
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class FlagsShellCommand {
    private final FlagOverrideStore mFlagStore;

    FlagsShellCommand(FlagOverrideStore flagStore) {
        mFlagStore = flagStore;
    }

    /**
     * Interpret the command supplied in the constructor.
     *
     * @return Zero on success or non-zero on error.
     */
    public int process(
            String[] args,
            OutputStream out,
            OutputStream err) {
        PrintWriter outPw = new FastPrintWriter(out);
        PrintWriter errPw = new FastPrintWriter(err);

        if (args.length == 0) {
            return printHelp(outPw);
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help":
                return printHelp(outPw);
            case "list":
                return listCmd(args, outPw, errPw);
            case "set":
                return setCmd(args, outPw, errPw);
            case "get":
                return getCmd(args, outPw, errPw);
            case "erase":
                return eraseCmd(args, outPw, errPw);
            default:
                return unknownCmd(outPw);
        }
    }

    private int printHelp(PrintWriter outPw) {
        outPw.println("Feature Flags command, allowing listing, setting, getting, and erasing of");
        outPw.println("local flag overrides on a device.");
        outPw.println();
        outPw.println("Commands:");
        outPw.println("  list [namespace]");
        outPw.println("    List all flag overrides. Namespace is optional.");
        outPw.println();
        outPw.println("  get <namespace> <name>");
        outPw.println("    Return the string value of a specific flag, or <unset>");
        outPw.println();
        outPw.println("  set <namespace> <name> <value>");
        outPw.println("    Set a specific flag");
        outPw.println();
        outPw.println("  erase <namespace> <name>");
        outPw.println("    Unset a specific flag");
        outPw.flush();
        return 0;
    }

    private int listCmd(String[] args, PrintWriter outPw, PrintWriter errPw) {
        if (!validateNumArguments(args, 0, 1, args[0], errPw)) {
            errPw.println("Expected `" + args[0] + " [namespace]`");
            errPw.flush();
            return -1;
        }
        Map<String, Map<String, String>> overrides;
        if (args.length == 2) {
            overrides = mFlagStore.getFlagsForNamespace(args[1]);
        } else {
            overrides = mFlagStore.getFlags();
        }
        if (overrides.isEmpty()) {
            outPw.println("No overrides set");
        } else {
            int longestNamespaceLen = "namespace".length();
            int longestFlagLen = "flag".length();
            int longestValLen = "value".length();
            for (Map.Entry<String, Map<String, String>> namespace : overrides.entrySet()) {
                longestNamespaceLen = Math.max(longestNamespaceLen, namespace.getKey().length());
                for (Map.Entry<String, String> flag : namespace.getValue().entrySet()) {
                    longestFlagLen = Math.max(longestFlagLen, flag.getKey().length());
                    longestValLen = Math.max(longestValLen, flag.getValue().length());
                }
            }
            outPw.print(String.format("%-" + longestNamespaceLen + "s", "namespace"));
            outPw.print(' ');
            outPw.print(String.format("%-" + longestFlagLen + "s", "flag"));
            outPw.print(' ');
            outPw.println("value");
            for (int i = 0; i < longestNamespaceLen; i++) {
                outPw.print('=');
            }
            outPw.print(' ');
            for (int i = 0; i < longestFlagLen; i++) {
                outPw.print('=');
            }
            outPw.print(' ');
            for (int i = 0; i < longestValLen; i++) {
                outPw.print('=');
            }
            outPw.println();
            for (Map.Entry<String, Map<String, String>> namespace : overrides.entrySet()) {
                for (Map.Entry<String, String> flag : namespace.getValue().entrySet()) {
                    outPw.print(
                            String.format("%-" + longestNamespaceLen + "s", namespace.getKey()));
                    outPw.print(' ');
                    outPw.print(String.format("%-" + longestFlagLen + "s", flag.getKey()));
                    outPw.print(' ');
                    outPw.println(flag.getValue());
                }
            }
        }
        outPw.flush();
        return 0;
    }

    private int setCmd(String[] args, PrintWriter outPw, PrintWriter errPw) {
        if (!validateNumArguments(args, 3, args[0], errPw)) {
            errPw.println("Expected `" + args[0] + " <namespace> <name> <value>`");
            errPw.flush();
            return -1;
        }
        mFlagStore.set(args[1], args[2], args[3]);
        outPw.println("Flag " + args[1] + "." + args[2] + " is now " + args[3]);
        outPw.flush();
        return 0;
    }

    private int getCmd(String[] args, PrintWriter outPw, PrintWriter errPw) {
        if (!validateNumArguments(args, 2, args[0], errPw)) {
            errPw.println("Expected `" + args[0] + " <namespace> <name>`");
            errPw.flush();
            return -1;
        }

        String value = mFlagStore.get(args[1], args[2]);
        outPw.print(args[1] + "." + args[2] + " is ");
        if (value == null || value.isEmpty()) {
            outPw.println("<unset>");
        } else {
            outPw.println("\"" + value.translateEscapes() + "\"");
        }
        outPw.flush();
        return 0;
    }

    private int eraseCmd(String[] args, PrintWriter outPw, PrintWriter errPw) {
        if (!validateNumArguments(args, 2, args[0], errPw)) {
            errPw.println("Expected `" + args[0] + " <namespace> <name>`");
            errPw.flush();
            return -1;
        }
        mFlagStore.erase(args[1], args[2]);
        outPw.println("Erased " + args[1] + "." + args[2]);
        return 0;
    }

    private int unknownCmd(PrintWriter outPw) {
        outPw.println("This command is unknown.");
        printHelp(outPw);
        outPw.flush();
        return -1;
    }

    private boolean validateNumArguments(
            String[] args, int exactly, String cmdName, PrintWriter errPw) {
        return validateNumArguments(args, exactly, exactly, cmdName, errPw);
    }

    private boolean validateNumArguments(
            String[] args, int min, int max, String cmdName, PrintWriter errPw) {
        int len = args.length - 1; // Discount the command itself.
        if (len < min) {
            errPw.println(
                    "Less than " + min + " arguments provided for \"" + cmdName + "\" command.");
            return false;
        } else if (len > max) {
            errPw.println(
                    "More than " + max + " arguments provided for \"" + cmdName + "\" command.");
            return false;
        }

        return true;
    }
}
