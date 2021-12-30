/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.companion;

import static com.android.server.companion.CompanionDeviceManagerService.LOG_TAG;

import android.companion.AssociationInfo;
import android.util.Log;
import android.util.Slog;

import java.io.PrintWriter;
import java.util.List;

class CompanionDeviceShellCommand extends android.os.ShellCommand {
    private final CompanionDeviceManagerService mService;
    private final AssociationStore mAssociationStore;

    CompanionDeviceShellCommand(CompanionDeviceManagerService service,
            AssociationStore associationStore) {
        mService = service;
        mAssociationStore = associationStore;
    }

    @Override
    public int onCommand(String cmd) {
        final PrintWriter out = getOutPrintWriter();
        try {
            switch (cmd) {
                case "list": {
                    final int userId = getNextArgInt();
                    final List<AssociationInfo> associationsForUser =
                            mAssociationStore.getAssociationsForUser(userId);
                    for (AssociationInfo association : associationsForUser) {
                        // TODO(b/212535524): use AssociationInfo.toShortString(), once it's not
                        //  longer referenced in tests.
                        out.println(association.getPackageName() + " "
                                + association.getDeviceMacAddress());
                    }
                }
                break;

                case "associate": {
                    int userId = getNextArgInt();
                    String packageName = getNextArgRequired();
                    String address = getNextArgRequired();
                    mService.legacyCreateAssociation(userId, address, packageName, null);
                }
                break;

                case "disassociate": {
                    final int userId = getNextArgInt();
                    final String packageName = getNextArgRequired();
                    final String address = getNextArgRequired();
                    final AssociationInfo association =
                            mService.getAssociationWithCallerChecks(userId, packageName, address);
                    if (association != null) {
                        mService.disassociateInternal(association.getId());
                    }
                }
                break;

                case "simulate_connect": {
                    mService.onDeviceConnected(getNextArgRequired());
                }
                break;

                case "simulate_disconnect": {
                    mService.onDeviceDisconnected(getNextArgRequired());
                }
                break;

                default:
                    return handleDefaultCommands(cmd);
            }
            return 0;
        } catch (Throwable t) {
            Slog.e(LOG_TAG, "Error running a command: $ " + cmd, t);
            getErrPrintWriter().println(Log.getStackTraceString(t));
            return 1;
        }
    }

    private int getNextArgInt() {
        return Integer.parseInt(getNextArgRequired());
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Companion Device Manager (companiondevice) commands:");
        pw.println("  help");
        pw.println("      Print this help text.");
        pw.println("  list USER_ID");
        pw.println("      List all Associations for a user.");
        pw.println("  associate USER_ID PACKAGE MAC_ADDRESS");
        pw.println("      Create a new Association.");
        pw.println("  disassociate USER_ID PACKAGE MAC_ADDRESS");
        pw.println("      Remove an existing Association.");
    }
}
