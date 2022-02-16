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

import static com.android.server.backup.testing.TestUtils.runToEndOfTasks;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.annotation.Nullable;
import android.app.Application;
import android.app.IActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;

import com.android.server.backup.BackupAgentTimeoutParameters;
import com.android.server.backup.BackupManagerService;
import com.android.server.backup.TransportManager;
import com.android.server.backup.UserBackupManagerService;

import org.mockito.stubbing.Answer;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowBinder;

import java.io.File;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.concurrent.atomic.AtomicReference;

/** Test utils for {@link UserBackupManagerService} and friends. */
public class BackupManagerServiceTestUtils {
    /**
     * Creates an instance of {@link UserBackupManagerService} with a new backup thread and runs
     * tasks that were posted to it during instantiation.
     *
     * <p>If the class-under-test is going to execute methods as the system, it's a good idea to
     * also call {@link #setUpBinderCallerAndApplicationAsSystem(Application)} before this method.
     *
     * @see #createUserBackupManagerServiceAndRunTasks(int, Context, HandlerThread, File, File,
     *     TransportManager)
     */
    public static UserBackupManagerService createUserBackupManagerServiceAndRunTasks(
            int userId,
            Context context,
            File baseStateDir,
            File dataDir,
            TransportManager transportManager) {
        return createUserBackupManagerServiceAndRunTasks(
                userId, context, startBackupThread(null), baseStateDir, dataDir, transportManager);
    }

    /**
     * Creates an instance of {@link UserBackupManagerService} with the supplied backup thread
     * {@code backupThread} and runs tasks that were posted to it during instantiation.
     *
     * <p>If the class-under-test is going to execute methods as the system, it's a good idea to
     * also call {@link #setUpBinderCallerAndApplicationAsSystem(Application)} before this method.
     */
    public static UserBackupManagerService createUserBackupManagerServiceAndRunTasks(
            int userId,
            Context context,
            HandlerThread backupThread,
            File baseStateDir,
            File dataDir,
            TransportManager transportManager) {
        UserBackupManagerService backupManagerService =
                UserBackupManagerService.createAndInitializeService(
                        userId,
                        context,
                        new BackupManagerService(context),
                        backupThread,
                        baseStateDir,
                        dataDir,
                        transportManager);
        runToEndOfTasks(backupThread.getLooper());
        return backupManagerService;
    }

    /**
     * Sets up basic mocks for {@link UserBackupManagerService} mock. If {@code
     * backupManagerService} is a spy, make sure you provide in the arguments the same objects that
     * the original object uses.
     *
     * <p>If the class-under-test is going to execute methods as the system, it's a good idea to
     * also call {@link #setUpBinderCallerAndApplicationAsSystem(Application)}.
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void setUpBackupManagerServiceBasics(
            UserBackupManagerService backupManagerService,
            Application application,
            TransportManager transportManager,
            PackageManager packageManager,
            Handler backupHandler,
            UserBackupManagerService.BackupWakeLock wakeLock,
            BackupAgentTimeoutParameters agentTimeoutParameters) {

        when(backupManagerService.getContext()).thenReturn(application);
        when(backupManagerService.getTransportManager()).thenReturn(transportManager);
        when(backupManagerService.getPackageManager()).thenReturn(packageManager);
        when(backupManagerService.getBackupHandler()).thenReturn(backupHandler);
        when(backupManagerService.getCurrentOpLock()).thenReturn(new Object());
        when(backupManagerService.getQueueLock()).thenReturn(new Object());
        when(backupManagerService.getActivityManager()).thenReturn(mock(IActivityManager.class));
        when(backupManagerService.getWakelock()).thenReturn(wakeLock);
        when(backupManagerService.getAgentTimeoutParameters()).thenReturn(agentTimeoutParameters);

        AccessorMock backupEnabled = mockAccessor(false);
        doAnswer(backupEnabled.getter).when(backupManagerService).isBackupEnabled();
        doAnswer(backupEnabled.setter).when(backupManagerService).setBackupEnabled(anyBoolean());

        AccessorMock backupRunning = mockAccessor(false);
        doAnswer(backupEnabled.getter).when(backupManagerService).isBackupRunning();
        doAnswer(backupRunning.setter).when(backupManagerService).setBackupRunning(anyBoolean());
    }

    public static void setUpBinderCallerAndApplicationAsSystem(Application application) {
        final int uid = Process.SYSTEM_UID;
        final int pid = 1211;
        ShadowBinder.setCallingUid(uid);
        ShadowBinder.setCallingPid(pid);
        ShadowApplication shadowApplication = shadowOf(application);
        shadowApplication.grantPermissions(pid, uid, "android.permission.BACKUP");
        shadowApplication.grantPermissions(pid, uid, "android.permission.CONFIRM_FULL_BACKUP");
    }

    /**
     * Returns one getter {@link Answer<T>} and one setter {@link Answer<T>} to be easily passed to
     * Mockito mocking facilities.
     *
     * @param defaultValue Value returned by the getter if there was no setter call until then.
     */
    public static <T> AccessorMock<T> mockAccessor(T defaultValue) {
        AtomicReference<T> holder = new AtomicReference<>(defaultValue);
        return new AccessorMock<>(
                invocation -> holder.get(),
                invocation -> {
                    holder.set(invocation.getArgument(0));
                    return null;
                });
    }

    public static UserBackupManagerService.BackupWakeLock createBackupWakeLock(
            Application application) {
        PowerManager powerManager =
                (PowerManager) application.getSystemService(Context.POWER_SERVICE);
        return new UserBackupManagerService.BackupWakeLock(
                powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*backup*"), 0);
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
    public static HandlerThread startBackupThread(
            @Nullable UncaughtExceptionHandler exceptionHandler) {
        HandlerThread backupThread = new HandlerThread("backup");
        backupThread.setUncaughtExceptionHandler(exceptionHandler);
        backupThread.start();
        return backupThread;
    }

    /**
     * Similar to {@link #startBackupThread(UncaughtExceptionHandler)} but logging uncaught
     * exceptions to logcat.
     *
     * @param tag Tag used for logging exceptions.
     * @return The backup thread.
     * @see #startBackupThread(UncaughtExceptionHandler)
     */
    public static HandlerThread startSilentBackupThread(String tag) {
        return startBackupThread(
                (thread, e) ->
                        Log.e(tag, "Uncaught exception in test thread " + thread.getName(), e));
    }

    private BackupManagerServiceTestUtils() {}

    public static class AccessorMock<T> {
        public Answer<T> getter;
        public Answer<T> setter;

        private AccessorMock(Answer<T> getter, Answer<T> setter) {
            this.getter = getter;
            this.setter = setter;
        }
    }
}
