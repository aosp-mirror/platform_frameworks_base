/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.display;

import static android.media.AudioDeviceInfo.TYPE_HDMI;
import static android.media.AudioDeviceInfo.TYPE_HDMI_ARC;
import static android.media.AudioDeviceInfo.TYPE_USB_DEVICE;
import static android.provider.Settings.Global.DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.AudioManager.AudioPlaybackCallback;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Slog;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.DisplayInfo;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.display.utils.DebugUtils;

import java.util.List;


/**
 * Manages metrics logging for external display.
 */
public final class ExternalDisplayStatsService {
    private static final String TAG = "ExternalDisplayStatsService";
    // To enable these logs, run:
    // 'adb shell setprop persist.log.tag.ExternalDisplayStatsService DEBUG && adb reboot'
    private static final boolean DEBUG = DebugUtils.isDebuggable(TAG);

    private static final int INVALID_DISPLAYS_COUNT = -1;
    private static final int DISCONNECTED_STATE =
            FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED__STATE__DISCONNECTED;
    private static final int CONNECTED_STATE =
            FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED__STATE__CONNECTED;
    private static final int MIRRORING_STATE =
            FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED__STATE__MIRRORING;
    private static final int EXTENDED_STATE =
            FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED__STATE__EXTENDED;
    private static final int PRESENTATION_WHILE_MIRRORING =
            FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED__STATE__PRESENTATION_WHILE_MIRRORING;
    private static final int PRESENTATION_WHILE_EXTENDED =
            FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED__STATE__PRESENTATION_WHILE_EXTENDED;
    private static final int PRESENTATION_ENDED =
            FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED__STATE__PRESENTATION_ENDED;
    private static final int KEYGUARD =
            FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED__STATE__KEYGUARD;
    private static final int DISABLED_STATE =
            FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED__STATE__DISABLED;
    private static final int AUDIO_SINK_CHANGED =
            FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED__STATE__AUDIO_SINK_CHANGED;
    private static final int ERROR_HOTPLUG_CONNECTION =
            FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED__STATE__ERROR_HOTPLUG_CONNECTION;
    private static final int ERROR_DISPLAYPORT_LINK_FAILED =
            FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED__STATE__ERROR_DISPLAYPORT_LINK_FAILED;
    private static final int ERROR_CABLE_NOT_CAPABLE_DISPLAYPORT =
            FrameworkStatsLog
                    .EXTERNAL_DISPLAY_STATE_CHANGED__STATE__ERROR_CABLE_NOT_CAPABLE_DISPLAYPORT;

    private final Injector mInjector;

    @GuardedBy("mExternalDisplayStates")
    private final SparseIntArray mExternalDisplayStates = new SparseIntArray();

    /**
     * Count of interactive external displays or INVALID_DISPLAYS_COUNT, modified only from Handler
     */
    private int mInteractiveExternalDisplays;

    /**
     * Guards init deinit, modified only from Handler
     */
    private boolean mIsInitialized;

    /**
     * Whether audio plays on external display, modified only from Handler
     */
    private boolean mIsExternalDisplayUsedForAudio;

    private final AudioPlaybackCallback mAudioPlaybackCallback = new AudioPlaybackCallback() {
        private final Runnable mLogStateAfterAudioSinkEnabled =
                () -> logStateAfterAudioSinkChanged(true);
        private final Runnable mLogStateAfterAudioSinkDisabled =
                () -> logStateAfterAudioSinkChanged(false);

        @Override
        public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            super.onPlaybackConfigChanged(configs);
            scheduleAudioSinkChange(isExternalDisplayUsedForAudio(configs));
        }

        private boolean isExternalDisplayUsedForAudio(List<AudioPlaybackConfiguration> configs) {
            for (var config : configs) {
                var info = config.getAudioDeviceInfo();
                if (config.isActive() && info != null
                            && info.isSink()
                            && (info.getType() == TYPE_HDMI
                                        || info.getType() == TYPE_HDMI_ARC
                                        || info.getType() == TYPE_USB_DEVICE)) {
                    if (DEBUG) {
                        Slog.d(TAG, "isExternalDisplayUsedForAudio:"
                                                    + " use " + info.getProductName()
                                                    + " isActive=" + config.isActive()
                                                    + " isSink=" + info.isSink()
                                                    + " type=" + info.getType());
                    }
                    return true;
                }
                if (DEBUG) {
                    // info is null if the device is not available at the time of query.
                    if (info != null) {
                        Slog.d(TAG, "isExternalDisplayUsedForAudio:"
                                            + " drop " + info.getProductName()
                                            + " isActive=" + config.isActive()
                                            + " isSink=" + info.isSink()
                                            + " type=" + info.getType());
                    }
                }
            }
            return false;
        }

        private void scheduleAudioSinkChange(final boolean isAudioOnExternalDisplay) {
            if (DEBUG) {
                Slog.d(TAG, "scheduleAudioSinkChange:"
                                    + " mIsExternalDisplayUsedForAudio="
                                    + mIsExternalDisplayUsedForAudio
                                    + " isAudioOnExternalDisplay="
                                    + isAudioOnExternalDisplay);
            }
            mInjector.getHandler().removeCallbacks(mLogStateAfterAudioSinkEnabled);
            mInjector.getHandler().removeCallbacks(mLogStateAfterAudioSinkDisabled);
            final var callback = isAudioOnExternalDisplay ? mLogStateAfterAudioSinkEnabled
                                   : mLogStateAfterAudioSinkDisabled;
            if (isAudioOnExternalDisplay) {
                mInjector.getHandler().postDelayed(callback, /*delayMillis=*/ 10000L);
            } else {
                mInjector.getHandler().post(callback);
            }
        }
    };

    private final BroadcastReceiver mInteractivityReceiver = new BroadcastReceiver() {
        /**
         * Verifies that there is a change to the mInteractiveExternalDisplays and logs the change.
         * Executed within a handler - no need to keep lock on mInteractiveExternalDisplays update.
         */
        @Override
        public void onReceive(Context context, Intent intent) {
            int interactiveDisplaysCount = 0;
            synchronized (mExternalDisplayStates) {
                if (mExternalDisplayStates.size() == 0) {
                    return;
                }
                for (var i = 0; i < mExternalDisplayStates.size(); i++) {
                    if (mInjector.isInteractive(mExternalDisplayStates.keyAt(i))) {
                        interactiveDisplaysCount++;
                    }
                }
            }

            // For the first time, mInteractiveExternalDisplays is INVALID_DISPLAYS_COUNT(-1)
            // which is always not equal to interactiveDisplaysCount.
            if (mInteractiveExternalDisplays == interactiveDisplaysCount) {
                return;
            } else if (0 == interactiveDisplaysCount) {
                logExternalDisplayIdleStarted();
            } else if (INVALID_DISPLAYS_COUNT != mInteractiveExternalDisplays) {
                // Log Only if mInteractiveExternalDisplays was previously initialised.
                // Otherwise no need to log that idle has ended, as we assume it never started.
                // This is because, currently for enabling external display, the display must be
                // non-idle for the user to press the Mirror/Dismiss dialog button.
                logExternalDisplayIdleEnded();
            }
            mInteractiveExternalDisplays = interactiveDisplaysCount;
        }
    };

    ExternalDisplayStatsService(Context context, Handler handler) {
        this(new Injector(context, handler));
    }

    @VisibleForTesting
    ExternalDisplayStatsService(Injector injector) {
        mInjector = injector;
    }

    /**
     * Write log on hotplug connection error
     */
    public void onHotplugConnectionError() {
        logExternalDisplayError(ERROR_HOTPLUG_CONNECTION);
    }

    /**
     * Write log on DisplayPort link training failure
     */
    public void onDisplayPortLinkTrainingFailure() {
        logExternalDisplayError(ERROR_DISPLAYPORT_LINK_FAILED);
    }

    /**
     * Write log on USB cable not capable DisplayPort
     */
    public void onCableNotCapableDisplayPort() {
        logExternalDisplayError(ERROR_CABLE_NOT_CAPABLE_DISPLAYPORT);
    }

    void onDisplayConnected(final LogicalDisplay display) {
        DisplayInfo displayInfo = display.getDisplayInfoLocked();
        if (displayInfo == null || displayInfo.type != Display.TYPE_EXTERNAL) {
            return;
        }
        logStateConnected(display.getDisplayIdLocked());
    }

    void onDisplayAdded(int displayId) {
        if (mInjector.isExtendedDisplayEnabled()) {
            logStateExtended(displayId);
        } else {
            logStateMirroring(displayId);
        }
    }

    void onDisplayDisabled(int displayId) {
        logStateDisabled(displayId);
    }

    void onDisplayDisconnected(int displayId) {
        logStateDisconnected(displayId);
    }

    /**
     * Callback triggered upon presentation window gets added.
     */
    void onPresentationWindowAdded(int displayId) {
        logExternalDisplayPresentationStarted(displayId);
    }

    /**
     * Callback triggered upon presentation window gets removed.
     */
    void onPresentationWindowRemoved(int displayId) {
        logExternalDisplayPresentationEnded(displayId);
    }

    @VisibleForTesting
    boolean isInteractiveExternalDisplays() {
        return mInteractiveExternalDisplays != 0;
    }

    @VisibleForTesting
    boolean isExternalDisplayUsedForAudio() {
        return mIsExternalDisplayUsedForAudio;
    }

    private void logExternalDisplayError(int errorType) {
        final int countOfExternalDisplays;
        synchronized (mExternalDisplayStates) {
            countOfExternalDisplays = mExternalDisplayStates.size();
        }

        mInjector.writeLog(FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED,
                errorType, countOfExternalDisplays,
                mIsExternalDisplayUsedForAudio);
        if (DEBUG) {
            Slog.d(TAG, "logExternalDisplayError"
                                + " countOfExternalDisplays=" + countOfExternalDisplays
                                + " errorType=" + errorType
                                + " mIsExternalDisplayUsedForAudio="
                                + mIsExternalDisplayUsedForAudio);
        }
    }

    private void scheduleInit() {
        mInjector.getHandler().post(() -> {
            if (mIsInitialized) {
                Slog.e(TAG, "scheduleInit is called but already initialized");
                return;
            }
            mIsInitialized = true;
            var filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            mInteractiveExternalDisplays = INVALID_DISPLAYS_COUNT;
            mIsExternalDisplayUsedForAudio = false;
            mInjector.registerInteractivityReceiver(mInteractivityReceiver, filter);
            mInjector.registerAudioPlaybackCallback(mAudioPlaybackCallback);
        });
    }

    private void scheduleDeinit() {
        mInjector.getHandler().post(() -> {
            if (!mIsInitialized) {
                Slog.e(TAG, "scheduleDeinit is called but never initialized");
                return;
            }
            mIsInitialized = false;
            mInjector.unregisterInteractivityReceiver(mInteractivityReceiver);
            mInjector.unregisterAudioPlaybackCallback(mAudioPlaybackCallback);
        });
    }

    private void logStateConnected(final int displayId) {
        final int countOfExternalDisplays, state;
        synchronized (mExternalDisplayStates) {
            state = mExternalDisplayStates.get(displayId, DISCONNECTED_STATE);
            if (state != DISCONNECTED_STATE) {
                return;
            }
            mExternalDisplayStates.put(displayId, CONNECTED_STATE);
            countOfExternalDisplays = mExternalDisplayStates.size();
        }

        if (countOfExternalDisplays == 1) {
            scheduleInit();
        }

        mInjector.writeLog(FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED,
                CONNECTED_STATE, countOfExternalDisplays, mIsExternalDisplayUsedForAudio);
        if (DEBUG) {
            Slog.d(TAG, "logStateConnected"
                                + " displayId=" + displayId
                                + " countOfExternalDisplays=" + countOfExternalDisplays
                                + " currentState=" + state
                                + " state=" + CONNECTED_STATE
                                + " mIsExternalDisplayUsedForAudio="
                                + mIsExternalDisplayUsedForAudio);
        }
    }

    private void logStateDisconnected(final int displayId) {
        final int countOfExternalDisplays, state;
        synchronized (mExternalDisplayStates) {
            state = mExternalDisplayStates.get(displayId, DISCONNECTED_STATE);
            if (state == DISCONNECTED_STATE) {
                if (DEBUG) {
                    Slog.d(TAG, "logStateDisconnected"
                                        + " displayId=" + displayId
                                        + " already disconnected");
                }
                return;
            }
            countOfExternalDisplays = mExternalDisplayStates.size();
            mExternalDisplayStates.delete(displayId);
        }

        mInjector.writeLog(FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED,
                DISCONNECTED_STATE, countOfExternalDisplays,
                mIsExternalDisplayUsedForAudio);

        if (DEBUG) {
            Slog.d(TAG, "logStateDisconnected"
                                + " displayId=" + displayId
                                + " countOfExternalDisplays=" + countOfExternalDisplays
                                + " currentState=" + state
                                + " state=" + DISCONNECTED_STATE
                                + " mIsExternalDisplayUsedForAudio="
                                + mIsExternalDisplayUsedForAudio);
        }

        if (countOfExternalDisplays == 1) {
            scheduleDeinit();
        }
    }

    private void logStateMirroring(final int displayId) {
        synchronized (mExternalDisplayStates) {
            final int state = mExternalDisplayStates.get(displayId, DISCONNECTED_STATE);
            if (state == DISCONNECTED_STATE || state == MIRRORING_STATE) {
                return;
            }
            for (var i = 0; i < mExternalDisplayStates.size(); i++) {
                if (mExternalDisplayStates.keyAt(i) != displayId) {
                    continue;
                }
                mExternalDisplayStates.put(displayId, MIRRORING_STATE);
                mInjector.writeLog(FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED,
                        MIRRORING_STATE, i + 1, mIsExternalDisplayUsedForAudio);
                if (DEBUG) {
                    Slog.d(TAG, "logStateMirroring"
                                        + " displayId=" + displayId
                                        + " countOfExternalDisplays=" + (i + 1)
                                        + " currentState=" + state
                                        + " state=" + MIRRORING_STATE
                                        + " mIsExternalDisplayUsedForAudio="
                                        + mIsExternalDisplayUsedForAudio);
                }
            }
        }
    }

    private void logStateExtended(final int displayId) {
        synchronized (mExternalDisplayStates) {
            final int state = mExternalDisplayStates.get(displayId, DISCONNECTED_STATE);
            if (state == DISCONNECTED_STATE || state == EXTENDED_STATE) {
                return;
            }
            for (var i = 0; i < mExternalDisplayStates.size(); i++) {
                if (mExternalDisplayStates.keyAt(i) != displayId) {
                    continue;
                }
                mExternalDisplayStates.put(displayId, EXTENDED_STATE);
                mInjector.writeLog(FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED,
                        EXTENDED_STATE, i + 1, mIsExternalDisplayUsedForAudio);
                if (DEBUG) {
                    Slog.d(TAG, "logStateExtended"
                                        + " displayId=" + displayId
                                        + " countOfExternalDisplays=" + (i + 1)
                                        + " currentState=" + state
                                        + " state=" + EXTENDED_STATE
                                        + " mIsExternalDisplayUsedForAudio="
                                        + mIsExternalDisplayUsedForAudio);
                }
            }
        }
    }

    private void logStateDisabled(final int displayId) {
        synchronized (mExternalDisplayStates) {
            final int state = mExternalDisplayStates.get(displayId, DISCONNECTED_STATE);
            if (state == DISCONNECTED_STATE || state == DISABLED_STATE) {
                return;
            }
            for (var i = 0; i < mExternalDisplayStates.size(); i++) {
                if (mExternalDisplayStates.keyAt(i) != displayId) {
                    continue;
                }
                mExternalDisplayStates.put(displayId, DISABLED_STATE);
                mInjector.writeLog(FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED,
                        DISABLED_STATE, i + 1, mIsExternalDisplayUsedForAudio);
                if (DEBUG) {
                    Slog.d(TAG, "logStateDisabled"
                                        + " displayId=" + displayId
                                        + " countOfExternalDisplays=" + (i + 1)
                                        + " currentState=" + state
                                        + " state=" + DISABLED_STATE
                                        + " mIsExternalDisplayUsedForAudio="
                                        + mIsExternalDisplayUsedForAudio);
                }
            }
        }
    }

    private void logExternalDisplayPresentationStarted(int displayId) {
        final int countOfExternalDisplays, state;
        synchronized (mExternalDisplayStates) {
            state = mExternalDisplayStates.get(displayId, DISCONNECTED_STATE);
            if (state == DISCONNECTED_STATE) {
                return;
            }
            countOfExternalDisplays = mExternalDisplayStates.size();
        }

        final var newState = mInjector.isExtendedDisplayEnabled() ? PRESENTATION_WHILE_EXTENDED
                                     : PRESENTATION_WHILE_MIRRORING;
        mInjector.writeLog(FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED,
                newState, countOfExternalDisplays,
                mIsExternalDisplayUsedForAudio);
        if (DEBUG) {
            Slog.d(TAG, "logExternalDisplayPresentationStarted"
                                + " state=" + state
                                + " newState=" + newState
                                + " mIsExternalDisplayUsedForAudio="
                                + mIsExternalDisplayUsedForAudio);
        }
    }

    private void logExternalDisplayPresentationEnded(int displayId) {
        final int countOfExternalDisplays, state;
        synchronized (mExternalDisplayStates) {
            state = mExternalDisplayStates.get(displayId, DISCONNECTED_STATE);
            if (state == DISCONNECTED_STATE) {
                return;
            }
            countOfExternalDisplays = mExternalDisplayStates.size();
        }

        mInjector.writeLog(FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED,
                PRESENTATION_ENDED, countOfExternalDisplays,
                mIsExternalDisplayUsedForAudio);
        if (DEBUG) {
            Slog.d(TAG, "logExternalDisplayPresentationEnded"
                                + " state=" + state
                                + " countOfExternalDisplays=" + countOfExternalDisplays
                                + " mIsExternalDisplayUsedForAudio="
                                + mIsExternalDisplayUsedForAudio);
        }
    }

    private void logExternalDisplayIdleStarted() {
        synchronized (mExternalDisplayStates) {
            for (var i = 0; i < mExternalDisplayStates.size(); i++) {
                mInjector.writeLog(FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED,
                        KEYGUARD, i + 1, mIsExternalDisplayUsedForAudio);
                if (DEBUG) {
                    final int displayId = mExternalDisplayStates.keyAt(i);
                    final int state = mExternalDisplayStates.get(displayId, DISCONNECTED_STATE);
                    Slog.d(TAG, "logExternalDisplayIdleStarted"
                                        + " displayId=" + displayId
                                        + " currentState=" + state
                                        + " countOfExternalDisplays=" + (i + 1)
                                        + " state=" + KEYGUARD
                                        + " mIsExternalDisplayUsedForAudio="
                                        + mIsExternalDisplayUsedForAudio);
                }
            }
        }
    }

    private void logExternalDisplayIdleEnded() {
        synchronized (mExternalDisplayStates) {
            for (var i = 0; i < mExternalDisplayStates.size(); i++) {
                final int displayId = mExternalDisplayStates.keyAt(i);
                final int state = mExternalDisplayStates.get(displayId, DISCONNECTED_STATE);
                if (state == DISCONNECTED_STATE) {
                    return;
                }
                mInjector.writeLog(FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED,
                        state, i + 1, mIsExternalDisplayUsedForAudio);
                if (DEBUG) {
                    Slog.d(TAG, "logExternalDisplayIdleEnded"
                                        + " displayId=" + displayId
                                        + " state=" + state
                                        + " countOfExternalDisplays=" + (i + 1)
                                        + " mIsExternalDisplayUsedForAudio="
                                        + mIsExternalDisplayUsedForAudio);
                }
            }
        }
    }

    /**
     * Executed within Handler
     */
    private void logStateAfterAudioSinkChanged(boolean enabled) {
        if (mIsExternalDisplayUsedForAudio == enabled) {
            return;
        }
        mIsExternalDisplayUsedForAudio = enabled;
        int countOfExternalDisplays;
        synchronized (mExternalDisplayStates) {
            countOfExternalDisplays = mExternalDisplayStates.size();
        }
        mInjector.writeLog(FrameworkStatsLog.EXTERNAL_DISPLAY_STATE_CHANGED,
                AUDIO_SINK_CHANGED, countOfExternalDisplays,
                mIsExternalDisplayUsedForAudio);
        if (DEBUG) {
            Slog.d(TAG, "logStateAfterAudioSinkChanged"
                                + " countOfExternalDisplays)="
                                + countOfExternalDisplays
                                + " mIsExternalDisplayUsedForAudio="
                                + mIsExternalDisplayUsedForAudio);
        }
    }

    /**
     * Implements necessary functionality for {@link ExternalDisplayStatsService}
     */
    static class Injector {
        @NonNull
        private final Context mContext;
        @NonNull
        private final Handler mHandler;
        @Nullable
        private AudioManager mAudioManager;
        @Nullable
        private PowerManager mPowerManager;

        Injector(@NonNull Context context, @NonNull Handler handler) {
            mContext = context;
            mHandler = handler;
        }

        boolean isExtendedDisplayEnabled() {
            try {
                return 0 != Settings.Global.getInt(
                        mContext.getContentResolver(),
                        DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS, 0);
            } catch (Throwable e) {
                // Some services might not be initialised yet to be able to call getInt
                return false;
            }
        }

        void registerInteractivityReceiver(BroadcastReceiver interactivityReceiver,
                IntentFilter filter) {
            mContext.registerReceiver(interactivityReceiver, filter, /*broadcastPermission=*/ null,
                    mHandler, Context.RECEIVER_NOT_EXPORTED);
        }

        void unregisterInteractivityReceiver(BroadcastReceiver interactivityReceiver) {
            mContext.unregisterReceiver(interactivityReceiver);
        }

        void registerAudioPlaybackCallback(
                AudioPlaybackCallback audioPlaybackCallback) {
            if (mAudioManager == null) {
                mAudioManager = mContext.getSystemService(AudioManager.class);
            }
            if (mAudioManager != null) {
                mAudioManager.registerAudioPlaybackCallback(audioPlaybackCallback, mHandler);
            }
        }

        void unregisterAudioPlaybackCallback(
                AudioPlaybackCallback audioPlaybackCallback) {
            if (mAudioManager == null) {
                mAudioManager = mContext.getSystemService(AudioManager.class);
            }
            if (mAudioManager != null) {
                mAudioManager.unregisterAudioPlaybackCallback(audioPlaybackCallback);
            }
        }

        boolean isInteractive(int displayId) {
            if (mPowerManager == null) {
                mPowerManager = mContext.getSystemService(PowerManager.class);
            }
            // By default it is interactive, unless power manager is initialised and says it is not.
            return mPowerManager == null || mPowerManager.isInteractive(displayId);
        }

        @NonNull
        Handler getHandler() {
            return mHandler;
        }

        void writeLog(int externalDisplayStateChanged, int event, int numberOfDisplays,
                boolean isExternalDisplayUsedForAudio) {
            FrameworkStatsLog.write(externalDisplayStateChanged, event, numberOfDisplays,
                    isExternalDisplayUsedForAudio);
        }
    }
}
