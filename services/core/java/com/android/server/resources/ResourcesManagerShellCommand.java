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

package com.android.server.resources;

import android.content.res.IResourcesManager;
import android.os.ConditionVariable;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.util.Slog;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * Shell command handler for resources related commands
 */
public class ResourcesManagerShellCommand extends ShellCommand {
    private static final String TAG = "ResourcesManagerShellCommand";

    private final IResourcesManager mInterface;

    public ResourcesManagerShellCommand(IResourcesManager anInterface) {
        mInterface = anInterface;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        final PrintWriter err = getErrPrintWriter();
        try {
            switch (cmd) {
                case "dump":
                    return dumpResources();
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
        } catch (RemoteException e) {
            err.println("Remote exception: " + e);
        }
        return -1;
    }

    private int dumpResources() throws RemoteException {
        String processId = getNextArgRequired();
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.dup(getOutFileDescriptor())) {
            ConditionVariable lock = new ConditionVariable();
            RemoteCallback
                    finishCallback = new RemoteCallback(result -> lock.open(), null);

            if (!mInterface.dumpResources(processId, pfd, finishCallback)) {
                getErrPrintWriter().println("RESOURCES DUMP FAILED on process " + processId);
                return -1;
            }
            lock.block(5000);
            return 0;
        } catch (IOException e) {
            Slog.e(TAG, "Exception while dumping resources", e);
            getErrPrintWriter().println("Exception while dumping resources: " + e.getMessage());
        }
        return -1;
    }

    @Override
    public void onHelp() {
        final PrintWriter out = getOutPrintWriter();
        out.println("Resources manager commands:");
        out.println("  help");
        out.println("    Print this help text.");
        out.println("  dump <PROCESS>");
        out.println("    Dump the Resources objects in use as well as the history of Resources");

    }
}
