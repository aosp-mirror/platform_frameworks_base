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

import static com.android.systemui.classifier.FalsingManagerProxy.FALSING_SUCCESS;

import android.app.ActivityManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.classifier.Classifier;
import com.android.systemui.classifier.FalsingDataProvider;
import com.android.systemui.classifier.FalsingDataProvider.SessionListener;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dock.DockManager;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.statusbar.phone.NotificationTapHelper;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.sensors.ThresholdSensor;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import javax.inject.Inject;

/**
 * FalsingManager designed to make clear why a touch was rejected.
 */
public class BrightLineFalsingManager implements FalsingManager {

    private static final String TAG = "FalsingManager";
    public static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final int RECENT_INFO_LOG_SIZE = 40;
    private static final int RECENT_SWIPE_LOG_SIZE = 20;

    private final FalsingDataProvider mDataProvider;
    private final DockManager mDockManager;
    private final SingleTapClassifier mSingleTapClassifier;
    private final DoubleTapClassifier mDoubleTapClassifier;
    private final MetricsLogger mMetricsLogger;
    private int mIsFalseTouchCalls;
    private static final Queue<String> RECENT_INFO_LOG =
            new ArrayDeque<>(RECENT_INFO_LOG_SIZE + 1);
    private static final Queue<DebugSwipeRecord> RECENT_SWIPES =
            new ArrayDeque<>(RECENT_SWIPE_LOG_SIZE + 1);

    private final List<FalsingClassifier> mClassifiers;

    private final SessionListener mSessionListener = new SessionListener() {
        @Override
        public void onSessionEnded() {
            mClassifiers.forEach(FalsingClassifier::onSessionEnded);
        }

        @Override
        public void onSessionStarted() {
            mClassifiers.forEach(FalsingClassifier::onSessionStarted);
        }
    };

    private boolean mPreviousResult = false;

    @Inject
    public BrightLineFalsingManager(FalsingDataProvider falsingDataProvider,
            DeviceConfigProxy deviceConfigProxy, @Main Resources resources,
            ViewConfiguration viewConfiguration, DockManager dockManager) {
        mDataProvider = falsingDataProvider;
        mDockManager = dockManager;

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

        mSingleTapClassifier = new SingleTapClassifier(
                mDataProvider, viewConfiguration.getScaledTouchSlop());
        mDoubleTapClassifier = new DoubleTapClassifier(mDataProvider, mSingleTapClassifier,
                resources.getDimension(R.dimen.double_tap_slop),
                NotificationTapHelper.DOUBLE_TAP_TIMEOUT_MS);

        mDataProvider.addSessionListener(mSessionListener);
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

        mPreviousResult = !ActivityManager.isRunningInUserTestHarness()
                && !mDataProvider.isJustUnlockedWithFace() && !mDockManager.isDocked()
                && mClassifiers.stream().anyMatch(falsingClassifier -> {
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
                RECENT_SWIPES.remove();
            }
        }

        return mPreviousResult;
    }

    @Override
    public boolean isFalseTap(boolean robustCheck) {
        if (!mSingleTapClassifier.isTap(mDataProvider.getRecentMotionEvents())) {
            logInfo(String.format(
                    (Locale) null, "{classifier=%s}", mSingleTapClassifier.getClass().getName()));
            String reason = mSingleTapClassifier.getReason();
            if (reason != null) {
                logInfo(reason);
            }
            return true;
        }

        // TODO(b/172655679): we always reject single-taps when doing a robust check for now.
        return robustCheck;
    }

    @Override
    public boolean isFalseDoubleTap() {
        boolean result = mDoubleTapClassifier.isFalseTouch();
        if (result) {
            logInfo(String.format(
                    (Locale) null, "{classifier=%s}", mDoubleTapClassifier.getClass().getName()));
            String reason = mDoubleTapClassifier.getReason();
            if (reason != null) {
                logInfo(reason);
            }
        }
        return result;
    }

    @Override
    public void onTouchEvent(MotionEvent motionEvent, int width, int height) {
        // TODO: some of these classifiers might allow us to abort early, meaning we don't have to
        // make these calls.
        mClassifiers.forEach((classifier) -> classifier.onTouchEvent(motionEvent));
    }

    @Override
    public void onProximityEvent(ThresholdSensor.ThresholdSensorEvent proximityEvent) {
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
    }

    @Override
    public boolean isUnlockingDisabled() {
        return false;
    }

    @Override
    public boolean shouldEnforceBouncer() {
        return false;
    }

    @Override
    public Uri reportRejectedTouch() {
        return null;
    }

    @Override
    public boolean isReportingEnabled() {
        return false;
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        ipw.println("BRIGHTLINE FALSING MANAGER");
        ipw.print("classifierEnabled=");
        ipw.println(isClassifierEnabled() ? 1 : 0);
        ipw.print("mJustUnlockedWithFace=");
        ipw.println(mDataProvider.isJustUnlockedWithFace() ? 1 : 0);
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
        mDataProvider.removeSessionListener(mSessionListener);
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
