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

package com.android.systemui.onehanded;

import static android.view.Display.DEFAULT_DISPLAY;

import android.content.Context;
import android.database.ContentObserver;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.SystemUI;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.statusbar.CommandQueue;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * A service that controls UI of the one handed mode function.
 */
@Singleton
public class OneHandedUI extends SystemUI implements CommandQueue.Callbacks, Dumpable {
    private static final String TAG = "OneHandedUI";

    private final OneHandedManagerImpl mOneHandedManager;
    private final CommandQueue mCommandQueue;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final OneHandedSettingsUtil mSettingUtil;
    private final OneHandedTimeoutHandler mTimeoutHandler;
    private final ScreenLifecycle mScreenLifecycle;

    private final ContentObserver mEnabledObserver = new ContentObserver(mMainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            final boolean enabled = OneHandedSettingsUtil.getSettingsOneHandedModeEnabled(
                    mContext.getContentResolver());
            OneHandedEvents.writeEvent(enabled
                    ? OneHandedEvents.EVENT_ONE_HANDED_SETTINGS_ENABLED_ON
                    : OneHandedEvents.EVENT_ONE_HANDED_SETTINGS_ENABLED_OFF);
            if (mOneHandedManager != null) {
                mOneHandedManager.setOneHandedEnabled(enabled);
            }
        }
    };

    private final ContentObserver mTimeoutObserver = new ContentObserver(mMainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            final int newTimeout = OneHandedSettingsUtil.getSettingsOneHandedModeTimeout(
                    mContext.getContentResolver());
            int metricsId = OneHandedEvents.OneHandedSettingsTogglesEvent.INVALID.getId();
            switch (newTimeout) {
                case OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_NEVER:
                    metricsId = OneHandedEvents.EVENT_ONE_HANDED_SETTINGS_TIMEOUT_SECONDS_NEVER;
                    break;
                case OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_SHORT_IN_SECONDS:
                    metricsId = OneHandedEvents.EVENT_ONE_HANDED_SETTINGS_TIMEOUT_SECONDS_4;
                    break;
                case OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_MEDIUM_IN_SECONDS:
                    metricsId = OneHandedEvents.EVENT_ONE_HANDED_SETTINGS_TIMEOUT_SECONDS_8;
                    break;
                case OneHandedSettingsUtil.ONE_HANDED_TIMEOUT_LONG_IN_SECONDS:
                    metricsId = OneHandedEvents.EVENT_ONE_HANDED_SETTINGS_TIMEOUT_SECONDS_12;
                    break;
                default:
                    // do nothing
                    break;
            }
            OneHandedEvents.writeEvent(metricsId);

            if (mTimeoutHandler != null) {
                mTimeoutHandler.setTimeout(newTimeout);
            }
        }
    };

    private final ContentObserver mTaskChangeExitObserver = new ContentObserver(mMainHandler) {
        @Override
        public void onChange(boolean selfChange) {
            OneHandedEvents.writeEvent(OneHandedSettingsUtil.getSettingsTapsAppToExit(
                    mContext.getContentResolver())
                    ? OneHandedEvents.EVENT_ONE_HANDED_SETTINGS_APP_TAPS_EXIT_ON
                    : OneHandedEvents.EVENT_ONE_HANDED_SETTINGS_APP_TAPS_EXIT_OFF);
        }
    };

    @Inject
    public OneHandedUI(Context context,
            CommandQueue commandQueue,
            OneHandedManagerImpl oneHandedManager,
            DumpManager dumpManager,
            OneHandedSettingsUtil settingsUtil,
            ScreenLifecycle screenLifecycle) {
        super(context);

        mCommandQueue = commandQueue;
        /* TODO(b/154290458) define a boolean system properties "support_one_handed_mode"
            boolean supportOneHanded = SystemProperties.getBoolean("support_one_handed_mode");
            if (!supportOneHanded) return; */
        mOneHandedManager = oneHandedManager;
        mSettingUtil =  settingsUtil;
        mTimeoutHandler = OneHandedTimeoutHandler.get();
        mScreenLifecycle = screenLifecycle;
    }

    @Override
    public void start() {
        /* TODO(b/154290458) define a boolean system properties "support_one_handed_mode"
            boolean supportOneHanded = SystemProperties.getBoolean("support_one_handed_mode");
            if (!supportOneHanded) return; */
        mCommandQueue.addCallback(this);
        setupKeyguardUpdateMonitor();
        setupScreenObserver();
        setupSettingObservers();
        setupTimeoutListener();
        updateSettings();
    }

    private void setupTimeoutListener() {
        mTimeoutHandler.registerTimeoutListener(timeoutTime -> {
            OneHandedEvents.writeEvent(OneHandedEvents.EVENT_ONE_HANDED_TRIGGER_TIMEOUT_OUT);
            stopOneHanded();
        });
    }

    private void setupKeyguardUpdateMonitor() {
        final KeyguardUpdateMonitorCallback keyguardCallback =
                new KeyguardUpdateMonitorCallback() {
                    @Override
                    public void onKeyguardBouncerChanged(boolean bouncer) {
                        if (bouncer) {
                            stopOneHanded();
                        }
                    }

                    @Override
                    public void onKeyguardVisibilityChanged(boolean showing) {
                        stopOneHanded();
                    }
                };
        Dependency.get(KeyguardUpdateMonitor.class).registerCallback(keyguardCallback);
    }

    @Override
    public void onCameraLaunchGestureDetected(int source) {
        stopOneHanded();
    }

    private void setupScreenObserver() {
        final ScreenLifecycle.Observer mScreenObserver = new ScreenLifecycle.Observer() {
            @Override
            public void onScreenTurningOff() {
                OneHandedEvents.writeEvent(OneHandedEvents.EVENT_ONE_HANDED_TRIGGER_SCREEN_OFF_OUT);
                stopOneHanded();
            }
        };
        mScreenLifecycle.addObserver(mScreenObserver);
    }

    private void setupSettingObservers() {
        mSettingUtil.registerSettingsKeyObserver(Settings.Secure.ONE_HANDED_MODE_ENABLED,
                mContext.getContentResolver(), mEnabledObserver);
        mSettingUtil.registerSettingsKeyObserver(Settings.Secure.ONE_HANDED_MODE_TIMEOUT,
                mContext.getContentResolver(), mTimeoutObserver);
        mSettingUtil.registerSettingsKeyObserver(Settings.Secure.TAPS_APP_TO_EXIT,
                mContext.getContentResolver(), mTaskChangeExitObserver);
    }

    private void updateSettings() {
        mOneHandedManager.setOneHandedEnabled(
                mSettingUtil.getSettingsOneHandedModeEnabled(mContext.getContentResolver()));
        mTimeoutHandler.setTimeout(
                mSettingUtil.getSettingsOneHandedModeTimeout(mContext.getContentResolver()));
        mOneHandedManager.setTaskChangeToExit(
                mSettingUtil.getSettingsTapsAppToExit(mContext.getContentResolver()));
    }

    @Override
    public void setImeWindowStatus(int displayId, IBinder token, int vis, int backDisposition,
            boolean showImeSwitcher) {
        if (displayId != DEFAULT_DISPLAY) {
            return;
        }
        if ((vis & InputMethodService.IME_VISIBLE) != 0) {
            OneHandedEvents.writeEvent(OneHandedEvents.EVENT_ONE_HANDED_TRIGGER_POP_IME_OUT);
            stopOneHanded();
        }
    }

    /**
     * Trigger one handed more
     */
    public void startOneHanded() {
        mOneHandedManager.startOneHanded();
        OneHandedEvents.writeEvent(OneHandedEvents.EVENT_ONE_HANDED_TRIGGER_GESTURE_IN);
    }

    /**
     * Dismiss one handed more
     */
    public void stopOneHanded() {
        mOneHandedManager.stopOneHanded();
        OneHandedEvents.writeEvent(OneHandedEvents.EVENT_ONE_HANDED_TRIGGER_GESTURE_OUT);
    }

    /**
     * Dump all one handed data of states
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        final String innerPrefix = "  ";
        pw.println(TAG + "one handed states: ");

        if (mOneHandedManager != null) {
            ((OneHandedManagerImpl) mOneHandedManager).dump(fd, pw, args);
        }

        if (mTimeoutHandler != null) {
            mTimeoutHandler.dump(fd, pw, args);
        }

        if (mSettingUtil != null) {
            mSettingUtil.dump(pw, innerPrefix, mContext.getContentResolver());
        }
    }
}
