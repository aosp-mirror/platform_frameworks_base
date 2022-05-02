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

    public static void main(String[] args) {
        (new LockSettingsCmd()).run(args);
    }

    @Override
    public void onShowUsage(PrintStream out) {
        main(new String[] { "help" });
    }

    @Override
    public void onRun() throws Exception {
        ILockSettings lockSettings = ILockSettings.Stub.asInterface(
                ServiceManager.getService("lock_settings"));
        lockSettings.asBinder().shellCommand(FileDescriptor.in, FileDescriptor.out,
                FileDescriptor.err, getRawArgs(), new ShellCallback(), new ResultReceiver(null) {});
    }
}
