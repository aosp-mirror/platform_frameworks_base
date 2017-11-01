/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.pm;

import android.content.pm.IOtaDexopt;
import android.os.RemoteException;
import android.os.ShellCommand;

import java.io.PrintWriter;
import java.util.Locale;

class OtaDexoptShellCommand extends ShellCommand {
    final IOtaDexopt mInterface;

    OtaDexoptShellCommand(OtaDexoptService service) {
        mInterface = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(null);
        }

        final PrintWriter pw = getOutPrintWriter();
        try {
            switch(cmd) {
                case "prepare":
                    return runOtaPrepare();
                case "cleanup":
                    return runOtaCleanup();
                case "done":
                    return runOtaDone();
                case "step":
                    return runOtaStep();
                case "next":
                    return runOtaNext();
                case "progress":
                    return runOtaProgress();
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
        }
        return -1;
    }

    private int runOtaPrepare() throws RemoteException {
        mInterface.prepare();
        getOutPrintWriter().println("Success");
        return 0;
    }

    private int runOtaCleanup() throws RemoteException {
        mInterface.cleanup();
        return 0;
    }

    private int runOtaDone() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        if (mInterface.isDone()) {
            pw.println("OTA complete.");
        } else {
            pw.println("OTA incomplete.");
        }
        return 0;
    }

    private int runOtaStep() throws RemoteException {
        mInterface.dexoptNextPackage();
        return 0;
    }

    private int runOtaNext() throws RemoteException {
        getOutPrintWriter().println(mInterface.nextDexoptCommand());
        return 0;
    }

    private int runOtaProgress() throws RemoteException {
        final float progress = mInterface.getProgress();
        final PrintWriter pw = getOutPrintWriter();
        // Note: The float output is parsed by update_engine. It does needs to be non-localized,
        //       as it's always expected to be "0.xy," never "0,xy" or similar. So use the ROOT
        //       Locale for formatting. (b/37760573)
        pw.format(Locale.ROOT, "%.2f", progress);
        return 0;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("OTA Dexopt (ota) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("");
        pw.println("  prepare");
        pw.println("    Prepare an OTA dexopt pass, collecting all packages.");
        pw.println("  done");
        pw.println("    Replies whether the OTA is complete or not.");
        pw.println("  step");
        pw.println("    OTA dexopt the next package.");
        pw.println("  next");
        pw.println("    Get parameters for OTA dexopt of the next package.");
        pw.println("  cleanup");
        pw.println("    Clean up internal states. Ends an OTA session.");
    }
}
