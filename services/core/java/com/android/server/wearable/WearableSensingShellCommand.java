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

package com.android.server.wearable;

import android.annotation.NonNull;
import android.app.wearable.WearableSensingManager;
import android.content.ComponentName;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.ShellCommand;
import android.util.Slog;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.time.Duration;

final class WearableSensingShellCommand extends ShellCommand {
    private static final String TAG = WearableSensingShellCommand.class.getSimpleName();

    static final TestableCallbackInternal sTestableCallbackInternal =
            new TestableCallbackInternal();

    @NonNull
    private final WearableSensingManagerService mService;

    private static ParcelFileDescriptor[] sPipe;

    WearableSensingShellCommand(@NonNull WearableSensingManagerService service) {
        mService = service;
    }

    /** Callbacks for WearableSensingService results used internally for testing. */
    static class TestableCallbackInternal {
        private int mLastStatus;

        public int getLastStatus() {
            return mLastStatus;
        }

        @NonNull
        private RemoteCallback createRemoteStatusCallback() {
            return new RemoteCallback(result -> {
                int status = result.getInt(WearableSensingManager.STATUS_RESPONSE_BUNDLE_KEY);
                final long token = Binder.clearCallingIdentity();
                try {
                    mLastStatus = status;
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            });
        }
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }

        switch (cmd) {
            case "create-data-stream":
                return createDataStream();
            case "destroy-data-stream":
                return destroyDataStream();
            case "provide-data-stream":
                return provideDataStream();
            case "write-to-data-stream":
                return writeToDataStream();
            case "provide-data":
                return provideData();
            case "get-last-status-code":
                return getLastStatusCode();
            case "get-bound-package":
                return getBoundPackageName();
            case "set-temporary-service":
                return setTemporaryService();
            case "set-data-request-rate-limit-window-size":
                return setDataRequestRateLimitWindowSize();
            default:
                return handleDefaultCommands(cmd);
        }
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        pw.println("WearableSensingCommands commands: ");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println();
        pw.println("  create-data-stream: Creates a data stream to be provided.");
        pw.println("  destroy-data-stream: Destroys a data stream if one was previously created.");
        pw.println("  provide-data-stream USER_ID: "
                + "Provides data stream to WearableSensingService.");
        pw.println("  write-to-data-stream STRING: writes string to data stream.");
        pw.println("  provide-data USER_ID KEY INTEGER: provide integer as data with key.");
        pw.println("  get-last-status-code: Prints the latest request status code.");
        pw.println("  get-bound-package USER_ID:"
                + "     Print the bound package that implements the service.");
        pw.println("  set-temporary-service USER_ID [PACKAGE_NAME] [COMPONENT_NAME DURATION]");
        pw.println("    Temporarily (for DURATION ms) changes the service implementation.");
        pw.println("    To reset, call with just the USER_ID argument.");
        pw.println("  set-data-request-rate-limit-window-size WINDOW_SIZE");
        pw.println("    Set the window size used in data request rate limiting to WINDOW_SIZE"
                + " seconds.");
        pw.println("    positive WINDOW_SIZE smaller than 20 will be automatically set to 20.");
        pw.println("    To reset, call with 0 or a negative WINDOW_SIZE.");
    }

    private int createDataStream() {
        Slog.d(TAG, "createDataStream");
        try {
            sPipe = ParcelFileDescriptor.createPipe();
        } catch (IOException e) {
            Slog.d(TAG, "Failed to createDataStream.", e);
        }
        return 0;
    }

    private int destroyDataStream() {
        Slog.d(TAG, "destroyDataStream");
        try {
            if (sPipe != null) {
                sPipe[0].close();
                sPipe[1].close();
            }
        } catch (IOException e) {
            Slog.d(TAG, "Failed to destroyDataStream.", e);
        }
        return 0;
    }

    private int provideDataStream() {
        Slog.d(TAG, "provideDataStream");
        if (sPipe != null) {
            final int userId = Integer.parseInt(getNextArgRequired());
            mService.provideDataStream(userId, sPipe[0],
                    sTestableCallbackInternal.createRemoteStatusCallback());
        }
        return 0;
    }

    private int writeToDataStream() {
        Slog.d(TAG, "writeToDataStream");
        if (sPipe != null) {
            final String value = getNextArgRequired();
            try {
                ParcelFileDescriptor writePipe = sPipe[1].dup();
                OutputStream os = new ParcelFileDescriptor.AutoCloseOutputStream(writePipe);
                os.write(value.getBytes());
            } catch (IOException e) {
                Slog.d(TAG, "Failed to writeToDataStream.", e);
            }
        }
        return 0;
    }

    private int provideData() {
        Slog.d(TAG, "provideData");
        final int userId = Integer.parseInt(getNextArgRequired());
        final String key = getNextArgRequired();
        final int value = Integer.parseInt(getNextArgRequired());
        PersistableBundle data = new PersistableBundle();
        data.putInt(key, value);

        mService.provideData(userId, data, null,
                sTestableCallbackInternal.createRemoteStatusCallback());
        return 0;
    }

    private int getLastStatusCode() {
        Slog.d(TAG, "getLastStatusCode");
        final PrintWriter resultPrinter = getOutPrintWriter();
        int lastStatus = sTestableCallbackInternal.getLastStatus();
        resultPrinter.println(lastStatus);
        return 0;
    }

    private int setTemporaryService() {
        final PrintWriter out = getOutPrintWriter();
        final int userId = Integer.parseInt(getNextArgRequired());
        final String serviceName = getNextArg();
        if (serviceName == null) {
            mService.resetTemporaryService(userId);
            out.println("WearableSensingManagerService temporary reset. ");
            return 0;
        }

        final int duration = Integer.parseInt(getNextArgRequired());
        mService.setTemporaryService(userId, serviceName, duration);
        out.println("WearableSensingService temporarily set to " + serviceName
                + " for " + duration + "ms");
        return 0;
    }

    private int getBoundPackageName() {
        final PrintWriter resultPrinter = getOutPrintWriter();
        final int userId = Integer.parseInt(getNextArgRequired());
        final ComponentName componentName = mService.getComponentName(userId);
        resultPrinter.println(componentName == null ? "" : componentName.getPackageName());
        return 0;
    }

    private int setDataRequestRateLimitWindowSize() {
        Slog.d(TAG, "setDataRequestRateLimitWindowSize");
        int windowSizeSeconds = Integer.parseInt(getNextArgRequired());
        if (windowSizeSeconds <= 0) {
            mService.resetDataRequestRateLimitWindowSize();
        } else {
            // 20 is the minimum window size supported by the rate limiter.
            // It is defined by com.android.server.utils.quota.QuotaTracker#MIN_WINDOW_SIZE_MS
            if (windowSizeSeconds < 20) {
                windowSizeSeconds = 20;
            }
            mService.setDataRequestRateLimitWindowSize(Duration.ofSeconds(windowSizeSeconds));
        }
        return 0;
    }
}
