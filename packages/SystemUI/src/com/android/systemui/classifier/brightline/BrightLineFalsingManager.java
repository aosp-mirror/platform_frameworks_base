/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.classifier.brightline;

import static com.android.systemui.classifier.FalsingManagerImpl.FALSING_REMAIN_LOCKED;
import static com.android.systemui.classifier.FalsingManagerImpl.FALSING_SUCCESS;

import android.app.ActivityManager;
import android.hardware.biometrics.BiometricSourceType;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.view.MotionEvent;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.IndentingPrintWriter;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.classifier.Classifier;
import com.android.systemui.dock.DockManager;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.sensors.ProximitySensor;
import com.android.systemui.util.sensors.ThresholdSensor;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * FalsingManager designed to make clear why a touch was rejected.
 */
public class BrightLineFalsingManager implements FalsingManager {

    private static final String TAG = "FalsingManager";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final int RECENT_INFO_LOG_SIZE = 40;
    private static final int RECENT_SWIPE_LOG_SIZE = 20;

    private final FalsingDataProvider mDataProvider;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final ProximitySensor mProximitySensor;
    private final DockManager mDockManager;
    private final StatusBarStateController mStatusBarStateController;
    private boolean mSessionStarted;
    private MetricsLogger mMetricsLogger;
    private int mIsFalseTouchCalls;
    private boolean mShowingAod;
    private boolean mScreenOn;
    private boolean mJustUnlockedWithFace;
    private static final Queue<String> RECENT_INFO_LOG =
            new ArrayDeque<>(RECENT_INFO_LOG_SIZE + 1);
    private static final Queue<DebugSwipeRecord> RECENT_SWIPES =
            new ArrayDeque<>(RECENT_SWIPE_LOG_SIZE + 1);

    private final List<FalsingClassifier> mClassifiers;

    private ThresholdSensor.Listener mSensorEventListener = this::onProximityEvent;

    private final KeyguardUpdateMonitorCallback mKeyguardUpdateCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onBiometricAuthenticated(int userId,
                        BiometricSourceType biometricSourceType,
                        boolean isStrongBiometric) {
                    if (userId == KeyguardUpdateMonitor.getCurrentUser()
                            && biometricSourceType == BiometricSourceType.FACE) {
                        mJustUnlockedWithFace = true;
                    }
                }
            };
    private boolean mPreviousResult = false;

    private StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
        @Override
        public void onStateChanged(int newState) {
            logDebug("StatusBarState=" + StatusBarState.toShortString(newState));
            mState = newState;
            updateSessionActive();
        }
    };
    private int mState;

    public BrightLineFalsingManager(FalsingDataProvider falsingDataProvider,
            KeyguardUpdateMonitor keyguardUpdateMonitor, ProximitySensor proximitySensor,
            DeviceConfigProxy deviceConfigProxy,
            DockManager dockManager, StatusBarStateController statusBarStateController) {
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mDataProvider = falsingDataProvider;
        mProximitySensor = proximitySensor;
        mDockManager = dockManager;
        mStatusBarStateController = statusBarStateController;
        mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateCallback);
        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mState = mStatusBarStateController.getState();

        mMetricsLogger = new MetricsLogger();
        mClassifiers = new ArrayList<>();
        DistanceClassifier distanceClassifier =
                new DistanceClassifier(mDataProvider, deviceConfigProxy);
        ProximityClassifier proximityClassifier =
                new ProximityClassifier(distanceClassifier, mDataProvider, deviceConfigProxy);
        mClassifiers.add(new PointerCountClassifier(mDataProvider));
        mClassifiers.add(new TypeClassifier(mDataProvider));
        mClassifiers.add(new DiagonalClassifier(mDataProvider, deviceConfigProxy));
        mClassifiers.add(distanceClassifier);
        mClassifiers.add(proximityClassifier);
        mClassifiers.add(new ZigZagClassifier(mDataProvider, deviceConfigProxy));
    }

    private void registerSensors() {
        if (!mDataProvider.isWirelessCharging()) {
            mProximitySensor.register(mSensorEventListener);
        }
    }

    private void unregisterSensors() {
        mProximitySensor.unregister(mSensorEventListener);
    }

    private void sessionStart() {
        if (!mSessionStarted && shouldSessionBeActive()) {
            logDebug("Starting Session");
            mSessionStarted = true;
            mJustUnlockedWithFace = false;
            registerSensors();
            mClassifiers.forEach(FalsingClassifier::onSessionStarted);
        }
    }

    private void sessionEnd() {
        if (mSessionStarted) {
            logDebug("Ending Session");
            mSessionStarted = false;
            unregisterSensors();
            mDataProvider.onSessionEnd();
            mClassifiers.forEach(FalsingClassifier::onSessionEnded);
            if (mIsFalseTouchCalls != 0) {
                mMetricsLogger.histogram(FALSING_REMAIN_LOCKED, mIsFalseTouchCalls);
                mIsFalseTouchCalls = 0;
            }
        }
    }


    private void updateSessionActive() {
        if (shouldSessionBeActive()) {
            sessionStart();
        } else {
            sessionEnd();
        }
    }

    private boolean shouldSessionBeActive() {
        return mScreenOn && (mState == StatusBarState.KEYGUARD) && !mShowingAod;
    }

    private void updateInteractionType(@Classifier.InteractionType int type) {
        logDebug("InteractionType: " + type);
        mDataProvider.setInteractionType(type);
    }

    @Override
    public boolean isClassifierEnabled() {
        return true;
    }

    @Override
    public boolean isFalseTouch(@Classifier.InteractionType int interactionType) {
        mDataProvider.setInteractionType(interactionType);
        if (!mDataProvider.isDirty()) {
            return mPreviousResult;
        }

        mPreviousResult = !ActivityManager.isRunningInUserTestHarness() && !mJustUnlockedWithFace
                && !mDockManager.isDocked() && mClassifiers.stream().anyMatch(falsingClassifier -> {
                    boolean result = falsingClassifier.isFalseTouch();
                    if (result) {
                        logInfo(String.format(
                                (Locale) null,
                                "{classifier=%s, interactionType=%d}",
                                falsingClassifier.getClass().getName(),
                                mDataProvider.getInteractionType()));
                        String reason = falsingClassifier.getReason();
                        if (reason != null) {
                            logInfo(reason);
                        }
                    } else {
                        logDebug(falsingClassifier.getClass().getName() + ": false");
                    }
                    return result;
                });

        logDebug("Is false touch? " + mPreviousResult);

        if (Build.IS_ENG || Build.IS_USERDEBUG) {
            // Copy motion events, as the passed in list gets emptied out elsewhere in the code.
            RECENT_SWIPES.add(new DebugSwipeRecord(
                    mPreviousResult,
                    mDataProvider.getInteractionType(),
                    mDataProvider.getRecentMotionEvents().stream().map(
                            motionEvent -> new XYDt(
                                    (int) motionEvent.getX(),
                                    (int) motionEvent.getY(),
                                    (int) (motionEvent.getEventTime() - motionEvent.getDownTime())))
                            .collect(Collectors.toList())));
            while (RECENT_SWIPES.size() > RECENT_INFO_LOG_SIZE) {
                DebugSwipeRecord record = RECENT_SWIPES.remove();
            }

        }

        return mPreviousResult;
    }

    @Override
    public void onTouchEvent(MotionEvent motionEvent, int width, int height) {
        // TODO: some of these classifiers might allow us to abort early, meaning we don't have to
        // make these calls.
        mDataProvider.onMotionEvent(motionEvent);
        mClassifiers.forEach((classifier) -> classifier.onTouchEvent(motionEvent));
    }

    private void onProximityEvent(ThresholdSensor.ThresholdSensorEvent proximityEvent) {
        // TODO: some of these classifiers might allow us to abort early, meaning we don't have to
        // make these calls.
        mClassifiers.forEach((classifier) -> classifier.onProximityEvent(proximityEvent));
    }

    @Override
    public void onSuccessfulUnlock() {
        if (mIsFalseTouchCalls != 0) {
            mMetricsLogger.histogram(FALSING_SUCCESS, mIsFalseTouchCalls);
            mIsFalseTouchCalls = 0;
        }
        sessionEnd();
    }

    @Override
    public void onNotificationActive() {
    }

    @Override
    public void setShowingAod(boolean showingAod) {
        mShowingAod = showingAod;
        updateSessionActive();
    }

    @Override
    public void onNotificatonStartDraggingDown() {
        updateInteractionType(Classifier.NOTIFICATION_DRAG_DOWN);
    }

    @Override
    public boolean isUnlockingDisabled() {
        return false;
    }


    @Override
    public void onNotificatonStopDraggingDown() {
    }

    @Override
    public void setNotificationExpanded() {
    }

    @Override
    public void onQsDown() {
        updateInteractionType(Classifier.QUICK_SETTINGS);
    }

    @Override
    public void setQsExpanded(boolean expanded) {
        if (expanded) {
            unregisterSensors();
        } else if (mSessionStarted) {
            registerSensors();
        }
    }

    @Override
    public boolean shouldEnforceBouncer() {
        return false;
    }

    @Override
    public void onTrackingStarted(boolean secure) {
        updateInteractionType(secure ? Classifier.BOUNCER_UNLOCK : Classifier.UNLOCK);
    }

    @Override
    public void onTrackingStopped() {
    }

    @Override
    public void onLeftAffordanceOn() {
    }

    @Override
    public void onCameraOn() {
    }

    @Override
    public void onAffordanceSwipingStarted(boolean rightCorner) {
        updateInteractionType(
                rightCorner ? Classifier.RIGHT_AFFORDANCE : Classifier.LEFT_AFFORDANCE);
    }

    @Override
    public void onAffordanceSwipingAborted() {
    }

    @Override
    public void onStartExpandingFromPulse() {
        updateInteractionType(Classifier.PULSE_EXPAND);
    }

    @Override
    public void onExpansionFromPulseStopped() {
    }

    @Override
    public Uri reportRejectedTouch() {
        return null;
    }

    @Override
    public void onScreenOnFromTouch() {
        onScreenTurningOn();
    }

    @Override
    public boolean isReportingEnabled() {
        return false;
    }

    @Override
    public void onUnlockHintStarted() {
    }

    @Override
    public void onCameraHintStarted() {
    }

    @Override
    public void onLeftAffordanceHintStarted() {
    }

    @Override
    public void onScreenTurningOn() {
        mScreenOn = true;
        updateSessionActive();
    }

    @Override
    public void onScreenOff() {
        mScreenOn = false;
        updateSessionActive();
    }


    @Override
    public void onNotificationStopDismissing() {
    }

    @Override
    public void onNotificationDismissed() {
    }

    @Override
    public void onNotificationStartDismissing() {
        updateInteractionType(Classifier.NOTIFICATION_DISMISS);
    }

    @Override
    public void onNotificationDoubleTap(boolean b, float v, float v1) {
    }

    @Override
    public void onBouncerShown() {
        unregisterSensors();
    }

    @Override
    public void onBouncerHidden() {
        if (mSessionStarted) {
            registerSensors();
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        ipw.println("BRIGHTLINE FALSING MANAGER");
        ipw.print("classifierEnabled=");
        ipw.println(isClassifierEnabled() ? 1 : 0);
        ipw.print("mJustUnlockedWithFace=");
        ipw.println(mJustUnlockedWithFace ? 1 : 0);
        ipw.print("isDocked=");
        ipw.println(mDockManager.isDocked() ? 1 : 0);
        ipw.print("width=");
        ipw.println(mDataProvider.getWidthPixels());
        ipw.print("height=");
        ipw.println(mDataProvider.getHeightPixels());
        ipw.println();
        if (RECENT_SWIPES.size() != 0) {
            ipw.println("Recent swipes:");
            ipw.increaseIndent();
            for (DebugSwipeRecord record : RECENT_SWIPES) {
                ipw.println(record.getString());
                ipw.println();
            }
            ipw.decreaseIndent();
        } else {
            ipw.println("No recent swipes");
        }
        ipw.println();
        ipw.println("Recent falsing info:");
        ipw.increaseIndent();
        for (String msg : RECENT_INFO_LOG) {
            ipw.println(msg);
        }
        ipw.println();
    }

    @Override
    public void cleanup() {
        unregisterSensors();
        mKeyguardUpdateMonitor.removeCallback(mKeyguardUpdateCallback);
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
    }

    static void logDebug(String msg) {
        logDebug(msg, null);
    }

    static void logDebug(String msg, Throwable throwable) {
        if (DEBUG) {
            Log.d(TAG, msg, throwable);
        }
    }

    static void logInfo(String msg) {
        Log.i(TAG, msg);
        RECENT_INFO_LOG.add(msg);
        while (RECENT_INFO_LOG.size() > RECENT_INFO_LOG_SIZE) {
            RECENT_INFO_LOG.remove();
        }
    }

    static void logError(String msg) {
        Log.e(TAG, msg);
    }

    private static class DebugSwipeRecord {
        private static final byte VERSION = 1;  // opaque version number indicating format of data.
        private final boolean mIsFalse;
        private final int mInteractionType;
        private final List<XYDt> mRecentMotionEvents;

        DebugSwipeRecord(boolean isFalse, int interactionType,
                List<XYDt> recentMotionEvents) {
            mIsFalse = isFalse;
            mInteractionType = interactionType;
            mRecentMotionEvents = recentMotionEvents;
        }

        String getString() {
            StringJoiner sj = new StringJoiner(",");
            sj.add(Integer.toString(VERSION))
                    .add(mIsFalse ? "1" : "0")
                    .add(Integer.toString(mInteractionType));
            for (XYDt event : mRecentMotionEvents) {
                sj.add(event.toString());
            }
            return sj.toString();
        }
    }

    private static class XYDt {
        private final int mX;
        private final int mY;
        private final int mDT;

        XYDt(int x, int y, int dT) {
            mX = x;
            mY = y;
            mDT = dT;
        }

        @Override
        public String toString() {
            return mX + "," + mY + "," + mDT;
        }
    }
}
