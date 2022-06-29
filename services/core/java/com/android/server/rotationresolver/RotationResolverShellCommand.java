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

package com.android.server.rotationresolver;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.os.ShellCommand;
import android.rotationresolver.RotationResolverInternal.RotationResolverCallbackInternal;
import android.service.rotationresolver.RotationResolutionRequest;
import android.view.Surface;

import java.io.PrintWriter;

final class RotationResolverShellCommand extends ShellCommand {
    private static final int INITIAL_RESULT_CODE = -1;

    @NonNull
    private final RotationResolverManagerService mService;

    RotationResolverShellCommand(@NonNull RotationResolverManagerService service) {
        mService = service;
    }

    static class TestableRotationCallbackInternal implements RotationResolverCallbackInternal {

        private int mLastCallbackResultCode = INITIAL_RESULT_CODE;

        @Override
        public void onSuccess(int result) {
            mLastCallbackResultCode = result;
        }

        @Override
        public void onFailure(int error) {
            mLastCallbackResultCode = error;
        }

        public void reset() {
            mLastCallbackResultCode = INITIAL_RESULT_CODE;
        }

        public int getLastCallbackCode() {
            return mLastCallbackResultCode;
        }
    }

    static final TestableRotationCallbackInternal sTestableRotationCallbackInternal =
            new TestableRotationCallbackInternal();

    @Override
    public int onCommand(@Nullable String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }

        switch (cmd) {
            case "resolve-rotation":
                return runResolveRotation();
            case "get-last-resolution":
                return getLastResolution();
            case "get-bound-package":
                return getBoundPackageName();
            case "set-temporary-service":
                return setTemporaryService();
            default:
                return handleDefaultCommands(cmd);
        }
    }

    private int getBoundPackageName() {
        final PrintWriter out = getOutPrintWriter();
        final int userId = Integer.parseInt(getNextArgRequired());
        final ComponentName componentName = mService.getComponentNameShellCommand(userId);
        out.println(componentName == null ? "" : componentName.getPackageName());
        return 0;
    }

    private int setTemporaryService() {
        final PrintWriter out = getOutPrintWriter();
        final int userId = Integer.parseInt(getNextArgRequired());
        final String serviceName = getNextArg();
        if (serviceName == null) {
            mService.resetTemporaryService(userId);
            out.println("RotationResolverService temporary reset. ");
            return 0;
        }
        final int duration = Integer.parseInt(getNextArgRequired());
        mService.setTemporaryService(userId, serviceName, duration);
        out.println("RotationResolverService temporarily set to " + serviceName
                + " for " + duration + "ms");
        return 0;
    }

    private int runResolveRotation() {
        final int userId = Integer.parseInt(getNextArgRequired());
        final RotationResolutionRequest request = new RotationResolutionRequest("",
                Surface.ROTATION_0, Surface.ROTATION_0, true, 2000L);
        mService.resolveRotationShellCommand(userId, sTestableRotationCallbackInternal, request);
        return 0;
    }

    private int getLastResolution() {
        final PrintWriter out = getOutPrintWriter();
        out.println(sTestableRotationCallbackInternal.getLastCallbackCode());
        return 0;
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Rotation Resolver commands: ");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println();
        pw.println("  resolve-rotation USER_ID: request a rotation resolution.");
        pw.println("  get-last-resolution: show the last rotation resolution result.");
        pw.println("  get-bound-package USER_ID:");
        pw.println("    Print the bound package that implements the service.");
        pw.println("  set-temporary-service USER_ID [COMPONENT_NAME DURATION]");
        pw.println("    Temporarily (for DURATION ms) changes the service implementation.");
        pw.println("    To reset, call with just the USER_ID argument.");
    }
}
