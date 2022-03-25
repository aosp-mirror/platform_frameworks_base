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

package com.android.server.ambientcontext;

import static java.lang.System.out;

import android.annotation.NonNull;
import android.app.ambientcontext.AmbientContextEvent;
import android.app.ambientcontext.AmbientContextEventRequest;
import android.content.ComponentName;
import android.os.Binder;
import android.os.RemoteCallback;
import android.os.ShellCommand;
import android.service.ambientcontext.AmbientContextDetectionResult;
import android.service.ambientcontext.AmbientContextDetectionServiceStatus;

import java.io.PrintWriter;

/**
 * Shell command for {@link AmbientContextManagerService}.
 */
final class AmbientContextShellCommand extends ShellCommand {

    private static final AmbientContextEventRequest REQUEST =
            new AmbientContextEventRequest.Builder()
                    .addEventType(AmbientContextEvent.EVENT_COUGH)
                    .addEventType(AmbientContextEvent.EVENT_SNORE)
                    .build();

    @NonNull
    private final AmbientContextManagerService mService;

    AmbientContextShellCommand(@NonNull AmbientContextManagerService service) {
        mService = service;
    }

    /** Callbacks for AmbientContextEventService results used internally for testing. */
    static class TestableCallbackInternal {
        private AmbientContextDetectionResult mLastResult;
        private AmbientContextDetectionServiceStatus mLastStatus;

        public AmbientContextDetectionResult getLastResult() {
            return mLastResult;
        }

        public AmbientContextDetectionServiceStatus getLastStatus() {
            return mLastStatus;
        }

        @NonNull
        private RemoteCallback createRemoteDetectionResultCallback() {
            return new RemoteCallback(result -> {
                AmbientContextDetectionResult detectionResult =
                        (AmbientContextDetectionResult) result.get(
                                AmbientContextDetectionResult.RESULT_RESPONSE_BUNDLE_KEY);
                final long token = Binder.clearCallingIdentity();
                try {
                    mLastResult = detectionResult;
                    out.println("Detection result available: " + detectionResult);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            });
        }

        @NonNull
        private RemoteCallback createRemoteStatusCallback() {
            return new RemoteCallback(result -> {
                AmbientContextDetectionServiceStatus status =
                        (AmbientContextDetectionServiceStatus) result.get(
                                AmbientContextDetectionServiceStatus.STATUS_RESPONSE_BUNDLE_KEY);
                final long token = Binder.clearCallingIdentity();
                try {
                    mLastStatus = status;
                    out.println("Status available: " + status);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            });
        }
    }

    static final TestableCallbackInternal sTestableCallbackInternal =
            new TestableCallbackInternal();

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }

        switch (cmd) {
            case "start-detection":
                return runStartDetection();
            case "stop-detection":
                return runStopDetection();
            case "get-last-status-code":
                return getLastStatusCode();
            case "get-last-package-name":
                return getLastPackageName();
            case "query-service-status":
                return runQueryServiceStatus();
            case "get-bound-package":
                return getBoundPackageName();
            case "set-temporary-service":
                return setTemporaryService();
            default:
                return handleDefaultCommands(cmd);
        }
    }

    private int runStartDetection() {
        final int userId = Integer.parseInt(getNextArgRequired());
        final String packageName = getNextArgRequired();
        mService.startDetection(userId, REQUEST, packageName,
                sTestableCallbackInternal.createRemoteDetectionResultCallback(),
                sTestableCallbackInternal.createRemoteStatusCallback());
        return 0;
    }

    private int runStopDetection() {
        final int userId = Integer.parseInt(getNextArgRequired());
        final String packageName = getNextArgRequired();
        mService.stopAmbientContextEvent(userId, packageName);
        return 0;
    }

    private int runQueryServiceStatus() {
        final int userId = Integer.parseInt(getNextArgRequired());
        final String packageName = getNextArgRequired();
        int[] types = new int[] {
                AmbientContextEvent.EVENT_COUGH,
                AmbientContextEvent.EVENT_SNORE};
        mService.queryServiceStatus(userId, packageName, types,
                sTestableCallbackInternal.createRemoteStatusCallback());
        return 0;
    }

    private int getLastStatusCode() {
        AmbientContextDetectionServiceStatus lastResponse =
                sTestableCallbackInternal.getLastStatus();
        if (lastResponse == null) {
            return -1;
        }
        return lastResponse.getStatusCode();
    }

    private int getLastPackageName() {
        AmbientContextDetectionServiceStatus lastResponse =
                sTestableCallbackInternal.getLastStatus();
        out.println(lastResponse == null ? "" : lastResponse.getPackageName());
        return 0;
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("AmbientContextEvent commands: ");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println();
        pw.println("  start-detection USER_ID PACKAGE_NAME: Starts AmbientContextEvent detection.");
        pw.println("  stop-detection USER_ID: Stops AmbientContextEvent detection.");
        pw.println("  get-last-status-code: Prints the latest request status code.");
        pw.println("  get-last-package-name: Prints the latest request package name.");
        pw.println("  query-event-status USER_ID PACKAGE_NAME: Prints the event status code.");
        pw.println("  get-bound-package USER_ID:"
                + "     Print the bound package that implements the service.");
        pw.println("  set-temporary-service USER_ID [COMPONENT_NAME DURATION]");
        pw.println("    Temporarily (for DURATION ms) changes the service implementation.");
        pw.println("    To reset, call with just the USER_ID argument.");
    }

    private int getBoundPackageName() {
        final PrintWriter out = getOutPrintWriter();
        final int userId = Integer.parseInt(getNextArgRequired());
        final ComponentName componentName = mService.getComponentName(userId);
        out.println(componentName == null ? "" : componentName.getPackageName());
        return 0;
    }

    private int setTemporaryService() {
        final PrintWriter out = getOutPrintWriter();
        final int userId = Integer.parseInt(getNextArgRequired());
        final String serviceName = getNextArg();
        if (serviceName == null) {
            mService.resetTemporaryService(userId);
            out.println("AmbientContextDetectionService temporary reset. ");
            return 0;
        }

        final int duration = Integer.parseInt(getNextArgRequired());
        mService.setTemporaryService(userId, serviceName, duration);
        out.println("AmbientContextDetectionService temporarily set to " + serviceName
                + " for " + duration + "ms");
        return 0;
    }
}
