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

package com.android.systemui.classifier;

import static com.android.systemui.classifier.FalsingManagerProxy.FALSING_SUCCESS;
import static com.android.systemui.classifier.FalsingModule.BRIGHT_LINE_GESTURE_CLASSIFERS;

import android.net.Uri;
import android.os.Build;
import android.util.IndentingPrintWriter;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.classifier.FalsingDataProvider.SessionListener;
import com.android.systemui.dagger.qualifiers.TestHarness;
import com.android.systemui.dock.DockManager;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.util.sensors.ThresholdSensor;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * FalsingManager designed to make clear why a touch was rejected.
 */
public class BrightLineFalsingManager implements FalsingManager {

    private static final String TAG = "FalsingManager";
    public static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final int RECENT_INFO_LOG_SIZE = 40;
    private static final int RECENT_SWIPE_LOG_SIZE = 20;
    private static final double TAP_CONFIDENCE_THRESHOLD = 0.7;

    private final FalsingDataProvider mDataProvider;
    private final DockManager mDockManager;
    private final SingleTapClassifier mSingleTapClassifier;
    private final DoubleTapClassifier mDoubleTapClassifier;
    private final HistoryTracker mHistoryTracker;
    private final boolean mTestHarness;
    private final MetricsLogger mMetricsLogger;
    private int mIsFalseTouchCalls;
    private static final Queue<String> RECENT_INFO_LOG =
            new ArrayDeque<>(RECENT_INFO_LOG_SIZE + 1);
    private static final Queue<DebugSwipeRecord> RECENT_SWIPES =
            new ArrayDeque<>(RECENT_SWIPE_LOG_SIZE + 1);

    private final Collection<FalsingClassifier> mClassifiers;

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

    private final FalsingDataProvider.GestureCompleteListener mGestureCompleteListener =
            new FalsingDataProvider.GestureCompleteListener() {
                @Override
        public void onGestureComplete(long completionTimeMs) {
            if (mPriorResults != null) {
                mHistoryTracker.addResults(mPriorResults, completionTimeMs);
                mPriorResults = null;
            } else {
                // Gestures that were not classified get treated as a false.
                mHistoryTracker.addResults(
                        Collections.singleton(
                                FalsingClassifier.Result.falsed(.8, "unclassified")),
                        completionTimeMs);
            }
        }
    };

    private Collection<FalsingClassifier.Result> mPriorResults;

    @Inject
    public BrightLineFalsingManager(FalsingDataProvider falsingDataProvider,
            DockManager dockManager, MetricsLogger metricsLogger,
            @Named(BRIGHT_LINE_GESTURE_CLASSIFERS) Set<FalsingClassifier> classifiers,
            SingleTapClassifier singleTapClassifier, DoubleTapClassifier doubleTapClassifier,
            HistoryTracker historyTracker, @TestHarness boolean testHarness) {
        mDataProvider = falsingDataProvider;
        mDockManager = dockManager;
        mMetricsLogger = metricsLogger;
        mClassifiers = classifiers;
        mSingleTapClassifier = singleTapClassifier;
        mDoubleTapClassifier = doubleTapClassifier;
        mHistoryTracker = historyTracker;
        mTestHarness = testHarness;

        mDataProvider.addSessionListener(mSessionListener);
        mDataProvider.addGestureCompleteListener(mGestureCompleteListener);
    }

    @Override
    public boolean isClassifierEnabled() {
        return true;
    }

    @Override
    public boolean isFalseTouch(@Classifier.InteractionType int interactionType) {
        boolean result;

        mDataProvider.setInteractionType(interactionType);

        if (!mTestHarness && !mDataProvider.isJustUnlockedWithFace() && !mDockManager.isDocked()) {
            Stream<FalsingClassifier.Result> results =
                    mClassifiers.stream().map(falsingClassifier -> {
                        FalsingClassifier.Result classifierResult =
                                falsingClassifier.classifyGesture(
                                        mHistoryTracker.falseBelief(),
                                        mHistoryTracker.falseConfidence());
                        if (classifierResult.isFalse()) {
                            logInfo(String.format(
                                    (Locale) null,
                                    "{classifier=%s, interactionType=%d}",
                                    falsingClassifier.getClass().getName(),
                                    mDataProvider.getInteractionType()));
                            String reason = classifierResult.getReason();
                            if (reason != null) {
                                logInfo(reason);
                            }
                        } else {
                            logDebug(falsingClassifier.getClass().getName() + ": false");
                        }
                        return classifierResult;
                    });
            mPriorResults = new ArrayList<>();
            final boolean[] localResult = {false};
            results.forEach(classifierResult -> {
                localResult[0] |= classifierResult.isFalse();
                mPriorResults.add(classifierResult);
            });
            result = localResult[0];
        } else {
            result = false;
            mPriorResults = Collections.singleton(FalsingClassifier.Result.passed(1));
        }

        logDebug("Is false touch? " + result);

        if (Build.IS_ENG || Build.IS_USERDEBUG) {
            // Copy motion events, as the passed in list gets emptied out elsewhere in the code.
            RECENT_SWIPES.add(new DebugSwipeRecord(
                    result,
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

        return result;
    }

    @Override
    public boolean isFalseTap(boolean robustCheck, double falsePenalty) {
        FalsingClassifier.Result singleTapResult =
                mSingleTapClassifier.isTap(mDataProvider.getRecentMotionEvents());
        mPriorResults = Collections.singleton(singleTapResult);
        if (singleTapResult.isFalse()) {
            logInfo(String.format(
                    (Locale) null, "{classifier=%s}", mSingleTapClassifier.getClass().getName()));
            String reason = singleTapResult.getReason();
            if (reason != null) {
                logInfo(reason);
            }
            return true;
        }

        if (robustCheck) {
            if (mDataProvider.isJustUnlockedWithFace()) {
                // Immediately pass if a face is detected.
                mPriorResults = Collections.singleton(FalsingClassifier.Result.passed(1));
                return false;
            } else if (!isFalseDoubleTap()) {
                // We must check double tapping before other heuristics. This is because
                // the double tap will fail if there's only been one tap. We don't want that
                // failure to be recorded in mPriorResults.
                return false;
            } else if (mHistoryTracker.falseBelief() > TAP_CONFIDENCE_THRESHOLD) {
                mPriorResults = Collections.singleton(
                        FalsingClassifier.Result.falsed(0, "bad history"));
                return true;
            } else {
                mPriorResults = Collections.singleton(FalsingClassifier.Result.passed(0.1));
                return false;
            }
        }

        return false;
    }

    @Override
    public boolean isFalseDoubleTap() {
        FalsingClassifier.Result result = mDoubleTapClassifier.classifyGesture();
        mPriorResults = Collections.singleton(result);
        if (result.isFalse()) {
            logInfo(String.format(
                    (Locale) null, "{classifier=%s}", mDoubleTapClassifier.getClass().getName()));
            String reason = result.getReason();
            if (reason != null) {
                logInfo(reason);
            }
        }
        return result.isFalse();
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
        mDataProvider.removeGestureCompleteListener(mGestureCompleteListener);
        mClassifiers.forEach(FalsingClassifier::cleanup);
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
