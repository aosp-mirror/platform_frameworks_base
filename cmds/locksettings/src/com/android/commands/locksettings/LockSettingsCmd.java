/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.commands.locksettings;

import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;

import com.android.internal.os.BaseCommand;
import com.android.internal.widget.ILockSettings;

import java.io.FileDescriptor;
import java.io.PrintStream;

public final class LockSettingsCmd extends BaseCommand {

    private static final String USAGE =
            "usage: locksettings set-pattern [--old OLD_CREDENTIAL] NEW_PATTERN\n" +
            "       locksettings set-pin [--old OLD_CREDENTIAL] NEW_PIN\n" +
            "       locksettings set-password [--old OLD_CREDENTIAL] NEW_PASSWORD\n" +
            "       locksettings clear [--old OLD_CREDENTIAL]\n" +
            "       locksettings verify [--old OLD_CREDENTIAL]\n" +
            "       locksettings set-disabled DISABLED\n" +
            "       locksettings get-disabled\n" +
            "\n" +
            "flags: \n" +
            "       --user USER_ID: specify the user, default value is current user\n" +
            "\n" +
            "locksettings set-pattern: sets a pattern\n" +
            "    A pattern is specified by a non-separated list of numbers that index the cell\n" +
            "    on the pattern in a 1-based manner in left to right and top to bottom order,\n" +
            "    i.e. the top-left cell is indexed with 1, whereas the bottom-right cell\n" +
            "    is indexed with 9. Example: 1234\n" +
            "\n" +
            "locksettings set-pin: sets a PIN\n" +
            "\n" +
            "locksettings set-password: sets a password\n" +
            "\n" +
            "locksettings clear: clears the unlock credential\n" +
            "\n" +
            "locksettings verify: verifies the credential and unlocks the user\n" +
            "\n" +
            "locksettings set-disabled: sets whether the lock screen should be disabled\n" +
            "\n" +
            "locksettings get-disabled: retrieves whether the lock screen is disabled\n";

    public static void main(String[] args) {
        (new LockSettingsCmd()).run(args);
    }

    @Override
    public void onShowUsage(PrintStream out) {
        out.println(USAGE);
    }

    @Override
    public void onRun() throws Exception {
        ILockSettings lockSettings = ILockSettings.Stub.asInterface(
                ServiceManager.getService("lock_settings"));
        lockSettings.asBinder().shellCommand(FileDescriptor.in, FileDescriptor.out,
                FileDescriptor.err, getRawArgs(), new ShellCallback(), new ResultReceiver(null) {});
    }
}
