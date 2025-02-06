/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;

import android.Manifest;
import android.content.Context;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.AmUtils;
import com.android.compatibility.common.util.FileUtils;
import com.android.compatibility.common.util.SystemUtil;
import com.android.compatibility.common.util.ThrowingRunnable;

import java.io.FileInputStream;
import java.io.IOException;

public class NotificationSystemUtil {

    /**
     * Runs a {@link ThrowingRunnable} as the Shell, while adopting SystemUI's permission (as
     * checked by {@code NotificationManagerService#isCallerSystemOrSystemUi}).
     */
    protected static void runAsSystemUi(@NonNull ThrowingRunnable runnable) {
        SystemUtil.runWithShellPermissionIdentity(
                InstrumentationRegistry.getInstrumentation().getUiAutomation(),
                runnable, Manifest.permission.STATUS_BAR_SERVICE);
    }

    static void toggleNotificationPolicyAccess(Context context, String packageName,
            boolean on) throws IOException {

        String command = " cmd notification " + (on ? "allow_dnd " : "disallow_dnd ") + packageName
                + " " + context.getUserId();

        runCommand(command, InstrumentationRegistry.getInstrumentation());
        AmUtils.waitForBroadcastBarrier();

        NotificationManager nm = context.getSystemService(NotificationManager.class);
        assertEquals("Notification Policy Access Grant is "
                + nm.isNotificationPolicyAccessGranted() + " not " + on + " for "
                + packageName, on, nm.isNotificationPolicyAccessGranted());
    }

    private static void runCommand(String command, Instrumentation instrumentation)
            throws IOException {
        UiAutomation uiAutomation = instrumentation.getUiAutomation();
        try (FileInputStream fis = new ParcelFileDescriptor.AutoCloseInputStream(
                uiAutomation.executeShellCommand(command))) {
            FileUtils.readInputStreamFully(fis);
        }
    }
}
