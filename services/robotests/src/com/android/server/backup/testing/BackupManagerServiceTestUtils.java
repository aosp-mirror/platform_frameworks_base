/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.testing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.app.IActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.util.SparseArray;

import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.backup.BackupManagerService;
import com.android.server.backup.TransportManager;
import com.android.server.backup.internal.BackupHandler;

import java.lang.Thread.UncaughtExceptionHandler;

/** Test utils for {@link BackupManagerService} and friends. */
public class BackupManagerServiceTestUtils {
    /** Sets up basic mocks for {@link BackupManagerService}. */
    public static void setUpBackupManagerServiceBasics(
            BackupManagerService backupManagerService,
            Context context,
            TransportManager transportManager,
            PackageManager packageManager,
            BackupHandler backupHandler,
            PowerManager.WakeLock wakeLock,
            BackupAgentTimeoutParameters agentTimeoutParameters) {
        when(backupManagerService.getContext()).thenReturn(context);
        when(backupManagerService.getTransportManager()).thenReturn(transportManager);
        when(backupManagerService.getPackageManager()).thenReturn(packageManager);
        when(backupManagerService.getBackupHandler()).thenReturn(backupHandler);
        when(backupManagerService.getCurrentOpLock()).thenReturn(new Object());
        when(backupManagerService.getQueueLock()).thenReturn(new Object());
        when(backupManagerService.getCurrentOperations()).thenReturn(new SparseArray<>());
        when(backupManagerService.getActivityManager()).thenReturn(mock(IActivityManager.class));
        when(backupManagerService.getWakelock()).thenReturn(wakeLock);
        when(backupManagerService.getAgentTimeoutParameters()).thenReturn(agentTimeoutParameters);
    }

    public static PowerManager.WakeLock createBackupWakeLock(Application application) {
        PowerManager powerManager =
                (PowerManager) application.getSystemService(Context.POWER_SERVICE);
        return powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*backup*");
    }

    /**
     * Creates a backup thread associated with a looper, starts it and returns its looper for
     * shadowing and creation of the backup handler.
     *
     * <p>Note that Robolectric simulates multi-thread in a single-thread to avoid flakiness, so
     * even though we started the thread, you should control its execution via the shadow of the
     * looper returned.
     *
     * @return The {@link Looper} for the backup thread.
     */
    public static Looper startBackupThreadAndGetLooper() {
        HandlerThread backupThread = new HandlerThread("backup");
        backupThread.start();
        return backupThread.getLooper();
    }

    /**
     * Similar to {@link #startBackupThreadAndGetLooper()} but with a custom exception handler and
     * returning the thread instead of the looper associated with it.
     *
     * @param exceptionHandler Uncaught exception handler for backup thread.
     * @return The backup thread.
     * @see #startBackupThreadAndGetLooper()
     */
    public static HandlerThread startBackupThread(UncaughtExceptionHandler exceptionHandler) {
        HandlerThread backupThread = new HandlerThread("backup");
        backupThread.setUncaughtExceptionHandler(exceptionHandler);
        backupThread.start();
        return backupThread;
    }

    private BackupManagerServiceTestUtils() {}
}
