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
import android.app.ambientcontext.AmbientContextManager;
import android.app.ambientcontext.IAmbientContextObserver;
import android.content.ComponentName;
import android.os.Binder;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ShellCommand;

import java.io.PrintWriter;
import java.util.List;

/**
 * Shell command for {@link AmbientContextManagerService}.
 */
final class AmbientContextShellCommand extends ShellCommand {

    private static final AmbientContextEventRequest REQUEST =
            new AmbientContextEventRequest.Builder()
                    .addEventType(AmbientContextEvent.EVENT_COUGH)
                    .addEventType(AmbientContextEvent.EVENT_SNORE)
                    .addEventType(AmbientContextEvent.EVENT_BACK_DOUBLE_TAP)
                    .build();

    @NonNull
    private final AmbientContextManagerService mService;

    AmbientContextShellCommand(@NonNull AmbientContextManagerService service) {
        mService = service;
    }

    /** Callbacks for AmbientContextEventService results used internally for testing. */
    static class TestableCallbackInternal {
        private List<AmbientContextEvent> mLastEvents;
        private int mLastStatus;

        public List<AmbientContextEvent> getLastEvents() {
            return mLastEvents;
        }

        public int getLastStatus() {
            return mLastStatus;
        }

        @NonNull
        private IAmbientContextObserver createAmbientContextObserver() {
            return new IAmbientContextObserver.Stub() {
                @Override
                public void onEvents(List<AmbientContextEvent> events) throws RemoteException {
                    mLastEvents = events;
                    out.println("Detection events available: " + events);
                }

                @Override
                public void onRegistrationComplete(int statusCode) throws RemoteException {
                    mLastStatus = statusCode;
                }
            };
        }

        @NonNull
        private RemoteCallback createRemoteStatusCallback() {
            return new RemoteCallback(result -> {
                int status = result.getInt(AmbientContextManager.STATUS_RESPONSE_BUNDLE_KEY);
                final long token = Binder.clearCallingIdentity();
                try {
                    mLastStatus = status;
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
        mService.startDetection(
                userId, REQUEST, packageName,
                sTestableCallbackInternal.createAmbientContextObserver());
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
        final PrintWriter resultPrinter = getOutPrintWriter();
        int lastStatus = sTestableCallbackInternal.getLastStatus();
        resultPrinter.println(lastStatus);
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
        pw.println("  stop-detection USER_ID PACKAGE_NAME: Stops AmbientContextEvent detection.");
        pw.println("  get-last-status-code: Prints the latest request status code.");
        pw.println("  query-service-status USER_ID PACKAGE_NAME: Prints the service status code.");
        pw.println("  get-bound-package USER_ID:"
                + "     Print the bound package that implements the service.");
        pw.println("  set-temporary-service USER_ID [PACKAGE_NAME] [COMPONENT_NAME DURATION]");
        pw.println("    Temporarily (for DURATION ms) changes the service implementation.");
        pw.println("    To reset, call with just the USER_ID argument.");
    }

    private int getBoundPackageName() {
        final PrintWriter resultPrinter = getOutPrintWriter();
        final int userId = Integer.parseInt(getNextArgRequired());
        final ComponentName componentName = mService.getComponentName(userId);
        resultPrinter.println(componentName == null ? "" : componentName.getPackageName());
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
