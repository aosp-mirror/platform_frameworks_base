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

import static android.os.UserHandle.USER_CURRENT;
import static android.view.Display.DEFAULT_DISPLAY;

import android.content.Context;
import android.content.om.IOverlayManager;
import android.content.om.OverlayInfo;
import android.database.ContentObserver;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.SystemUI;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.keyguard.ScreenLifecycle;
import com.android.systemui.statusbar.CommandQueue;

import java.io.FileDescriptor;
import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * A service that controls UI of the one handed mode function.
 */
@SysUISingleton
public class OneHandedUI extends SystemUI implements CommandQueue.Callbacks, Dumpable {
    private static final String TAG = "OneHandedUI";
    private static final String ONE_HANDED_MODE_GESTURAL_OVERLAY =
            "com.android.internal.systemui.onehanded.gestural";
    private static final String SUPPORT_ONE_HANDED_MODE = "ro.support_one_handed_mode";

    private final OneHandedController mOneHandedController;
    private final CommandQueue mCommandQueue;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final IOverlayManager mOverlayManager;
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
            if (mOneHandedController != null) {
                mOneHandedController.setOneHandedEnabled(enabled);
            }

            // Also checks swipe to notification settings since they all need gesture overlay.
            setEnabledGesturalOverlay(
                    enabled || OneHandedSettingsUtil.getSettingsSwipeToNotificationEnabled(
                            mContext.getContentResolver()));
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
            final boolean enabled = OneHandedSettingsUtil.getSettingsTapsAppToExit(
                    mContext.getContentResolver());
            OneHandedEvents.writeEvent(enabled
                    ? OneHandedEvents.EVENT_ONE_HANDED_SETTINGS_APP_TAPS_EXIT_ON
                    : OneHandedEvents.EVENT_ONE_HANDED_SETTINGS_APP_TAPS_EXIT_OFF);

            if (mOneHandedController != null) {
                mOneHandedController.setTaskChangeToExit(enabled);
            }
        }
    };

    private final ContentObserver mSwipeToNotificationEnabledObserver =
            new ContentObserver(mMainHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    final boolean enabled =
                            OneHandedSettingsUtil.getSettingsSwipeToNotificationEnabled(
                                    mContext.getContentResolver());
                    if (mOneHandedController != null) {
                        mOneHandedController.setSwipeToNotificationEnabled(enabled);
                    }

                    // Also checks one handed mode settings since they all need gesture overlay.
                    setEnabledGesturalOverlay(
                            enabled || OneHandedSettingsUtil.getSettingsOneHandedModeEnabled(
                                    mContext.getContentResolver()));
                }
            };

    @Inject
    public OneHandedUI(Context context,
            CommandQueue commandQueue,
            OneHandedController oneHandedController,
            ScreenLifecycle screenLifecycle) {
        super(context);

        if (!SystemProperties.getBoolean(SUPPORT_ONE_HANDED_MODE, false)) {
            Log.i(TAG, "Device config SUPPORT_ONE_HANDED_MODE off");
            mCommandQueue = null;
            mOneHandedController = null;
            mOverlayManager = null;
            mTimeoutHandler = null;
            mScreenLifecycle = null;
            return;
        }

        mCommandQueue = commandQueue;
        mOneHandedController = oneHandedController;
        mTimeoutHandler = OneHandedTimeoutHandler.get();
        mScreenLifecycle = screenLifecycle;
        mOverlayManager = IOverlayManager.Stub.asInterface(
                ServiceManager.getService(Context.OVERLAY_SERVICE));
    }

    @Override
    public void start() {
        if (!SystemProperties.getBoolean(SUPPORT_ONE_HANDED_MODE, false)) {
            return;
        }
        mCommandQueue.addCallback(this);
        setupKeyguardUpdateMonitor();
        setupScreenObserver();
        setupSettingObservers();
        setupTimeoutListener();
        setupGesturalOverlay();
        updateSettings();
    }

    private void setupGesturalOverlay() {
        if (!OneHandedSettingsUtil.getSettingsOneHandedModeEnabled(mContext.getContentResolver())) {
            return;
        }

        OverlayInfo info = null;
        try {
            // TODO(b/157958539) migrate new RRO config file after S+
            mOverlayManager.setHighestPriority(ONE_HANDED_MODE_GESTURAL_OVERLAY, USER_CURRENT);
            info = mOverlayManager.getOverlayInfo(ONE_HANDED_MODE_GESTURAL_OVERLAY, USER_CURRENT);
        } catch (RemoteException e) { /* Do nothing */ }

        if (info != null && !info.isEnabled()) {
            // Enable the default gestural one handed overlay.
            setEnabledGesturalOverlay(true);
        }
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
        OneHandedSettingsUtil.registerSettingsKeyObserver(Settings.Secure.ONE_HANDED_MODE_ENABLED,
                mContext.getContentResolver(), mEnabledObserver);
        OneHandedSettingsUtil.registerSettingsKeyObserver(Settings.Secure.ONE_HANDED_MODE_TIMEOUT,
                mContext.getContentResolver(), mTimeoutObserver);
        OneHandedSettingsUtil.registerSettingsKeyObserver(Settings.Secure.TAPS_APP_TO_EXIT,
                mContext.getContentResolver(), mTaskChangeExitObserver);
        OneHandedSettingsUtil.registerSettingsKeyObserver(
                Settings.Secure.SWIPE_BOTTOM_TO_NOTIFICATION_ENABLED,
                mContext.getContentResolver(), mSwipeToNotificationEnabledObserver);
    }

    private void updateSettings() {
        mOneHandedController.setOneHandedEnabled(OneHandedSettingsUtil
                .getSettingsOneHandedModeEnabled(mContext.getContentResolver()));
        mTimeoutHandler.setTimeout(OneHandedSettingsUtil
                .getSettingsOneHandedModeTimeout(mContext.getContentResolver()));
        mOneHandedController.setTaskChangeToExit(OneHandedSettingsUtil
                .getSettingsTapsAppToExit(mContext.getContentResolver()));
        mOneHandedController.setSwipeToNotificationEnabled(OneHandedSettingsUtil
                .getSettingsSwipeToNotificationEnabled(mContext.getContentResolver()));
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

    @VisibleForTesting
    private void setEnabledGesturalOverlay(boolean enabled) {
        try {
            mOverlayManager.setEnabled(ONE_HANDED_MODE_GESTURAL_OVERLAY, enabled, USER_CURRENT);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Trigger one handed more
     */
    public void startOneHanded() {
        mOneHandedController.startOneHanded();
        OneHandedEvents.writeEvent(OneHandedEvents.EVENT_ONE_HANDED_TRIGGER_GESTURE_IN);
    }

    /**
     * Dismiss one handed more
     */
    public void stopOneHanded() {
        mOneHandedController.stopOneHanded();
        OneHandedEvents.writeEvent(OneHandedEvents.EVENT_ONE_HANDED_TRIGGER_GESTURE_OUT);
    }

    /**
     * Dump all one handed data of states
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        final String innerPrefix = "  ";
        pw.println(TAG + "one handed states: ");

        if (mOneHandedController != null) {
            mOneHandedController.dump(fd, pw, args);
        }

        if (mTimeoutHandler != null) {
            mTimeoutHandler.dump(fd, pw, args);
        }

        OneHandedSettingsUtil.dump(pw, innerPrefix, mContext.getContentResolver());

        if (mOverlayManager != null) {
            OverlayInfo info = null;
            try {
                info = mOverlayManager.getOverlayInfo(ONE_HANDED_MODE_GESTURAL_OVERLAY,
                        USER_CURRENT);
            } catch (RemoteException e) { /* Do nothing */ }

            if (info != null && !info.isEnabled()) {
                pw.print(innerPrefix + "OverlayInfo=");
                pw.println(info);
            }
        }
    }
}
