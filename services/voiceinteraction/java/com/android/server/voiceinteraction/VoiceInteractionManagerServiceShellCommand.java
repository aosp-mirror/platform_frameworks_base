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
package com.android.server.voiceinteraction;

import android.os.Bundle;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.util.Slog;

import com.android.internal.app.IVoiceInteractionSessionShowCallback;
import com.android.server.voiceinteraction.VoiceInteractionManagerService.VoiceInteractionManagerServiceStub;

import java.io.PrintWriter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shell command for {@link VoiceInteractionManagerService}.
 */
final class VoiceInteractionManagerServiceShellCommand extends ShellCommand {
    private static final String TAG = "VoiceInteractionManager";
    private static final long TIMEOUT_MS = 5_000;

    private final VoiceInteractionManagerServiceStub mService;

    VoiceInteractionManagerServiceShellCommand(VoiceInteractionManagerServiceStub service) {
        mService = service;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        PrintWriter pw = getOutPrintWriter();
        switch (cmd) {
            case "show":
                return requestShow(pw);
            case "hide":
                return requestHide(pw);
            case "disable":
                return requestDisable(pw);
            case "restart-detection":
                return requestRestartDetection(pw);
            case "set-debug-hotword-logging":
                return setDebugHotwordLogging(pw);
            default:
                return handleDefaultCommands(cmd);
        }
    }

    @Override
    public void onHelp() {
        try (PrintWriter pw = getOutPrintWriter();) {
            pw.println("VoiceInteraction Service (voiceinteraction) commands:");
            pw.println("  help");
            pw.println("    Prints this help text.");
            pw.println("");
            pw.println("  show");
            pw.println("    Shows a session for the active service");
            pw.println("");
            pw.println("  hide");
            pw.println("    Hides the current session");
            pw.println("");
            pw.println("  disable [true|false]");
            pw.println("    Temporarily disable (when true) service");
            pw.println("");
            pw.println("  restart-detection");
            pw.println("    Force a restart of a hotword detection service");
            pw.println("");
            pw.println("  set-debug-hotword-logging [true|false]");
            pw.println("    Temporarily enable or disable debug logging for hotword result.");
            pw.println("    The debug logging will be reset after one hour from last enable.");
            pw.println("");
        }
    }

    private int requestShow(PrintWriter pw) {
        Slog.i(TAG, "requestShow()");
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger result = new AtomicInteger();

        IVoiceInteractionSessionShowCallback callback =
                new IVoiceInteractionSessionShowCallback.Stub() {
            @Override
            public void onFailed() throws RemoteException {
                Slog.w(TAG, "onFailed()");
                pw.println("callback failed");
                result.set(1);
                latch.countDown();
            }

            @Override
            public void onShown() throws RemoteException {
                Slog.d(TAG, "onShown()");
                result.set(0);
                latch.countDown();
            }
        };

        try {
            Bundle args = new Bundle();
            boolean ok = mService.showSessionForActiveService(args, /* sourceFlags= */ 0, callback,
                    /* activityToken= */ null);

            if (!ok) {
                pw.println("showSessionForActiveService() returned false");
                return 1;
            }

            if (!latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                pw.printf("Callback not called in %d ms\n", TIMEOUT_MS);
                return 1;
            }
        } catch (Exception e) {
            return handleError(pw, "showSessionForActiveService()", e);
        }

        return 0;
    }

    private int requestHide(PrintWriter pw) {
        Slog.i(TAG, "requestHide()");
        try {
            mService.hideCurrentSession();
        } catch (Exception e) {
            return handleError(pw, "requestHide()", e);
        }
        return 0;
    }

    private int requestDisable(PrintWriter pw) {
        boolean disabled = Boolean.parseBoolean(getNextArgRequired());
        Slog.i(TAG, "requestDisable(): " + disabled);
        try {
            mService.setDisabled(disabled);
        } catch (Exception e) {
            return handleError(pw, "requestDisable()", e);
        }
        return 0;
    }

    private int requestRestartDetection(PrintWriter pw) {
        Slog.i(TAG, "requestRestartDetection()");
        try {
            mService.forceRestartHotwordDetector();
        } catch (Exception e) {
            return handleError(pw, "requestRestartDetection()", e);
        }
        return 0;
    }

    private int setDebugHotwordLogging(PrintWriter pw) {
        boolean logging = Boolean.parseBoolean(getNextArgRequired());
        Slog.i(TAG, "setDebugHotwordLogging(): " + logging);
        try {
            mService.setDebugHotwordLogging(logging);
        } catch (Exception e) {
            return handleError(pw, "setDebugHotwordLogging()", e);
        }
        return 0;
    }

    private static int handleError(PrintWriter pw, String message, Exception e) {
        Slog.e(TAG,  "error calling " + message, e);
        pw.printf("Error calling %s: %s\n", message, e);
        return 1;
    }
}
