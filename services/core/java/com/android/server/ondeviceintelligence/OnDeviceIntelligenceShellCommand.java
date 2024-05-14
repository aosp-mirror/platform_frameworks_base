/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.ondeviceintelligence;

import android.annotation.NonNull;
import android.os.ShellCommand;

import java.io.PrintWriter;
import java.util.Objects;

final class OnDeviceIntelligenceShellCommand extends ShellCommand {
    private static final String TAG = OnDeviceIntelligenceShellCommand.class.getSimpleName();

    @NonNull
    private final OnDeviceIntelligenceManagerService mService;

    OnDeviceIntelligenceShellCommand(@NonNull OnDeviceIntelligenceManagerService service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }

        switch (cmd) {
            case "set-temporary-services":
                return setTemporaryServices();
            case "get-services":
                return getConfiguredServices();
            case "set-model-broadcasts":
                return setBroadcastKeys();
            default:
                return handleDefaultCommands(cmd);
        }
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("OnDeviceIntelligenceShellCommand commands: ");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println();
        pw.println(
                "  set-temporary-services [IntelligenceServiceComponentName] "
                        + "[InferenceServiceComponentName] [DURATION]");
        pw.println("    Temporarily (for DURATION ms) changes the service implementations.");
        pw.println("    To reset, call without any arguments.");

        pw.println("  get-services To get the names of services that are currently being used.");
        pw.println(
                "  set-model-broadcasts [ModelLoadedBroadcastKey] [ModelUnloadedBroadcastKey] "
                        + "[ReceiverPackageName] "
                        + "[DURATION] To set the names of broadcast intent keys that are to be "
                        + "emitted for cts tests.");
    }

    private int setTemporaryServices() {
        final PrintWriter out = getOutPrintWriter();
        final String intelligenceServiceName = getNextArg();
        final String inferenceServiceName = getNextArg();

        if (getRemainingArgsCount() == 0 && intelligenceServiceName == null
                && inferenceServiceName == null) {
            mService.resetTemporaryServices();
            out.println("OnDeviceIntelligenceManagerService temporary reset. ");
            return 0;
        }

        Objects.requireNonNull(intelligenceServiceName);
        Objects.requireNonNull(inferenceServiceName);
        final int duration = Integer.parseInt(getNextArgRequired());
        mService.setTemporaryServices(
                new String[]{intelligenceServiceName, inferenceServiceName},
                duration);
        out.println("OnDeviceIntelligenceService temporarily set to " + intelligenceServiceName
                + " \n and \n OnDeviceTrustedInferenceService set to " + inferenceServiceName
                + " for " + duration + "ms");
        return 0;
    }

    private int getConfiguredServices() {
        final PrintWriter out = getOutPrintWriter();
        String[] services = mService.getServiceNames();
        out.println("OnDeviceIntelligenceService set to :  " + services[0]
                + " \n and \n OnDeviceTrustedInferenceService set to : " + services[1]);
        return 0;
    }

    private int setBroadcastKeys() {
        final PrintWriter out = getOutPrintWriter();
        final String modelLoadedKey = getNextArgRequired();
        final String modelUnloadedKey = getNextArgRequired();
        final String receiverPackageName = getNextArg();

        final int duration = Integer.parseInt(getNextArgRequired());
        mService.setModelBroadcastKeys(
                new String[]{modelLoadedKey, modelUnloadedKey}, receiverPackageName, duration);
        out.println("OnDeviceIntelligence Model Loading broadcast keys temporarily set to "
                + modelLoadedKey
                + " \n and \n OnDeviceTrustedInferenceService set to " + modelUnloadedKey
                + "\n and Package name set to : " + receiverPackageName
                + " for " + duration + "ms");
        return 0;
    }

}