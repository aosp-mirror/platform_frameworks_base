/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.app;

import android.accessibilityservice.IAccessibilityServiceClient;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.view.InputEvent;
import android.view.WindowContentFrameStats;
import android.view.WindowAnimationFrameStats;
import android.os.ParcelFileDescriptor;

/**
 * This interface contains privileged operations a shell program can perform
 * on behalf of an instrumentation that it runs. These operations require
 * special permissions which the shell user has but the instrumentation does
 * not. Running privileged operations by the shell user on behalf of an
 * instrumentation is needed for running UiTestCases.
 *
 * {@hide}
 */
interface IUiAutomationConnection {
    void connect(IAccessibilityServiceClient client, int flags);
    void disconnect();
    boolean injectInputEvent(in InputEvent event, boolean sync);
    boolean setRotation(int rotation);
    Bitmap takeScreenshot(in Rect crop, int rotation);
    boolean clearWindowContentFrameStats(int windowId);
    WindowContentFrameStats getWindowContentFrameStats(int windowId);
    void clearWindowAnimationFrameStats();
    WindowAnimationFrameStats getWindowAnimationFrameStats();
    void executeShellCommand(String command, in ParcelFileDescriptor sink,
            in ParcelFileDescriptor source);
    void grantRuntimePermission(String packageName, String permission, int userId);
    void revokeRuntimePermission(String packageName, String permission, int userId);

    // Called from the system process.
    oneway void shutdown();
}
