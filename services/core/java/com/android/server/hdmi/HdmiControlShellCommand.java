/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.hdmi;


import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.IHdmiControlCallback;
import android.hardware.hdmi.IHdmiControlService;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.util.Slog;

import java.io.PrintWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class HdmiControlShellCommand extends ShellCommand {

    private static final String TAG = "HdmiShellCommand";

    private final IHdmiControlService.Stub mBinderService;

    final CountDownLatch mLatch;
    AtomicInteger mCecResult;
    IHdmiControlCallback.Stub mHdmiControlCallback;

    HdmiControlShellCommand(IHdmiControlService.Stub binderService) {
        mBinderService = binderService;
        mLatch = new CountDownLatch(1);
        mCecResult = new AtomicInteger();
        mHdmiControlCallback =
                new IHdmiControlCallback.Stub() {
                    @Override
                    public void onComplete(int result) {
                        getOutPrintWriter().println(" done (" + getResultString(result) + ")");
                        mCecResult.set(result);
                        mLatch.countDown();
                    }
                };
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }

        try {
            return handleShellCommand(cmd);
        } catch (Exception e) {
            getErrPrintWriter().println(
                    "Caught error for command '" + cmd + "': " + e.getMessage());
            Slog.e(TAG, "Error handling hdmi_control shell command: " + cmd, e);
            return 1;
        }
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();

        pw.println("HdmiControlManager (hdmi_control) commands:");
        pw.println("  help");
        pw.println("      Print this help text.");
        pw.println("  onetouchplay, otp");
        pw.println("      Send the \"One Touch Play\" feature from a source to the TV");
        pw.println("  vendorcommand --device_type <originating device type>");
        pw.println("                --destination <destination device>");
        pw.println("                --args <vendor specific arguments>");
        pw.println("                [--id <true if vendor command should be sent with vendor id>]");
        pw.println("      Send a Vendor Command to the given target device");
        pw.println("  cec_setting get <setting name>");
        pw.println("      Get the current value of a CEC setting");
        pw.println("  cec_setting set <setting name> <value>");
        pw.println("      Set the value of a CEC setting");
        pw.println("  setsystemaudiomode, setsam [on|off]");
        pw.println("      Sets the System Audio Mode feature on or off on TV devices");
        pw.println("  setarc [on|off]");
        pw.println("      Sets the ARC feature on or off on TV devices");
    }

    private int handleShellCommand(String cmd) throws RemoteException {
        PrintWriter pw = getOutPrintWriter();

        switch (cmd) {
            case "otp":
            case "onetouchplay":
                return oneTouchPlay(pw);
            case "vendorcommand":
                return vendorCommand(pw);
            case "cec_setting":
                return cecSetting(pw);
            case "setsystemaudiomode":
            case "setsam":
                return setSystemAudioMode(pw);
            case "setarc":
                return setArcMode(pw);
        }

        getErrPrintWriter().println("Unhandled command: " + cmd);
        return 1;
    }

    private int oneTouchPlay(PrintWriter pw) throws RemoteException {
        pw.print("Sending One Touch Play...");
        mBinderService.oneTouchPlay(mHdmiControlCallback);

        if (!receiveCallback("One Touch Play")) {
            return 1;
        }

        return mCecResult.get() == HdmiControlManager.RESULT_SUCCESS ? 0 : 1;
    }

    private int vendorCommand(PrintWriter pw) throws RemoteException {
        if (6 > getRemainingArgsCount()) {
            throw new IllegalArgumentException("Expected 3 arguments.");
        }

        int deviceType = -1;
        int destination = -1;
        String parameters = "";
        boolean hasVendorId = false;

        String arg = getNextOption();
        while (arg != null) {
            switch (arg) {
                case "-t":
                case "--device_type":
                    deviceType = Integer.parseInt(getNextArgRequired());
                    break;
                case "-d":
                case "--destination":
                    destination = Integer.parseInt(getNextArgRequired());
                    break;
                case "-a":
                case "--args":
                    parameters = getNextArgRequired();
                    break;
                case "-i":
                case "--id":
                    hasVendorId = Boolean.parseBoolean(getNextArgRequired());
                    break;
                default:
                    throw new IllegalArgumentException("Unknown argument: " + arg);
            }
            arg = getNextArg();
        }

        String[] parts = parameters.split(":");
        byte[] params = new byte[parts.length];
        for (int i = 0; i < params.length; i++) {
            params[i] = (byte) Integer.parseInt(parts[i], 16);
        }

        pw.println("Sending <Vendor Command>");
        mBinderService.sendVendorCommand(deviceType, destination, params, hasVendorId);
        return 0;
    }

    private int cecSetting(PrintWriter pw) throws RemoteException {
        if (getRemainingArgsCount() < 1) {
            throw new IllegalArgumentException("Expected at least 1 argument (operation).");
        }
        String operation = getNextArgRequired();
        switch (operation) {
            case "get": {
                String setting = getNextArgRequired();
                try {
                    String value = mBinderService.getCecSettingStringValue(setting);
                    pw.println(setting + " = " + value);
                } catch (IllegalArgumentException e) {
                    int intValue = mBinderService.getCecSettingIntValue(setting);
                    pw.println(setting + " = " + intValue);
                }
                return 0;
            }
            case "set": {
                String setting = getNextArgRequired();
                String value = getNextArgRequired();
                try {
                    mBinderService.setCecSettingStringValue(setting, value);
                    pw.println(setting + " = " + value);
                } catch (IllegalArgumentException e) {
                    int intValue = Integer.parseInt(value);
                    mBinderService.setCecSettingIntValue(setting, intValue);
                    pw.println(setting + " = " + intValue);
                }
                return 0;
            }
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    private int setSystemAudioMode(PrintWriter pw) throws RemoteException {
        if (1 > getRemainingArgsCount()) {
            throw new IllegalArgumentException(
                    "Please indicate if System Audio Mode should be turned \"on\" or \"off\".");
        }

        String arg = getNextArg();
        if (arg.equals("on")) {
            pw.println("Setting System Audio Mode on");
            mBinderService.setSystemAudioMode(true, mHdmiControlCallback);
        } else if (arg.equals("off")) {
            pw.println("Setting System Audio Mode off");
            mBinderService.setSystemAudioMode(false, mHdmiControlCallback);
        } else {
            throw new IllegalArgumentException(
                    "Please indicate if System Audio Mode should be turned \"on\" or \"off\".");
        }

        if (!receiveCallback("Set System Audio Mode")) {
            return 1;
        }

        return mCecResult.get() == HdmiControlManager.RESULT_SUCCESS ? 0 : 1;
    }

    private int setArcMode(PrintWriter pw) throws RemoteException {
        if (1 > getRemainingArgsCount()) {
            throw new IllegalArgumentException(
                    "Please indicate if ARC mode should be turned \"on\" or \"off\".");
        }

        String arg = getNextArg();
        if (arg.equals("on")) {
            pw.println("Setting ARC mode on");
            mBinderService.setArcMode(true);
        } else if (arg.equals("off")) {
            pw.println("Setting ARC mode off");
            mBinderService.setArcMode(false);
        } else {
            throw new IllegalArgumentException(
                    "Please indicate if ARC mode should be turned \"on\" or \"off\".");
        }

        return 0;
    }

    private boolean receiveCallback(String command) {
        try {
            if (!mLatch.await(HdmiConfig.TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                getErrPrintWriter().println(command + " timed out.");
                return false;
            }
        } catch (InterruptedException e) {
            getErrPrintWriter().println("Caught InterruptedException");
            Thread.currentThread().interrupt();
        }
        return true;
    }

    private String getResultString(int result) {
        switch (result) {
            case HdmiControlManager.RESULT_SUCCESS:
                return "Success";
            case HdmiControlManager.RESULT_TIMEOUT:
                return "Timeout";
            case HdmiControlManager.RESULT_SOURCE_NOT_AVAILABLE:
                return "Source not available";
            case HdmiControlManager.RESULT_TARGET_NOT_AVAILABLE:
                return "Target not available";
            case HdmiControlManager.RESULT_EXCEPTION:
                return "Exception";
            case HdmiControlManager.RESULT_INCORRECT_MODE:
                return "Incorrect mode";
            case HdmiControlManager.RESULT_COMMUNICATION_FAILED:
                return "Communication Failed";
        }
        return Integer.toString(result);
    }
}
