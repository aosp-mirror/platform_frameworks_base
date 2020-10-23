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

package com.android.server;

import android.annotation.Nullable;
import android.content.Context;
import android.os.CombinedVibrationEffect;
import android.os.IBinder;
import android.os.IVibratorManagerService;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.VibrationAttributes;

import com.android.internal.annotations.VisibleForTesting;

import libcore.util.NativeAllocationRegistry;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;

/** System implementation of {@link IVibratorManagerService}. */
public class VibratorManagerService extends IVibratorManagerService.Stub {
    private static final String TAG = "VibratorManagerService";
    private static final boolean DEBUG = false;

    private final Context mContext;
    private final NativeWrapper mNativeWrapper;
    private final int[] mVibratorIds;

    static native long nativeInit();

    static native long nativeGetFinalizer();

    static native int[] nativeGetVibratorIds(long nativeServicePtr);

    VibratorManagerService(Context context) {
        this(context, new Injector());
    }

    @VisibleForTesting
    VibratorManagerService(Context context, Injector injector) {
        mContext = context;
        mNativeWrapper = injector.getNativeWrapper();

        mNativeWrapper.init();

        int[] vibratorIds = mNativeWrapper.getVibratorIds();
        mVibratorIds = vibratorIds == null ? new int[0] : vibratorIds;
    }

    @Override // Binder call
    public int[] getVibratorIds() {
        return Arrays.copyOf(mVibratorIds, mVibratorIds.length);
    }

    @Override // Binder call
    public void vibrate(int uid, String opPkg, CombinedVibrationEffect effect,
            @Nullable VibrationAttributes attrs, String reason, IBinder token) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override // Binder call
    public void cancelVibrate(IBinder token) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback cb, ResultReceiver resultReceiver) {
        new VibratorManagerShellCommand(this).exec(this, in, out, err, args, cb, resultReceiver);
    }

    /** Point of injection for test dependencies */
    @VisibleForTesting
    static class Injector {

        NativeWrapper getNativeWrapper() {
            return new NativeWrapper();
        }
    }

    /** Wrapper around the static-native methods of {@link VibratorManagerService} for tests. */
    @VisibleForTesting
    public static class NativeWrapper {

        private long mNativeServicePtr = 0;

        /** Returns native pointer to newly created controller and connects with HAL service. */
        public void init() {
            mNativeServicePtr = VibratorManagerService.nativeInit();
            long finalizerPtr = VibratorManagerService.nativeGetFinalizer();

            if (finalizerPtr != 0) {
                NativeAllocationRegistry registry =
                        NativeAllocationRegistry.createMalloced(
                                VibratorManagerService.class.getClassLoader(), finalizerPtr);
                registry.registerNativeAllocation(this, mNativeServicePtr);
            }
        }

        /** Returns vibrator ids. */
        public int[] getVibratorIds() {
            return VibratorManagerService.nativeGetVibratorIds(mNativeServicePtr);
        }
    }

    /** Provides limited functionality from {@link VibratorManagerService} as shell commands. */
    private final class VibratorManagerShellCommand extends ShellCommand {

        private final IBinder mToken;

        private VibratorManagerShellCommand(IBinder token) {
            mToken = token;
        }

        @Override
        public int onCommand(String cmd) {
            if ("list".equals(cmd)) {
                return runListVibrators();
            }
            return handleDefaultCommands(cmd);
        }

        private int runListVibrators() {
            try (PrintWriter pw = getOutPrintWriter();) {
                if (mVibratorIds.length == 0) {
                    pw.println("No vibrator found");
                } else {
                    for (int id : mVibratorIds) {
                        pw.println(id);
                    }
                }
                pw.println("");
                return 0;
            }
        }

        @Override
        public void onHelp() {
            try (PrintWriter pw = getOutPrintWriter();) {
                pw.println("Vibrator Manager commands:");
                pw.println("  help");
                pw.println("    Prints this help text.");
                pw.println("");
                pw.println("  list");
                pw.println("    Prints the id of device vibrators. This do not include any ");
                pw.println("    connected input device.");
                pw.println("");
            }
        }
    }
}
