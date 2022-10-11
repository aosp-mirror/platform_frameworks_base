/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.flags;

import androidx.annotation.NonNull;

import com.android.systemui.statusbar.commandline.Command;

import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * A {@link Command} used to flip flags in SystemUI.
 */
public class FlagCommand implements Command {
    public static final String FLAG_COMMAND = "flag";

    private final List<String> mOnCommands = List.of("true", "on", "1", "enabled");
    private final List<String> mOffCommands = List.of("false", "off", "0", "disable");
    private final FeatureFlagsDebug mFeatureFlags;
    private final Map<Integer, Flag<?>> mAllFlags;

    @Inject
    FlagCommand(
            FeatureFlagsDebug featureFlags,
            @Named(FeatureFlagsDebug.ALL_FLAGS) Map<Integer, Flag<?>> allFlags
    ) {
        mFeatureFlags = featureFlags;
        mAllFlags = allFlags;
    }

    @Override
    public void execute(@NonNull PrintWriter pw, @NonNull List<String> args) {
        if (args.size() == 0) {
            pw.println("Error: no flag id supplied");
            help(pw);
            pw.println();
            printKnownFlags(pw);
            return;
        }

        if (args.size() > 2) {
            pw.println("Invalid number of arguments.");
            help(pw);
            return;
        }

        int id = 0;
        try {
            id = Integer.parseInt(args.get(0));
            if (!mAllFlags.containsKey(id)) {
                pw.println("Unknown flag id: " + id);
                pw.println();
                printKnownFlags(pw);
                return;
            }
        } catch (NumberFormatException e) {
            id = flagNameToId(args.get(0));
            if (id == 0) {
                pw.println("Invalid flag. Must an integer id or flag name: " + args.get(0));
                return;
            }
        }
        Flag<?> flag = mAllFlags.get(id);

        String cmd = "";
        if (args.size() == 2) {
            cmd = args.get(1).toLowerCase();
        }

        if ("erase".equals(cmd) || "reset".equals(cmd)) {
            mFeatureFlags.eraseFlag(flag);
            return;
        }

        boolean newValue = true;
        if (args.size() == 1 || "toggle".equals(cmd)) {
            boolean enabled = isBooleanFlagEnabled(flag);

            if (args.size() == 1) {
                pw.println("Flag " + id + " is " + enabled);
                return;
            }

            newValue = !enabled;
        } else {
            newValue = mOnCommands.contains(cmd);
            if (!newValue && !mOffCommands.contains(cmd)) {
                pw.println("Invalid on/off argument supplied");
                help(pw);
                return;
            }
        }

        pw.flush();  // Next command will restart sysui, so flush before we do so.
        mFeatureFlags.setBooleanFlagInternal(flag, newValue);
    }

    @Override
    public void help(PrintWriter pw) {
        pw.println(
                "Usage: adb shell cmd statusbar flag <id> "
                        + "[true|false|1|0|on|off|enable|disable|toggle|erase|reset]");
        pw.println("The id can either be a numeric integer or the corresponding field name");
        pw.println(
                "If no argument is supplied after the id, the flags runtime value is output");
    }

    private boolean isBooleanFlagEnabled(Flag<?> flag) {
        if (flag instanceof ReleasedFlag) {
            return mFeatureFlags.isEnabled((ReleasedFlag) flag);
        } else if (flag instanceof UnreleasedFlag) {
            return mFeatureFlags.isEnabled((UnreleasedFlag) flag);
        } else if (flag instanceof ResourceBooleanFlag) {
            return mFeatureFlags.isEnabled((ResourceBooleanFlag) flag);
        } else if (flag instanceof SysPropFlag) {
            return mFeatureFlags.isEnabled((SysPropBooleanFlag) flag);
        }

        return false;
    }

    private int flagNameToId(String flagName) {
        List<Field> fields = Flags.getFlagFields();
        for (Field field : fields) {
            if (flagName.equals(field.getName())) {
                return fieldToId(field);
            }
        }

        return 0;
    }

    private int fieldToId(Field field) {
        try {
            Flag<?> flag = (Flag<?>) field.get(null);
            return flag.getId();
        } catch (IllegalAccessException e) {
            // no-op
        }

        return 0;
    }

    private void printKnownFlags(PrintWriter pw) {
        List<Field> fields = Flags.getFlagFields();

        int longestFieldName = 0;
        for (Field field : fields) {
            longestFieldName = Math.max(longestFieldName, field.getName().length());
        }

        pw.println("Known Flags:");
        pw.print("Flag Name");
        for (int i = 0; i < longestFieldName - "Flag Name".length() + 1; i++) {
            pw.print(" ");
        }
        pw.println("ID   Enabled?");
        for (int i = 0; i < longestFieldName; i++) {
            pw.print("=");
        }
        pw.println(" ==== ========");
        for (Field field : fields) {
            int id = fieldToId(field);
            if (id == 0 || !mAllFlags.containsKey(id)) {
                continue;
            }
            pw.print(field.getName());
            int fieldWidth = field.getName().length();
            for (int i = 0; i < longestFieldName - fieldWidth + 1; i++) {
                pw.print(" ");
            }
            pw.printf("%-4d ", id);
            pw.println(isBooleanFlagEnabled(mAllFlags.get(id)));
        }
    }
}
