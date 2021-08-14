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
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.provider.Settings;

import java.io.InputStream;
import java.io.PrintWriter;

/**
 * Network watchlist shell commands class, to provide a way to set temporary watchlist config for
 * testing in shell, so CTS / GTS can use it to verify if watchlist feature is working properly.
 */
class NetworkWatchlistShellCommand extends ShellCommand {

    final Context mContext;
    final NetworkWatchlistService mService;

    NetworkWatchlistShellCommand(NetworkWatchlistService service, Context context) {
        mContext = context;
        mService = service;
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
                case "force-generate-report":
                    return runForceGenerateReport();
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (Exception e) {
            pw.println("Exception: " + e);
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
            if (pfd == null) {
                pw.println("Error: can't open input file " + configXmlPath);
                return -1;
            }
            try (InputStream inputStream = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                WatchlistConfig.getInstance().setTestMode(inputStream);
            }
            pw.println("Success!");
        } catch (Exception ex) {
            pw.println("Error: " + ex.toString());
            return -1;
        }
        return 0;
    }

    private int runForceGenerateReport() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final long ident = Binder.clearCallingIdentity();
        try {
            // Reset last report time
            if (WatchlistConfig.getInstance().isConfigSecure()) {
                pw.println("Error: Cannot force generate report under production config");
                return -1;
            }
            Settings.Global.putLong(mContext.getContentResolver(),
                    Settings.Global.NETWORK_WATCHLIST_LAST_REPORT_TIME, 0L);
            mService.forceReportWatchlistForTest(System.currentTimeMillis());
            pw.println("Success!");
        } catch (Exception ex) {
            pw.println("Error: " + ex);
            return -1;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return 0;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Network watchlist manager commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  set-test-config your_watchlist_config.xml");
        pw.println("    Set network watchlist test config file.");
        pw.println("  force-generate-report");
        pw.println("    Force generate watchlist test report.");
    }
}
