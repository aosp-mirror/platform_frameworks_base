/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.commands.telecom;

import android.app.ActivityThread;
import android.content.Context;
import android.os.Looper;
import android.os.ServiceManager;

import com.android.internal.telecom.ITelecomService;
import com.android.server.telecom.TelecomShellCommand;

import java.io.FileDescriptor;

/**
 * @deprecated Use {@code com.android.server.telecom.TelecomShellCommand} instead and execute the
 * shell command using {@code adb shell cmd telecom...}. This is only here for backwards
 * compatibility reasons.
 */
@Deprecated
public final class Telecom {

    /**
     * Command-line entry point.
     *
     * @param args The command-line arguments
     */
    public static void main(String[] args) {
        // Initialize the telephony module.
        // TODO: Do it in zygote and RuntimeInit. b/148897549
        ActivityThread.initializeMainlineModules();

        Looper.prepareMainLooper();
        ITelecomService service = ITelecomService.Stub.asInterface(
                ServiceManager.getService(Context.TELECOM_SERVICE));
        Context context = ActivityThread.systemMain().getSystemContext();
        new TelecomShellCommand(service, context).exec(null, FileDescriptor.in,
                FileDescriptor.out, FileDescriptor.err, args);
    }
}
