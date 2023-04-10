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

import static com.android.systemui.classifier.Classifier.BACK_GESTURE;
import static com.android.systemui.classifier.Classifier.GENERIC;
import static com.android.systemui.classifier.Classifier.MEDIA_SEEKBAR;
import static com.android.systemui.classifier.FalsingManagerProxy.FALSING_SUCCESS;
import static com.android.systemui.classifier.FalsingModule.BRIGHT_LINE_GESTURE_CLASSIFERS;

import android.net.Uri;
import android.os.Build;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.NonNull;

import com.android.internal.logging.MetricsLogger;
import com.android.systemui.classifier.FalsingDataProvider.SessionListener;
import com.android.systemui.classifier.HistoryTracker.BeliefListener;
import com.android.systemui.dagger.qualifiers.TestHarness;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

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
    private static final double FALSE_BELIEF_THRESHOLD = 0.9;

    private final FalsingDataProvider mDataProvider;
    private final LongTapClassifier mLongTapClassifier;
    private final SingleTapClassifier mSingleTapClassifier;
    private final DoubleTapClassifier mDoubleTapClassifier;
    private final HistoryTracker mHistoryTracker;
    private final KeyguardStateController mKeyguardStateController;
    private AccessibilityManager mAccessibilityManager;
    private final boolean mTestHarness;
    private final MetricsLogger mMetricsLogger;
    private int mIsFalseTouchCalls;
    private FeatureFlags mFeatureFlags;
    private static final Queue<String> RECENT_INFO_LOG =
            new ArrayDeque<>(RECENT_INFO_LOG_SIZE + 1);
    private static final Queue<DebugSwipeRecord> RECENT_SWIPES =
            new ArrayDeque<>(RECENT_SWIPE_LOG_SIZE + 1);

    private final Collection<FalsingClassifier> mClassifiers;
    private final List<FalsingBeliefListener> mFalsingBeliefListeners = new ArrayList<>();
    private List<FalsingTapListener> mFalsingTapListeners = new ArrayList<>();
    private ProximityEvent mLastProximityEvent;

    private boolean mDestroyed;

    private final SessionListener mSessionListener = new SessionListener() {
        @Override
        public void onSessionEnded() {
            mLastProximityEvent = null;
            mClassifiers.forEach(FalsingClassifier::onSessionEnded);
        }

        @Override
        public void onSessionStarted() {
            mClassifiers.forEach(FalsingClassifier::onSessionStarted);
        }
    };

    private final BeliefListener mBeliefListener = new BeliefListener() {
        @Override
        public void onBeliefChanged(double belief) {
            logInfo(String.format(
                    "{belief=%s confidence=%s}",
                    mHistoryTracker.falseBelief(),
                    mHistoryTracker.falseConfidence()));
            if (belief > FALSE_BELIEF_THRESHOLD) {
                mFalsingBeliefListeners.forEach(FalsingBeliefListener::onFalse);
                logInfo("Triggering False Event (Threshold: " + FALSE_BELIEF_THRESHOLD + ")");
            }
        }
    };

    private final FalsingDataProvider.GestureFinalizedListener mGestureFinalizedListener =
            new FalsingDataProvider.GestureFinalizedListener() {
                @Override
                public void onGestureFinalized(long completionTimeMs) {
                    if (mPriorResults != null) {
                        boolean boolResult = mPriorResults.stream().anyMatch(
                                FalsingClassifier.Result::isFalse);

                        mPriorResults.forEach(result -> {
                            if (result.isFalse()) {
                                String reason = result.getReason();
                                if (reason != null) {
                                    logInfo(reason);
                                }
                            }
                        });

                        if (Build.IS_ENG || Build.IS_USERDEBUG) {
                            // Copy motion events, as the results returned by
                            // #getRecentMotionEvents are recycled elsewhere.
                            RECENT_SWIPES.add(new DebugSwipeRecord(
                                    boolResult,
                                    mPriorInteractionType,
                                    mDataProvider.getRecentMotionEvents().stream().map(
                                            motionEvent -> new XYDt(
                                                    (int) motionEvent.getX(),
                                                    (int) motionEvent.getY(),
                                                    (int) (motionEvent.getEventTime()
                                                            - motionEvent.getDownTime())))
                                            .collect(Collectors.toList())));
                            while (RECENT_SWIPES.size() > RECENT_INFO_LOG_SIZE) {
                                RECENT_SWIPES.remove();
                            }
                        }


                        mHistoryTracker.addResults(mPriorResults, completionTimeMs);
                        mPriorResults = null;
                        mPriorInteractionType = Classifier.GENERIC;
                    } else {
                        // Gestures that were not classified get treated as a false.
                        // Gestures that look like simple taps are less likely to be false
                        // than swipes. They may simply be mis-clicks.
                        double penalty = mSingleTapClassifier.isTap(
                                mDataProvider.getRecentMotionEvents(), 0).isFalse()
                                ? 0.7 : 0.8;
                        mHistoryTracker.addResults(
                                Collections.singleton(
                                        FalsingClassifier.Result.falsed(
                                                penalty, getClass().getSimpleName(),
                                                "unclassified")),
                                completionTimeMs);
                    }
                }
            };

    private Collection<FalsingClassifier.Result> mPriorResults;
    private @Classifier.InteractionType int mPriorInteractionType = Classifier.GENERIC;

    @Inject
    public BrightLineFalsingManager(
            FalsingDataProvider falsingDataProvider,
            MetricsLogger metricsLogger,
            @Named(BRIGHT_LINE_GESTURE_CLASSIFERS) Set<FalsingClassifier> classifiers,
            SingleTapClassifier singleTapClassifier, LongTapClassifier longTapClassifier,
            DoubleTapClassifier doubleTapClassifier, HistoryTracker historyTracker,
            KeyguardStateController keyguardStateController,
            AccessibilityManager accessibilityManager,
            @TestHarness boolean testHarness,
            FeatureFlags featureFlags) {
        mDataProvider = falsingDataProvider;
        mMetricsLogger = metricsLogger;
        mClassifiers = classifiers;
        mSingleTapClassifier = singleTapClassifier;
        mLongTapClassifier = longTapClassifier;
        mDoubleTapClassifier = doubleTapClassifier;
        mHistoryTracker = historyTracker;
        mKeyguardStateController = keyguardStateController;
        mAccessibilityManager = accessibilityManager;
        mTestHarness = testHarness;
        mFeatureFlags = featureFlags;

        mDataProvider.addSessionListener(mSessionListener);
        mDataProvider.addGestureCompleteListener(mGestureFinalizedListener);
        mHistoryTracker.addBeliefListener(mBeliefListener);
    }

    @Override
    public boolean isClassifierEnabled() {
        return true;
    }

    @Override
    public boolean isFalseTouch(@Classifier.InteractionType int interactionType) {
        checkDestroyed();

        mPriorInteractionType = interactionType;
        if (skipFalsing(interactionType)) {
            mPriorResults = getPassedResult(1);
            logDebug("Skipped falsing");
            return false;
        }

        final boolean[] localResult = {false};
        mPriorResults = mClassifiers.stream().map(falsingClassifier -> {
            FalsingClassifier.Result r = falsingClassifier.classifyGesture(
                    interactionType,
                    mHistoryTracker.falseBelief(),
                    mHistoryTracker.falseConfidence());
            localResult[0] |= r.isFalse();

            return r;
        }).collect(Collectors.toList());

        // check for false tap if it is a seekbar interaction
        if (interactionType == MEDIA_SEEKBAR) {
            localResult[0] &= isFalseTap(mFeatureFlags.isEnabled(Flags.MEDIA_FALSING_PENALTY)
                    ? FalsingManager.MODERATE_PENALTY : FalsingManager.LOW_PENALTY);
        }

        logDebug("False Gesture (type: " + interactionType + "): " + localResult[0]);

        return localResult[0];
    }

    @Override
    public boolean isSimpleTap() {
        checkDestroyed();

        FalsingClassifier.Result result = mSingleTapClassifier.isTap(
                mDataProvider.getRecentMotionEvents(), 0);
        mPriorResults = Collections.singleton(result);

        return !result.isFalse();
    }

    private void checkDestroyed() {
        if (mDestroyed) {
            Log.wtf(TAG, "Tried to use FalsingManager after being destroyed!");
        }
    }

    @Override
    public boolean isFalseTap(@Penalty int penalty) {
        checkDestroyed();

        if (skipFalsing(GENERIC)) {
            mPriorResults = getPassedResult(1);
            logDebug("Skipped falsing");
            return false;
        }

        double falsePenalty = 0;
        switch(penalty) {
            case NO_PENALTY:
                falsePenalty = 0;
                break;
            case LOW_PENALTY:
                falsePenalty = 0.1;
                break;
            case MODERATE_PENALTY:
                falsePenalty = 0.3;
                break;
            case HIGH_PENALTY:
                falsePenalty = 0.6;
                break;
        }

        FalsingClassifier.Result singleTapResult =
                mSingleTapClassifier.isTap(mDataProvider.getRecentMotionEvents().isEmpty()
                        ? mDataProvider.getPriorMotionEvents()
                        : mDataProvider.getRecentMotionEvents(), falsePenalty);
        mPriorResults = Collections.singleton(singleTapResult);

        if (!singleTapResult.isFalse()) {
            if (mDataProvider.isJustUnlockedWithFace()) {
                // Immediately pass if a face is detected.
                mPriorResults = getPassedResult(1);
                logDebug("False Single Tap: false (face detected)");
                return false;
            } else if (!isFalseDoubleTap()) {
                // We must check double tapping before other heuristics. This is because
                // the double tap will fail if there's only been one tap. We don't want that
                // failure to be recorded in mPriorResults.
                logDebug("False Single Tap: false (double tapped)");
                return false;
            } else if (mHistoryTracker.falseBelief() > TAP_CONFIDENCE_THRESHOLD) {
                mPriorResults = Collections.singleton(
                        FalsingClassifier.Result.falsed(
                                0, getClass().getSimpleName(), "bad history"));
                logDebug("False Single Tap: true (bad history)");
                mFalsingTapListeners.forEach(FalsingTapListener::onAdditionalTapRequired);
                return true;
            } else {
                mPriorResults = getPassedResult(0.1);
                logDebug("False Single Tap: false (default)");
                return false;
            }

        } else {
            logDebug("False Single Tap: " + singleTapResult.isFalse() + " (simple)");
            return singleTapResult.isFalse();
        }

    }

    @Override
    public boolean isFalseLongTap(@Penalty int penalty) {
        if (!mFeatureFlags.isEnabled(Flags.FALSING_FOR_LONG_TAPS)) {
            return false;
        }

        checkDestroyed();

        if (skipFalsing(GENERIC)) {
            mPriorResults = getPassedResult(1);
            logDebug("Skipped falsing");
            return false;
        }

        double falsePenalty = 0;
        switch(penalty) {
            case NO_PENALTY:
                falsePenalty = 0;
                break;
            case LOW_PENALTY:
                falsePenalty = 0.1;
                break;
            case MODERATE_PENALTY:
                falsePenalty = 0.3;
                break;
            case HIGH_PENALTY:
                falsePenalty = 0.6;
                break;
        }

        FalsingClassifier.Result longTapResult =
                mLongTapClassifier.isTap(mDataProvider.getRecentMotionEvents().isEmpty()
                        ? mDataProvider.getPriorMotionEvents()
                        : mDataProvider.getRecentMotionEvents(), falsePenalty);
        mPriorResults = Collections.singleton(longTapResult);

        if (!longTapResult.isFalse()) {
            if (mDataProvider.isJustUnlockedWithFace()) {
                // Immediately pass if a face is detected.
                mPriorResults = getPassedResult(1);
                logDebug("False Long Tap: false (face detected)");
            } else {
                mPriorResults = getPassedResult(0.1);
                logDebug("False Long Tap: false (default)");
            }
            return false;
        } else {
            logDebug("False Long Tap: " + longTapResult.isFalse() + " (simple)");
            return longTapResult.isFalse();
        }
    }

    @Override
    public boolean isFalseDoubleTap() {
        checkDestroyed();

        if (skipFalsing(GENERIC)) {
            mPriorResults = getPassedResult(1);
            logDebug("Skipped falsing");
            return false;
        }

        FalsingClassifier.Result result = mDoubleTapClassifier.classifyGesture(
                Classifier.GENERIC,
                mHistoryTracker.falseBelief(),
                mHistoryTracker.falseConfidence());
        mPriorResults = Collections.singleton(result);
        logDebug("False Double Tap: " + result.isFalse() + " reason=" + result.getReason());
        return result.isFalse();
    }

    private boolean skipFalsing(@Classifier.InteractionType  int interactionType) {
        return interactionType == BACK_GESTURE
                || !mKeyguardStateController.isShowing()
                || mTestHarness
                || mDataProvider.isJustUnlockedWithFace()
                || mDataProvider.isDocked()
                || mAccessibilityManager.isTouchExplorationEnabled()
                || mDataProvider.isA11yAction()
                || (mFeatureFlags.isEnabled(Flags.FALSING_OFF_FOR_UNFOLDED)
                    && mDataProvider.isUnfolded());
    }

    @Override
    public void onProximityEvent(ProximityEvent proximityEvent) {
        // TODO: some of these classifiers might allow us to abort early, meaning we don't have to
        // make these calls.
        mLastProximityEvent = proximityEvent;
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
    public boolean isProximityNear() {
        return mLastProximityEvent != null && mLastProximityEvent.getCovered();
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
    public void addFalsingBeliefListener(FalsingBeliefListener listener) {
        mFalsingBeliefListeners.add(listener);
    }

    @Override
    public void removeFalsingBeliefListener(FalsingBeliefListener listener) {
        mFalsingBeliefListeners.remove(listener);
    }

    @Override
    public void addTapListener(FalsingTapListener listener) {
        mFalsingTapListeners.add(listener);
    }

    @Override
    public void removeTapListener(FalsingTapListener listener) {
        mFalsingTapListeners.remove(listener);
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        ipw.println("BRIGHTLINE FALSING MANAGER");
        ipw.print("classifierEnabled=");
        ipw.println(isClassifierEnabled() ? 1 : 0);
        ipw.print("mJustUnlockedWithFace=");
        ipw.println(mDataProvider.isJustUnlockedWithFace() ? 1 : 0);
        ipw.print("isDocked=");
        ipw.println(mDataProvider.isDocked() ? 1 : 0);
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
    public void cleanupInternal() {
        mDestroyed = true;
        mDataProvider.removeSessionListener(mSessionListener);
        mDataProvider.removeGestureCompleteListener(mGestureFinalizedListener);
        mClassifiers.forEach(FalsingClassifier::cleanup);
        mFalsingBeliefListeners.clear();
        mHistoryTracker.removeBeliefListener(mBeliefListener);
    }

    private static Collection<FalsingClassifier.Result> getPassedResult(double confidence) {
        return Collections.singleton(FalsingClassifier.Result.passed(confidence));
    }

    static void logDebug(String msg) {
        logDebug(msg, null);
    }

    static void logDebug(String msg, Throwable throwable) {
        if (DEBUG) {
            Log.d(TAG, msg, throwable);
        }
    }

    static void logVerbose(String msg) {
        if (DEBUG) {
            Log.v(TAG, msg);
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
