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

package com.android.wm.shell;

import com.android.wm.shell.protolog.ShellProtoLogImpl;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellInit;

import java.io.PrintWriter;
import java.util.Arrays;

/**
 * Controls the {@link ShellProtoLogImpl} in WMShell via adb shell commands.
 *
 * Use with {@code adb shell dumpsys activity service SystemUIService WMShell protolog ...}.
 */
public class ProtoLogController implements ShellCommandHandler.ShellCommandActionHandler {
    private final ShellCommandHandler mShellCommandHandler;
    private final ShellProtoLogImpl mShellProtoLog;

    public ProtoLogController(ShellInit shellInit,
            ShellCommandHandler shellCommandHandler) {
        shellInit.addInitCallback(this::onInit, this);
        mShellCommandHandler = shellCommandHandler;
        mShellProtoLog = ShellProtoLogImpl.getSingleInstance();
    }

    void onInit() {
        mShellCommandHandler.addCommandCallback("protolog", this, this);
    }

    @Override
    public boolean onShellCommand(String[] args, PrintWriter pw) {
        switch (args[0]) {
            case "status": {
                pw.println(mShellProtoLog.getStatus());
                return true;
            }
            case "start": {
                mShellProtoLog.startProtoLog(pw);
                return true;
            }
            case "stop": {
                mShellProtoLog.stopProtoLog(pw, true /* writeToFile */);
                return true;
            }
            case "enable-text": {
                String[] groups = Arrays.copyOfRange(args, 1, args.length);
                int result = mShellProtoLog.startTextLogging(groups, pw);
                if (result == 0) {
                    pw.println("Starting logging on groups: " + Arrays.toString(groups));
                    return true;
                }
                return false;
            }
            case "disable-text": {
                String[] groups = Arrays.copyOfRange(args, 1, args.length);
                int result = mShellProtoLog.stopTextLogging(groups, pw);
                if (result == 0) {
                    pw.println("Stopping logging on groups: " + Arrays.toString(groups));
                    return true;
                }
                return false;
            }
            case "enable": {
                String[] groups = Arrays.copyOfRange(args, 1, args.length);
                return mShellProtoLog.startTextLogging(groups, pw) == 0;
            }
            case "disable": {
                String[] groups = Arrays.copyOfRange(args, 1, args.length);
                return mShellProtoLog.stopTextLogging(groups, pw) == 0;
            }
            default: {
                pw.println("Invalid command: " + args[0]);
                printShellCommandHelp(pw, "");
                return false;
            }
        }
    }

    @Override
    public void printShellCommandHelp(PrintWriter pw, String prefix) {
        pw.println(prefix + "status");
        pw.println(prefix + "  Get current ProtoLog status.");
        pw.println(prefix + "start");
        pw.println(prefix + "  Start proto logging.");
        pw.println(prefix + "stop");
        pw.println(prefix + "  Stop proto logging and flush to file.");
        pw.println(prefix + "enable [group...]");
        pw.println(prefix + "  Enable proto logging for given groups.");
        pw.println(prefix + "disable [group...]");
        pw.println(prefix + "  Disable proto logging for given groups.");
        pw.println(prefix + "enable-text [group...]");
        pw.println(prefix + "  Enable logcat logging for given groups.");
        pw.println(prefix + "disable-text [group...]");
        pw.println(prefix + "  Disable logcat logging for given groups.");
    }
}
