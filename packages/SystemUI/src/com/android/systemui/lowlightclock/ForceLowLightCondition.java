/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.lowlightclock;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.shared.condition.Condition;
import com.android.systemui.statusbar.commandline.Command;
import com.android.systemui.statusbar.commandline.CommandRegistry;

import kotlinx.coroutines.CoroutineScope;

import java.io.PrintWriter;
import java.util.List;

import javax.inject.Inject;

/**
 * This condition registers for and fulfills cmd shell commands to force a device into or out of
 * low-light conditions.
 */
public class ForceLowLightCondition extends Condition {
    /**
     * Command root
     */
    public static final String COMMAND_ROOT = "low-light";
    /**
     * Command for forcing device into low light.
     */
    public static final String COMMAND_ENABLE_LOW_LIGHT = "enable";

    /**
     * Command for preventing a device from entering low light.
     */
    public static final String COMMAND_DISABLE_LOW_LIGHT = "disable";

    /**
     * Command for clearing previously forced low-light conditions.
     */
    public static final String COMMAND_CLEAR_LOW_LIGHT = "clear";

    private static final String TAG = "ForceLowLightCondition";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    /**
     * Default Constructor.
     *
     * @param commandRegistry command registry to register commands with.
     */
    @Inject
    public ForceLowLightCondition(
            @Application CoroutineScope scope,
            CommandRegistry commandRegistry
    ) {
        super(scope, null, true);

        if (DEBUG) {
            Log.d(TAG, "registering commands");
        }
        commandRegistry.registerCommand(COMMAND_ROOT, () -> new Command() {
            @Override
            public void execute(@NonNull PrintWriter pw, @NonNull List<String> args) {
                if (args.size() != 1) {
                    pw.println("no command specified");
                    help(pw);
                    return;
                }

                final String cmd = args.get(0);

                if (TextUtils.equals(cmd, COMMAND_ENABLE_LOW_LIGHT)) {
                    logAndPrint(pw, "forcing low light");
                    updateCondition(true);
                } else if (TextUtils.equals(cmd, COMMAND_DISABLE_LOW_LIGHT)) {
                    logAndPrint(pw, "forcing to not enter low light");
                    updateCondition(false);
                } else if (TextUtils.equals(cmd, COMMAND_CLEAR_LOW_LIGHT)) {
                    logAndPrint(pw, "clearing any forced low light");
                    clearCondition();
                } else {
                    pw.println("invalid command");
                    help(pw);
                }
            }

            @Override
            public void help(@NonNull PrintWriter pw) {
                pw.println("Usage: adb shell cmd statusbar low-light <cmd>");
                pw.println("Supported commands:");
                pw.println("  - enable");
                pw.println("    forces device into low-light");
                pw.println("  - disable");
                pw.println("    forces device to not enter low-light");
                pw.println("  - clear");
                pw.println("    clears any previously forced state");
            }

            private void logAndPrint(PrintWriter pw, String message) {
                pw.println(message);
                if (DEBUG) {
                    Log.d(TAG, message);
                }
            }
        });
    }

    @Override
    protected void start() {
    }

    @Override
    protected void stop() {
    }

    @Override
    protected int getStartStrategy() {
        return START_EAGERLY;
    }
}
