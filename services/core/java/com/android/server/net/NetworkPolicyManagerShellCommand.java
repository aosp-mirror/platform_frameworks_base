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
 * limitations under the License.
 */

package com.android.server.net;

import java.io.PrintWriter;

import android.content.Intent;
import android.net.INetworkPolicyManager;
import android.os.RemoteException;
import android.os.ShellCommand;

public class NetworkPolicyManagerShellCommand extends ShellCommand {

    final INetworkPolicyManager mInterface;

    NetworkPolicyManagerShellCommand(NetworkPolicyManagerService service) {
        mInterface = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        final PrintWriter pw = getOutPrintWriter();
        try {
            switch(cmd) {
                case "get":
                    return runGet();
                case "set":
                    return runSet();
                case "list":
                    return runList();
                case "add":
                    return runAdd();
                case "remove":
                    return runRemove();
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
        }
        return -1;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Network policy manager (netpolicy) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("");
        pw.println("  get restrict-background");
        pw.println("    Gets the global restrict background usage status.");
        pw.println("  set restrict-background BOOLEAN");
        pw.println("    Sets the global restrict background usage status.");
        pw.println("  list restrict-background-whitelist");
        pw.println("    Prints UID that are whitelisted for restrict background usage.");
        pw.println("  add restrict-background-whitelist UID");
        pw.println("    Adds a UID to the whitelist for restrict background usage.");
        pw.println("  remove restrict-background-whitelist UID");
        pw.println("    Removes a UID from the whitelist for restrict background usage.");
    }

    private int runGet() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final String type = getNextArg();
        if (type == null) {
            pw.println("Error: didn't specify type of data to get");
            return -1;
        }
        switch(type) {
            case "restrict-background":
                return getRestrictBackgroundWhitelist();
        }
        pw.println("Error: unknown get type '" + type + "'");
        return -1;
    }

    private int runSet() throws RemoteException  {
        final PrintWriter pw = getOutPrintWriter();
        final String type = getNextArg();
        if (type == null) {
            pw.println("Error: didn't specify type of data to set");
            return -1;
        }
        switch(type) {
            case "restrict-background":
                return setRestrictBackgroundWhitelist();
        }
        pw.println("Error: unknown set type '" + type + "'");
        return -1;
    }

    private int runList() throws RemoteException  {
        final PrintWriter pw = getOutPrintWriter();
        final String type = getNextArg();
        if (type == null) {
            pw.println("Error: didn't specify type of data to list");
            return -1;
        }
        switch(type) {
            case "restrict-background-whitelist":
                return runListRestrictBackgroundWhitelist();
        }
        pw.println("Error: unknown list type '" + type + "'");
        return -1;
    }

    private int runAdd() throws RemoteException  {
        final PrintWriter pw = getOutPrintWriter();
        final String type = getNextArg();
        if (type == null) {
            pw.println("Error: didn't specify type of data to add");
            return -1;
        }
        switch(type) {
            case "restrict-background-whitelist":
                return addRestrictBackgroundWhitelist();
        }
        pw.println("Error: unknown add type '" + type + "'");
        return -1;
    }

    private int runRemove() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final String type = getNextArg();
        if (type == null) {
            pw.println("Error: didn't specify type of data to remove");
            return -1;
        }
        switch(type) {
            case "restrict-background-whitelist":
                return removeRestrictBackgroundWhitelist();
        }
        pw.println("Error: unknown remove type '" + type + "'");
        return -1;
    }

    private int runListRestrictBackgroundWhitelist() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        final int[] uids = mInterface.getRestrictBackgroundWhitelistedUids();
        pw.print("Restrict background whitelisted UIDs: ");
        if (uids.length == 0) {
            pw.println("none");
        } else {
            for (int i = 0; i < uids.length; i++) {
                int uid = uids[i];
                pw.print(uid);
                pw.print(' ');
            }
        }
        pw.println();
        return 0;
    }

    private int getRestrictBackgroundWhitelist() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        pw.print("Restrict background status: ");
        pw.println(mInterface.getRestrictBackground() ? "enabled" : "disabled");
        return 0;
    }

    private int setRestrictBackgroundWhitelist() throws RemoteException {
        final int enabled = getNextBooleanArg();
        if (enabled < 0) {
            return enabled;
        }
        mInterface.setRestrictBackground(enabled > 0);
        return 0;
    }

    private int addRestrictBackgroundWhitelist() throws RemoteException {
      final int uid = getUidFromNextArg();
      if (uid < 0) {
          return uid;
      }
      mInterface.addRestrictBackgroundWhitelistedUid(uid);
      return 0;
    }

    private int removeRestrictBackgroundWhitelist() throws RemoteException {
        final int uid = getUidFromNextArg();
        if (uid < 0) {
            return uid;
        }
        mInterface.removeRestrictBackgroundWhitelistedUid(uid);
        return 0;
    }

    private int getNextBooleanArg() {
        final PrintWriter pw = getOutPrintWriter();
        final String arg = getNextArg();
        if (arg == null) {
            pw.println("Error: didn't specify BOOLEAN");
            return -1;
        }
        return Boolean.valueOf(arg) ? 1 : 0;
    }

    private int getUidFromNextArg() {
        final PrintWriter pw = getOutPrintWriter();
        final String arg = getNextArg();
        if (arg == null) {
            pw.println("Error: didn't specify UID");
            return -1;
        }
        try {
            return Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            pw.println("Error: UID (" + arg + ") should be a number");
            return -2;
        }
    }
}
