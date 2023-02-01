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

package com.android.server.wallpaper;

import android.os.RemoteException;
import android.os.ShellCommand;
import android.util.Log;

import java.io.PrintWriter;

/**
 * Shell Command class to run adb commands on the wallpaper service
 */
public class WallpaperManagerShellCommand extends ShellCommand {
    private static final String TAG = "WallpaperManagerShellCommand";

    private final WallpaperManagerService mService;

    public WallpaperManagerShellCommand(WallpaperManagerService service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            onHelp();
            return 1;
        }
        switch(cmd) {
            case "set-dim-amount":
                return setWallpaperDimAmount();
            case "dim-with-uid":
                return setDimmingWithUid();
            case "get-dim-amount":
                return getWallpaperDimAmount();
            case "-h":
            case "help":
                onHelp();
                return 0;
            default:
                return handleDefaultCommands(cmd);
        }
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Wallpaper manager commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println();
        pw.println("  set-dim-amount DIMMING");
        pw.println("    Sets the current dimming value to DIMMING (a number between 0 and 1).");
        pw.println();
        pw.println("  dim-with-uid UID DIMMING");
        pw.println("    Sets the wallpaper dim amount to DIMMING as if an app with uid, UID, "
                + "called it.");
        pw.println();
        pw.println("  get-dim-amount");
        pw.println("    Get the current wallpaper dim amount.");
    }

    /**
     * Sets the wallpaper dim amount between [0f, 1f] which would be blended with the system default
     * dimming. 0f doesn't add any additional dimming and 1f makes the wallpaper fully black.
     */
    private int setWallpaperDimAmount() {
        float dimAmount = Float.parseFloat(getNextArgRequired());
        try {
            mService.setWallpaperDimAmount(dimAmount);
        } catch (RemoteException e) {
            Log.e(TAG, "Can't set wallpaper dim amount");
        }
        getOutPrintWriter().println("Dimming the wallpaper to: " + dimAmount);
        return 0;
    }

    /**
     * Gets the current additional dim amount set on the wallpaper. 0f means no application has
     * added any dimming on top of the system default dim amount.
     */
    private int getWallpaperDimAmount() {
        float dimAmount = mService.getWallpaperDimAmount();
        getOutPrintWriter().println("The current wallpaper dim amount is: " + dimAmount);
        return 0;
    }

    /**
     * Sets the wallpaper dim amount for an arbitrary UID to simulate multiple applications setting
     * a dim amount on the wallpaper.
     */
    private int setDimmingWithUid() {
        int mockUid = Integer.parseInt(getNextArgRequired());
        float mockDimAmount = Float.parseFloat(getNextArgRequired());
        mService.setWallpaperDimAmountForUid(mockUid, mockDimAmount);
        getOutPrintWriter().println("Dimming the wallpaper for UID: " + mockUid + " to: "
                + mockDimAmount);
        return 0;
    }
}
