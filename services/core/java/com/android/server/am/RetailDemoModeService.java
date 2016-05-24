/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.am;

import android.app.ActivityManagerNative;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Slog;

import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.pm.UserManagerService;

public class RetailDemoModeService extends SystemService {
    private static final boolean DEBUG = false;

    private static final String TAG = RetailDemoModeService.class.getSimpleName();
    private static final String DEMO_USER_NAME = "Demo";

    private static final long SCREEN_WAKEUP_DELAY = 5000;

    private ActivityManagerService mAms;
    private UserManagerService mUms;
    private PowerManager mPm;
    private PowerManager.WakeLock mWakeLock;
    private Handler mHandler;
    private ServiceThread mHandlerThread;

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!UserManager.isDeviceInDemoMode(getContext())) {
                return;
            }
            switch (intent.getAction()) {
                case Intent.ACTION_SCREEN_OFF:
                    mHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (mWakeLock.isHeld()) {
                                mWakeLock.release();
                            }
                            mWakeLock.acquire();
                        }
                    }, SCREEN_WAKEUP_DELAY);
                    break;
            }
        }
    };

    public RetailDemoModeService(Context context) {
        super(context);
    }

    private void createAndSwitchToDemoUser() {
        if (DEBUG) {
            Slog.d(TAG, "Switching to a new demo user");
        }
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                UserInfo demoUser = getUserManager().createUser(DEMO_USER_NAME,
                        UserInfo.FLAG_DEMO | UserInfo.FLAG_EPHEMERAL);
                if (demoUser != null) {
                    getActivityManager().switchUser(demoUser.id);
                }
            }
        });
    }

    private ActivityManagerService getActivityManager() {
        if (mAms == null) {
            mAms = (ActivityManagerService) ActivityManagerNative.getDefault();
        }
        return mAms;
    }

    private UserManagerService getUserManager() {
        if (mUms == null) {
            mUms = (UserManagerService) UserManagerService.Stub
                    .asInterface(ServiceManager.getService(Context.USER_SERVICE));
        }
        return mUms;
    }

    private void registerSettingsChangeObserver() {
        final Uri deviceDemoModeUri = Settings.Global.getUriFor(Settings.Global.DEVICE_DEMO_MODE);
        final ContentResolver cr = getContext().getContentResolver();
        final ContentObserver deviceDemoModeSettingObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri, int userId) {
                if (deviceDemoModeUri.equals(uri)) {
                    if (UserManager.isDeviceInDemoMode(getContext())) {
                        createAndSwitchToDemoUser();
                    }
                }
            }
        };
        cr.registerContentObserver(deviceDemoModeUri, false, deviceDemoModeSettingObserver,
                UserHandle.USER_SYSTEM);
    }

    private void registerBroadcastReceiver() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        getContext().registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, filter, null, null);
    }

    @Override
    public void onStart() {
        if (DEBUG) {
            Slog.d(TAG, "Service starting up");
        }
        mHandlerThread = new ServiceThread(TAG, android.os.Process.THREAD_PRIORITY_FOREGROUND,
                false);
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper(), null, true);
    }

    @Override
    public void onBootPhase(int bootPhase) {
        if (bootPhase != PHASE_THIRD_PARTY_APPS_CAN_START) {
            return;
        }
        mPm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
        mWakeLock = mPm
                .newWakeLock(PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, TAG);
        if (UserManager.isDeviceInDemoMode(getContext())) {
            createAndSwitchToDemoUser();
        }
        registerSettingsChangeObserver();
        registerBroadcastReceiver();
    }

    @Override
    public void onSwitchUser(int userId) {
        if (DEBUG) {
            Slog.d(TAG, "onSwitchUser: " + userId);
        }
        UserInfo ui = getUserManager().getUserInfo(userId);
        if (!ui.isDemo()) {
            if (UserManager.isDeviceInDemoMode(getContext())) {
                Slog.wtf(TAG, "Should not allow switch to non-demo user in demo mode");
            } else if (mWakeLock.isHeld()) {
                mWakeLock.release();
            }
            return;
        }
        if (!mWakeLock.isHeld()) {
            mWakeLock.acquire();
        }
    }
}
