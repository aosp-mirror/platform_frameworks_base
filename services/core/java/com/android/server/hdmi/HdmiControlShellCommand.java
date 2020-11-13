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


    HdmiControlShellCommand(IHdmiControlService.Stub binderService) {
        mBinderService = binderService;
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
    }

    private int handleShellCommand(String cmd) throws RemoteException {
        PrintWriter pw = getOutPrintWriter();

        switch (cmd) {
            case "otp":
            case "onetouchplay":
                return oneTouchPlay(pw);
        }

        getErrPrintWriter().println("Unhandled command: " + cmd);
        return 1;
    }

    private int oneTouchPlay(PrintWriter pw) throws RemoteException {
        final CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger cecResult = new AtomicInteger();
        pw.print("Sending One Touch Play...");
        mBinderService.oneTouchPlay(new IHdmiControlCallback.Stub() {
            @Override
            public void onComplete(int result) {
                pw.println(" done (" + result + ")");
                latch.countDown();
                cecResult.set(result);
            }
        });

        try {
            if (!latch.await(HdmiConfig.TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                getErrPrintWriter().println("One Touch Play timed out.");
                return 1;
            }
        } catch (InterruptedException e) {
            getErrPrintWriter().println("Caught InterruptedException");
            Thread.currentThread().interrupt();
        }
        return cecResult.get() == HdmiControlManager.RESULT_SUCCESS ? 0 : 1;
    }
}
