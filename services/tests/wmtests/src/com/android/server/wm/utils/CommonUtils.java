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

package com.android.server.wm.utils;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.UiAutomation;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.WindowManagerGlobal;

import androidx.test.uiautomator.UiDevice;

import java.io.IOException;

/** Provides common utility functions. */
public class CommonUtils {
    private static final String TAG = "CommonUtils";
    private static final long REMOVAL_TIMEOUT_MS = 3000;
    private static final long TIMEOUT_INTERVAL_MS = 200;

    public static UiAutomation getUiAutomation() {
        return getInstrumentation().getUiAutomation();
    }

    public static void runWithShellPermissionIdentity(Runnable runnable) {
        getUiAutomation().adoptShellPermissionIdentity();
        try {
            runnable.run();
        } finally {
            getUiAutomation().dropShellPermissionIdentity();
        }
    }

    public static boolean getIgnoreOrientationRequest(int displayId) {
        final UiDevice uiDevice = UiDevice.getInstance(getInstrumentation());
        final String result;
        try {
            result = uiDevice.executeShellCommand("cmd window get-ignore-orientation-request -d "
                    + displayId);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        final String[] tokens = result.split(" ");
        if (tokens.length != 4) {
            throw new RuntimeException("Expecting a result with 4 tokens, but got " + result);
        }

        // The output looks like "ignoreOrientationRequest true for displayId=0"
        return Boolean.parseBoolean(tokens[1]);
    }

    public static void setIgnoreOrientationRequest(
            int displayId, boolean ignoreOrientationRequest) {
        runWithShellPermissionIdentity(() -> {
            final IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
            try {
                wm.setIgnoreOrientationRequest(displayId, ignoreOrientationRequest);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        });
    }

    /** Dismisses the Keyguard if it is locked. */
    public static void dismissKeyguard() {
        final KeyguardManager keyguardManager = getInstrumentation().getContext().getSystemService(
                KeyguardManager.class);
        if (keyguardManager == null || !keyguardManager.isKeyguardLocked()) {
            return;
        }
        final UiDevice device = UiDevice.getInstance(getInstrumentation());
        device.pressKeyCode(KeyEvent.KEYCODE_WAKEUP);
        device.pressKeyCode(KeyEvent.KEYCODE_MENU);
    }

    public static void waitUntilActivityRemoved(Activity activity) {
        if (!activity.isFinishing()) {
            activity.finish();
        }
        final UiDevice uiDevice = UiDevice.getInstance(getInstrumentation());
        final String classPattern = activity.getComponentName().flattenToShortString();
        final long startTime = SystemClock.uptimeMillis();
        while (SystemClock.uptimeMillis() - startTime <= REMOVAL_TIMEOUT_MS) {
            SystemClock.sleep(TIMEOUT_INTERVAL_MS);
            final String windowTokenDump;
            try {
                windowTokenDump = uiDevice.executeShellCommand("dumpsys window tokens");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            if (!windowTokenDump.contains(classPattern)) {
                return;
            }
        }
        Log.i(TAG, "Removal timeout of " + classPattern);
    }
}
