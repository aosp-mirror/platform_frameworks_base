/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.net.watchlist;

import android.content.Context;
import android.content.Intent;
import android.net.NetworkWatchlistManager;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ShellCommand;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;

/**
 * Network watchlist shell commands class, to provide a way to set temporary watchlist config for
 * testing in shell, so CTS / GTS can use it to verify if watchlist feature is working properly.
 */
class NetworkWatchlistShellCommand extends ShellCommand {

    final NetworkWatchlistManager mNetworkWatchlistManager;

    NetworkWatchlistShellCommand(Context context) {
        mNetworkWatchlistManager = new NetworkWatchlistManager(context);
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }

        final PrintWriter pw = getOutPrintWriter();
        try {
            switch(cmd) {
                case "set-test-config":
                    return runSetTestConfig();
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
        }
        return -1;
    }

    /**
     * Method to get fd from input xml path, and set it as temporary watchlist config.
     */
    private int runSetTestConfig() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        try {
            final String configXmlPath = getNextArgRequired();
            final ParcelFileDescriptor pfd = openFileForSystem(configXmlPath, "r");
            if (pfd != null) {
                final InputStream fileStream = new FileInputStream(pfd.getFileDescriptor());
                WatchlistConfig.getInstance().setTestMode(fileStream);
            }
            pw.println("Success!");
        } catch (RuntimeException | IOException ex) {
            pw.println("Error: " + ex.toString());
            return -1;
        }
        return 0;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Network watchlist manager commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("");
        pw.println("  set-test-config your_watchlist_config.xml");
        pw.println();
        Intent.printIntentArgsHelp(pw , "");
    }
}
