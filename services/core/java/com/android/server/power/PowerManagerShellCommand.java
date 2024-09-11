/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.power;

import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.PowerManagerInternal;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.util.SparseArray;
import android.view.Display;

import java.io.PrintWriter;
import java.util.List;

class PowerManagerShellCommand extends ShellCommand {
    private static final int LOW_POWER_MODE_ON = 1;

    private final Context mContext;
    private final PowerManagerService.BinderService mService;

    private SparseArray<WakeLock> mProxWakelocks = new SparseArray<>();

    PowerManagerShellCommand(Context context, PowerManagerService.BinderService service) {
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
                case "set-adaptive-power-saver-enabled":
                    return runSetAdaptiveEnabled();
                case "set-mode":
                    return runSetMode();
                case "set-fixed-performance-mode-enabled":
                    return runSetFixedPerformanceModeEnabled();
                case "suppress-ambient-display":
                    return runSuppressAmbientDisplay();
                case "list-ambient-display-suppression-tokens":
                    return runListAmbientDisplaySuppressionTokens();
                case "set-prox":
                    return runSetProx();
                case "set-face-down-detector":
                    return runSetFaceDownDetector();
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
        }
        return -1;
    }

    private int runSetAdaptiveEnabled() throws RemoteException {
        mService.setAdaptivePowerSaveEnabled(Boolean.parseBoolean(getNextArgRequired()));
        return 0;
    }

    private int runSetMode() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        int mode = -1;
        try {
            mode = Integer.parseInt(getNextArgRequired());
        } catch (RuntimeException ex) {
            pw.println("Error: " + ex.toString());
            return -1;
        }
        mService.setPowerSaveModeEnabled(mode == LOW_POWER_MODE_ON);
        return 0;
    }

    private int runSetFixedPerformanceModeEnabled() throws RemoteException {
        boolean success = mService.setPowerModeChecked(
                PowerManagerInternal.MODE_FIXED_PERFORMANCE,
                Boolean.parseBoolean(getNextArgRequired()));
        if (!success) {
            final PrintWriter ew = getErrPrintWriter();
            ew.println("Failed to set FIXED_PERFORMANCE mode");
            ew.println("This is likely because Power HAL AIDL is not implemented on this device");
        }
        return success ? 0 : -1;
    }

    private int runSuppressAmbientDisplay() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();

        try {
            String token = getNextArgRequired();
            boolean enabled = Boolean.parseBoolean(getNextArgRequired());
            mService.suppressAmbientDisplay(token, enabled);
        } catch (RuntimeException ex) {
            pw.println("Error: " + ex.toString());
            return -1;
        }

        return 0;
    }

    private int runListAmbientDisplaySuppressionTokens() throws RemoteException {
        final PrintWriter pw = getOutPrintWriter();
        List<String> tokens = mService.getAmbientDisplaySuppressionTokens();
        if (tokens.isEmpty()) {
            pw.println("none");
        } else {
            pw.println(String.format("[%s]", String.join(", ", tokens)));
        }

        return 0;
    }

    /** TODO: Consider updating this code to support all wakelock types. */
    private int runSetProx() throws RemoteException {
        PrintWriter pw = getOutPrintWriter();
        final boolean acquire;
        switch (getNextArgRequired().toLowerCase()) {
            case "list":
                pw.println("Wakelocks:");
                pw.println(mProxWakelocks);
                return 0;
            case "acquire":
                acquire = true;
                break;
            case "release":
                acquire = false;
                break;
            default:
                pw.println("Error: Allowed options are 'list' 'enable' and 'disable'.");
                return -1;
        }

        int displayId = Display.INVALID_DISPLAY;
        String displayOption = getNextArg();
        if ("-d".equals(displayOption)) {
            String idStr = getNextArg();
            displayId = Integer.parseInt(idStr);
            if (displayId < 0) {
                pw.println("Error: Specified displayId (" + idStr + ") must a non-negative int.");
                return -1;
            }
        }

        int wakelockIndex = displayId + 1; // SparseArray doesn't support negative indexes
        WakeLock wakelock = mProxWakelocks.get(wakelockIndex);
        if (wakelock == null) {
            PowerManager pm = mContext.getSystemService(PowerManager.class);
            wakelock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                        "PowerManagerShellCommand[" + displayId + "]", displayId);
            mProxWakelocks.put(wakelockIndex, wakelock);
        }

        if (acquire) {
            wakelock.acquire();
        } else {
            wakelock.release();
        }
        pw.println(wakelock);
        return 0;
    }

    /**
     * To be used for testing - allowing us to disable the usage of face down detector.
     */
    private int runSetFaceDownDetector() {
        try {
            mService.setUseFaceDownDetector(Boolean.parseBoolean(getNextArgRequired()));
        } catch (Exception e) {
            PrintWriter pw = getOutPrintWriter();
            pw.println("Error: " + e);
            return -1;
        }
        return 0;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Power manager (power) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("");
        pw.println("  set-adaptive-power-saver-enabled [true|false]");
        pw.println("    enables or disables adaptive power saver.");
        pw.println("  set-mode MODE");
        pw.println("    sets the power mode of the device to MODE.");
        pw.println("    1 turns low power mode on and 0 turns low power mode off.");
        pw.println("  set-fixed-performance-mode-enabled [true|false]");
        pw.println("    enables or disables fixed performance mode");
        pw.println("    note: this will affect system performance and should only be used");
        pw.println("          during development");
        pw.println("  suppress-ambient-display <token> [true|false]");
        pw.println("    suppresses the current ambient display configuration and disables");
        pw.println("    ambient display");
        pw.println("  list-ambient-display-suppression-tokens");
        pw.println("    prints the tokens used to suppress ambient display");
        pw.println("  set-prox [list|acquire|release] (-d <display_id>)");
        pw.println("    Acquires the proximity sensor wakelock. Wakelock is associated with");
        pw.println("    a specific display if specified. 'list' lists wakelocks previously");
        pw.println("    created by set-prox including their held status.");
        pw.println("  set-face-down-detector [true|false]");
        pw.println("    sets whether we use face down detector timeouts or not");

        pw.println();
        Intent.printIntentArgsHelp(pw , "");
    }
}
