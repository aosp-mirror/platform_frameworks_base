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

package com.android.server.audio;

import android.content.Context;
import android.media.AudioManager;
import android.os.ShellCommand;

import java.io.PrintWriter;


class AudioManagerShellCommand extends ShellCommand {
    private static final String TAG = "AudioManagerShellCommand";

    private final AudioService mService;

    AudioManagerShellCommand(AudioService service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        final PrintWriter pw = getOutPrintWriter();
        switch(cmd) {
            case "set-surround-format-enabled":
                return setSurroundFormatEnabled();
            case "get-is-surround-format-enabled":
                return getIsSurroundFormatEnabled();
            case "set-encoded-surround-mode":
                return setEncodedSurroundMode();
            case "get-encoded-surround-mode":
                return getEncodedSurroundMode();
        }
        return 0;
    }

    @Override
    public void onHelp() {
        final PrintWriter pw = getOutPrintWriter();
        pw.println("Audio manager commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println();
        pw.println("  set-surround-format-enabled SURROUND_FORMAT IS_ENABLED");
        pw.println("    Enables/disabled the SURROUND_FORMAT based on IS_ENABLED");
        pw.println("  get-is-surround-format-enabled SURROUND_FORMAT");
        pw.println("    Returns if the SURROUND_FORMAT is enabled");
        pw.println("  set-encoded-surround-mode SURROUND_SOUND_MODE");
        pw.println("    Sets the encoded surround sound mode to SURROUND_SOUND_MODE");
        pw.println("  get-encoded-surround-mode");
        pw.println("    Returns the encoded surround sound mode");
    }

    private int setSurroundFormatEnabled() {
        String surroundFormatText = getNextArg();
        String isSurroundFormatEnabledText = getNextArg();

        if (surroundFormatText == null) {
            getErrPrintWriter().println("Error: no surroundFormat specified");
            return 1;
        }

        if (isSurroundFormatEnabledText == null) {
            getErrPrintWriter().println("Error: no enabled value for surroundFormat specified");
            return 1;
        }

        int surroundFormat = -1;
        boolean isSurroundFormatEnabled = false;
        try {
            surroundFormat = Integer.parseInt(surroundFormatText);
            isSurroundFormatEnabled = Boolean.parseBoolean(isSurroundFormatEnabledText);
        } catch (NumberFormatException e) {
            getErrPrintWriter().println("Error: wrong format specified for surroundFormat");
            return 1;
        }
        if (surroundFormat < 0) {
            getErrPrintWriter().println("Error: invalid value of surroundFormat");
            return 1;
        }

        final Context context = mService.mContext;
        final AudioManager am = context.getSystemService(AudioManager.class);
        am.setSurroundFormatEnabled(surroundFormat, isSurroundFormatEnabled);
        return 0;
    }

    private int getIsSurroundFormatEnabled() {
        String surroundFormatText = getNextArg();

        if (surroundFormatText == null) {
            getErrPrintWriter().println("Error: no surroundFormat specified");
            return 1;
        }

        int surroundFormat = -1;
        try {
            surroundFormat = Integer.parseInt(surroundFormatText);
        } catch (NumberFormatException e) {
            getErrPrintWriter().println("Error: wrong format specified for surroundFormat");
            return 1;
        }

        if (surroundFormat < 0) {
            getErrPrintWriter().println("Error: invalid value of surroundFormat");
            return 1;
        }
        final Context context = mService.mContext;
        final AudioManager am = context.getSystemService(AudioManager.class);
        getOutPrintWriter().println("Value of enabled for " + surroundFormat + " is: "
                + am.isSurroundFormatEnabled(surroundFormat));
        return 0;
    }

    private int setEncodedSurroundMode() {
        String encodedSurroundModeText = getNextArg();

        if (encodedSurroundModeText == null) {
            getErrPrintWriter().println("Error: no encodedSurroundMode specified");
            return 1;
        }

        int encodedSurroundMode = -1;
        try {
            encodedSurroundMode = Integer.parseInt(encodedSurroundModeText);
        } catch (NumberFormatException e) {
            getErrPrintWriter().println("Error: wrong format specified for encoded surround mode");
            return 1;
        }

        if (encodedSurroundMode < 0) {
            getErrPrintWriter().println("Error: invalid value of encodedSurroundMode");
            return 1;
        }

        final Context context = mService.mContext;
        final AudioManager am = context.getSystemService(AudioManager.class);
        am.setEncodedSurroundMode(encodedSurroundMode);
        return 0;
    }

    private int getEncodedSurroundMode() {
        final Context context = mService.mContext;
        final AudioManager am = context.getSystemService(AudioManager.class);
        getOutPrintWriter().println("Encoded surround mode: " + am.getEncodedSurroundMode());
        return 0;
    }
}
