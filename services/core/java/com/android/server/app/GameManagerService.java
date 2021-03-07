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

package com.android.server.app;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.app.ActivityManager;
import android.app.GameManager;
import android.app.GameManager.GameMode;
import android.app.IGameManagerService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.ServiceThread;
import com.android.server.SystemService;

/**
 * Service to manage game related features.
 *
 * <p>Game service is a core service that monitors, coordinates game related features,
 * as well as collect metrics.</p>
 *
 * @hide
 */
public final class GameManagerService extends IGameManagerService.Stub {
    public static final String TAG = "GameManagerService";

    private static final boolean DEBUG = false;

    static final int WRITE_SETTINGS = 1;
    static final int REMOVE_SETTINGS = 2;
    static final int WRITE_SETTINGS_DELAY = 10 * 1000;  // 10 seconds

    private final Context mContext;
    private final Object mLock = new Object();
    private final Handler mHandler;
    @GuardedBy("mLock")
    private final ArrayMap<Integer, GameManagerSettings> mSettings = new ArrayMap<>();

    public GameManagerService(Context context) {
        this(context, createServiceThread().getLooper());
    }

    GameManagerService(Context context, Looper looper) {
        mContext = context;
        mHandler = new SettingsHandler(looper);
    }

    class SettingsHandler extends Handler {

        SettingsHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            doHandleMessage(msg);
        }

        void doHandleMessage(Message msg) {
            switch (msg.what) {
                case WRITE_SETTINGS: {
                    final int userId = (int) msg.obj;
                    if (userId < 0) {
                        Slog.wtf(TAG, "Attempt to write settings for invalid user: " + userId);
                        synchronized (mLock) {
                            removeMessages(WRITE_SETTINGS, msg.obj);
                        }
                        break;
                    }

                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                    synchronized (mLock) {
                        removeMessages(WRITE_SETTINGS, msg.obj);
                        if (mSettings.containsKey(userId)) {
                            GameManagerSettings userSettings = mSettings.get(userId);
                            userSettings.writePersistentDataLocked();
                        }
                    }
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    break;
                }
                case REMOVE_SETTINGS: {
                    final int userId = (int) msg.obj;
                    if (userId < 0) {
                        Slog.wtf(TAG, "Attempt to write settings for invalid user: " + userId);
                        synchronized (mLock) {
                            removeMessages(WRITE_SETTINGS, msg.obj);
                            removeMessages(REMOVE_SETTINGS, msg.obj);
                        }
                        break;
                    }

                    synchronized (mLock) {
                        // Since the user was removed, ignore previous write message
                        // and do write here.
                        removeMessages(WRITE_SETTINGS, msg.obj);
                        removeMessages(REMOVE_SETTINGS, msg.obj);
                        if (mSettings.containsKey(userId)) {
                            final GameManagerSettings userSettings = mSettings.get(userId);
                            mSettings.remove(userId);
                            userSettings.writePersistentDataLocked();
                        }
                    }
                    break;
                }
            }
        }
    }

    /**
     * SystemService lifecycle for GameService.
     *
     * @hide
     */
    public static class Lifecycle extends SystemService {
        private GameManagerService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mService = new GameManagerService(getContext());
            publishBinderService(Context.GAME_SERVICE, mService);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == PHASE_BOOT_COMPLETED) {
                mService.onBootCompleted();
            }
        }

        @Override
        public void onUserStarting(@NonNull TargetUser user) {
            mService.onUserStarting(user.getUserIdentifier());
        }

        @Override
        public void onUserStopping(@NonNull TargetUser user) {
            mService.onUserStopping(user.getUserIdentifier());
        }
    }

    private boolean isValidPackageName(String packageName) {
        final PackageManager pm = mContext.getPackageManager();
        try {
            return pm.getPackageUid(packageName, 0) == Binder.getCallingUid();
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return false;
        }
    }

    private void checkPermission(String permission) throws SecurityException {
        if (mContext.checkCallingOrSelfPermission(permission)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Access denied to process: " + Binder.getCallingPid()
                    + ", must have permission " + permission);
        }
    }

    private @GameMode int getGameModeFromSettings(String packageName, int userId) {
        synchronized (mLock) {
            if (!mSettings.containsKey(userId)) {
                Log.w(TAG, "User ID '" + userId + "' does not have a Game Mode"
                        + " selected for package: '" + packageName + "'");
                return GameManager.GAME_MODE_UNSUPPORTED;
            }

            return mSettings.get(userId).getGameModeLocked(packageName);
        }
    }

    /**
     * Get the Game Mode for the package name.
     * Verifies that the calling process is for the matching package UID or has
     * {@link android.Manifest.permission#MANAGE_GAME_MODE}.
     */
    @Override
    public @GameMode int getGameMode(String packageName, int userId)
            throws SecurityException {
        // TODO(b/178860939): Restrict to games only.

        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, true, "getGameMode",
                "com.android.server.app.GameManagerService");

        if (isValidPackageName(packageName)) {
            return getGameModeFromSettings(packageName, userId);
        }

        checkPermission(Manifest.permission.MANAGE_GAME_MODE);
        return getGameModeFromSettings(packageName, userId);
    }

    /**
     * Sets the Game Mode for the package name.
     * Verifies that the calling process has {@link android.Manifest.permission#MANAGE_GAME_MODE}.
     */
    @Override
    @RequiresPermission(Manifest.permission.MANAGE_GAME_MODE)
    public void setGameMode(String packageName, @GameMode int gameMode, int userId)
            throws SecurityException {
        // TODO(b/178860939): Restrict to games only.

        checkPermission(Manifest.permission.MANAGE_GAME_MODE);

        userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, true, "setGameMode",
                "com.android.server.app.GameManagerService");

        synchronized (mLock) {
            if (!mSettings.containsKey(userId)) {
                return;
            }
            GameManagerSettings userSettings = mSettings.get(userId);
            userSettings.setGameModeLocked(packageName, gameMode);
            final Message msg = mHandler.obtainMessage(WRITE_SETTINGS);
            msg.obj = userId;
            if (!mHandler.hasEqualMessages(WRITE_SETTINGS, userId)) {
                mHandler.sendMessageDelayed(msg, WRITE_SETTINGS_DELAY);
            }
        }
    }

    /**
     * Notified when boot is completed.
     */
    @VisibleForTesting
    void onBootCompleted() {
        Slog.d(TAG, "onBootCompleted");
    }

    void onUserStarting(int userId) {
        synchronized (mLock) {
            if (mSettings.containsKey(userId)) {
                return;
            }

            GameManagerSettings userSettings =
                    new GameManagerSettings(Environment.getDataSystemDeDirectory(userId));
            mSettings.put(userId, userSettings);
            userSettings.readPersistentDataLocked();
        }
    }

    void onUserStopping(int userId) {
        synchronized (mLock) {
            if (!mSettings.containsKey(userId)) {
                return;
            }
            final Message msg = mHandler.obtainMessage(REMOVE_SETTINGS);
            msg.obj = userId;
            mHandler.sendMessage(msg);
        }
    }

    private static ServiceThread createServiceThread() {
        ServiceThread handlerThread = new ServiceThread(TAG,
                Process.THREAD_PRIORITY_BACKGROUND, true /*allowIo*/);
        handlerThread.start();
        return handlerThread;
    }
}
