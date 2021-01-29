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
import android.os.CancellationSignal;
import android.os.ShellCommand;
import android.rotationresolver.RotationResolverInternal.RotationResolverCallbackInternal;
import android.view.Surface;

import java.io.PrintWriter;

final class RotationResolverShellCommend extends ShellCommand {
    private static final int INITIAL_RESULT_CODE = -1;

    @NonNull
    private final RotationResolverManagerPerUserService mService;

    RotationResolverShellCommend(@NonNull RotationResolverManagerPerUserService service) {
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

    final TestableRotationCallbackInternal mTestableRotationCallbackInternal =
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
            default:
                return handleDefaultCommands(cmd);
        }
    }

    private int runResolveRotation() {
        mService.resolveRotationLocked(mTestableRotationCallbackInternal, Surface.ROTATION_0,
                Surface.ROTATION_0, "", 2000L, new CancellationSignal());
        return 0;
    }

    private int getLastResolution() {
        final PrintWriter out = getOutPrintWriter();
        out.println(mTestableRotationCallbackInternal.getLastCallbackCode());
        return 0;
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("Rotation Resolver commands: ");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println();
        pw.println("  resolve-rotation: request a rotation resolution.");
        pw.println("  get-last-resolution: show the last rotation resolution result.");
    }
}
