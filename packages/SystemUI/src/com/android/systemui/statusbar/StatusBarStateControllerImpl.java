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

package com.android.systemui.statusbar;

import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.WindowInsetsController.APPEARANCE_LOW_PROFILE_BARS;

import static com.android.internal.jank.InteractionJankMonitor.CUJ_LOCKSCREEN_TRANSITION_FROM_AOD;
import static com.android.internal.jank.InteractionJankMonitor.CUJ_LOCKSCREEN_TRANSITION_TO_AOD;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.os.SystemProperties;
import android.os.Trace;
import android.text.format.DateFormat;
import android.util.FloatProperty;
import android.util.Log;
import android.view.Choreographer;
import android.view.InsetsFlags;
import android.view.InsetsVisibilities;
import android.view.View;
import android.view.ViewDebug;
import android.view.WindowInsetsController.Appearance;
import android.view.WindowInsetsController.Behavior;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.jank.InteractionJankMonitor.Configuration;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.DejankUtils;
import com.android.systemui.Dumpable;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.shade.ShadeExpansionStateManager;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.policy.CallbackController;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;

import javax.inject.Inject;

/**
 * Tracks and reports on {@link StatusBarState}.
 */
@SysUISingleton
public class StatusBarStateControllerImpl implements
        SysuiStatusBarStateController,
        CallbackController<StateListener>,
        Dumpable {
    private static final String TAG = "SbStateController";
    private static final boolean DEBUG_IMMERSIVE_APPS =
            SystemProperties.getBoolean("persist.debug.immersive_apps", false);

    // Must be a power of 2
    private static final int HISTORY_SIZE = 32;

    private static final int MAX_STATE = StatusBarState.SHADE_LOCKED;
    private static final int MIN_STATE = StatusBarState.SHADE;

    private static final Comparator<RankedListener> sComparator =
            Comparator.comparingInt(o -> o.mRank);
    private static final FloatProperty<StatusBarStateControllerImpl> SET_DARK_AMOUNT_PROPERTY =
            new FloatProperty<StatusBarStateControllerImpl>("mDozeAmount") {

                @Override
                public void setValue(StatusBarStateControllerImpl object, float value) {
                    object.setDozeAmountInternal(value);
                }

                @Override
                public Float get(StatusBarStateControllerImpl object) {
                    return object.mDozeAmount;
                }
            };

    private final ArrayList<RankedListener> mListeners = new ArrayList<>();
    private final UiEventLogger mUiEventLogger;
    private final InteractionJankMonitor mInteractionJankMonitor;
    private int mState;
    private int mLastState;
    private int mUpcomingState;
    private boolean mLeaveOpenOnKeyguardHide;
    private boolean mKeyguardRequested;

    // Record the HISTORY_SIZE most recent states
    private int mHistoryIndex = 0;
    private HistoricalState[] mHistoricalRecords = new HistoricalState[HISTORY_SIZE];
    // This is used by InteractionJankMonitor to get callback from HWUI.
    private View mView;

    /**
     * If any of the system bars is hidden.
     */
    private boolean mIsFullscreen = false;

    /**
     * If the device is currently pulsing (AOD2).
     */
    private boolean mPulsing;

    /**
     * If the device is currently dozing or not.
     */
    private boolean mIsDozing;

    /**
     * If the status bar is currently expanded or not.
     */
    private boolean mIsExpanded;

    /**
     * Current {@link #mDozeAmount} animator.
     */
    private ValueAnimator mDarkAnimator;

    /**
     * Current doze amount in this frame.
     */
    private float mDozeAmount;

    /**
     * Where the animator will stop.
     */
    private float mDozeAmountTarget;

    /**
     * The type of interpolator that should be used to the doze animation.
     */
    private Interpolator mDozeInterpolator = Interpolators.FAST_OUT_SLOW_IN;

    @Inject
    public StatusBarStateControllerImpl(
            UiEventLogger uiEventLogger,
            DumpManager dumpManager,
            InteractionJankMonitor interactionJankMonitor,
            ShadeExpansionStateManager shadeExpansionStateManager
    ) {
        mUiEventLogger = uiEventLogger;
        mInteractionJankMonitor = interactionJankMonitor;
        for (int i = 0; i < HISTORY_SIZE; i++) {
            mHistoricalRecords[i] = new HistoricalState();
        }
        shadeExpansionStateManager.addFullExpansionListener(this::onShadeExpansionFullyChanged);

        dumpManager.registerDumpable(this);
    }

    @Override
    public int getState() {
        return mState;
    }

    @Override
    public boolean setState(int state, boolean force) {
        if (state > MAX_STATE || state < MIN_STATE) {
            throw new IllegalArgumentException("Invalid state " + state);
        }

        // Unless we're explicitly asked to force the state change, don't apply the new state if
        // it's identical to both the current and upcoming states, since that should not be
        // necessary.
        if (!force && state == mState && state == mUpcomingState) {
            return false;
        }

        if (state != mUpcomingState) {
            Log.d(TAG, "setState: requested state " + StatusBarState.toString(state)
                    + "!= upcomingState: " + StatusBarState.toString(mUpcomingState) + ". "
                    + "This usually means the status bar state transition was interrupted before "
                    + "the upcoming state could be applied.");
        }

        // Record the to-be mState and mLastState
        recordHistoricalState(state /* newState */, mState /* lastState */, false);

        // b/139259891
        if (mState == StatusBarState.SHADE && state == StatusBarState.SHADE_LOCKED) {
            Log.e(TAG, "Invalid state transition: SHADE -> SHADE_LOCKED", new Throwable());
        }

        synchronized (mListeners) {
            String tag = getClass().getSimpleName() + "#setState(" + state + ")";
            DejankUtils.startDetectingBlockingIpcs(tag);
            for (RankedListener rl : new ArrayList<>(mListeners)) {
                rl.mListener.onStatePreChange(mState, state);
            }
            mLastState = mState;
            mState = state;
            updateUpcomingState(mState);
            mUiEventLogger.log(StatusBarStateEvent.fromState(mState));
            Trace.instantForTrack(Trace.TRACE_TAG_APP, "UI Events", "StatusBarState " + tag);
            for (RankedListener rl : new ArrayList<>(mListeners)) {
                rl.mListener.onStateChanged(mState);
            }

            for (RankedListener rl : new ArrayList<>(mListeners)) {
                rl.mListener.onStatePostChange();
            }
            DejankUtils.stopDetectingBlockingIpcs(tag);
        }

        return true;
    }

    @Override
    public void setUpcomingState(int nextState) {
        recordHistoricalState(nextState /* newState */, mState /* lastState */, true);
        updateUpcomingState(nextState);

    }

    private void updateUpcomingState(int upcomingState) {
        if (mUpcomingState != upcomingState) {
            mUpcomingState = upcomingState;
            for (RankedListener rl : new ArrayList<>(mListeners)) {
                rl.mListener.onUpcomingStateChanged(mUpcomingState);
            }
        }
    }

    @Override
    public int getCurrentOrUpcomingState() {
        return mUpcomingState;
    }

    @Override
    public boolean isDozing() {
        return mIsDozing;
    }

    @Override
    public boolean isPulsing() {
        return mPulsing;
    }

    @Override
    public float getDozeAmount() {
        return mDozeAmount;
    }

    @Override
    public boolean isExpanded() {
        return mIsExpanded;
    }

    @Override
    public float getInterpolatedDozeAmount() {
        return mDozeInterpolator.getInterpolation(mDozeAmount);
    }

    @Override
    public boolean setIsDozing(boolean isDozing) {
        if (mIsDozing == isDozing) {
            return false;
        }

        mIsDozing = isDozing;

        synchronized (mListeners) {
            String tag = getClass().getSimpleName() + "#setIsDozing";
            DejankUtils.startDetectingBlockingIpcs(tag);
            for (RankedListener rl : new ArrayList<>(mListeners)) {
                rl.mListener.onDozingChanged(isDozing);
            }
            DejankUtils.stopDetectingBlockingIpcs(tag);
        }

        return true;
    }

    @Override
    public void setAndInstrumentDozeAmount(View view, float dozeAmount, boolean animated) {
        if (mDarkAnimator != null && mDarkAnimator.isRunning()) {
            if (animated && mDozeAmountTarget == dozeAmount) {
                return;
            } else {
                mDarkAnimator.cancel();
            }
        }

        // We don't need a new attached view if we already have one.
        if ((mView == null || !mView.isAttachedToWindow())
                && (view != null && view.isAttachedToWindow())) {
            mView = view;
        }
        mDozeAmountTarget = dozeAmount;
        if (animated) {
            startDozeAnimation();
        } else {
            setDozeAmountInternal(dozeAmount);
        }
    }

    private void onShadeExpansionFullyChanged(Boolean isExpanded) {
        if (mIsExpanded != isExpanded) {
            mIsExpanded = isExpanded;
            String tag = getClass().getSimpleName() + "#setIsExpanded";
            DejankUtils.startDetectingBlockingIpcs(tag);
            for (RankedListener rl : new ArrayList<>(mListeners)) {
                rl.mListener.onExpandedChanged(mIsExpanded);
            }
            DejankUtils.stopDetectingBlockingIpcs(tag);
        }
    }

    private void startDozeAnimation() {
        if (mDozeAmount == 0f || mDozeAmount == 1f) {
            mDozeInterpolator = mIsDozing
                    ? Interpolators.FAST_OUT_SLOW_IN
                    : Interpolators.TOUCH_RESPONSE_REVERSE;
        }
        if (mDozeAmount == 1f && !mIsDozing) {
            // Workaround to force relayoutWindow to be called a frame earlier. Otherwise, if
            // mDozeAmount = 1f, then neither start() nor the first frame of the animation will
            // cause the scrim opacity to change, which ultimately results in an extra relayout and
            // causes us to miss a frame. By settings the doze amount to be <1f a frame earlier,
            // we can batch the relayout with the one in NotificationShadeWindowControllerImpl.
            setDozeAmountInternal(0.99f);
        }
        mDarkAnimator = createDarkAnimator();
    }

    @VisibleForTesting
    protected ObjectAnimator createDarkAnimator() {
        ObjectAnimator darkAnimator = ObjectAnimator.ofFloat(
                this, SET_DARK_AMOUNT_PROPERTY, mDozeAmountTarget);
        darkAnimator.setInterpolator(Interpolators.LINEAR);
        darkAnimator.setDuration(StackStateAnimator.ANIMATION_DURATION_WAKEUP);
        darkAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                cancelInteractionJankMonitor();
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                endInteractionJankMonitor();
            }

            @Override
            public void onAnimationStart(Animator animation) {
                beginInteractionJankMonitor();
            }
        });
        darkAnimator.start();
        return darkAnimator;
    }

    private void setDozeAmountInternal(float dozeAmount) {
        if (Float.compare(dozeAmount, mDozeAmount) == 0) {
            return;
        }
        mDozeAmount = dozeAmount;
        float interpolatedAmount = mDozeInterpolator.getInterpolation(dozeAmount);
        synchronized (mListeners) {
            String tag = getClass().getSimpleName() + "#setDozeAmount";
            DejankUtils.startDetectingBlockingIpcs(tag);
            for (RankedListener rl : new ArrayList<>(mListeners)) {
                rl.mListener.onDozeAmountChanged(mDozeAmount, interpolatedAmount);
            }
            DejankUtils.stopDetectingBlockingIpcs(tag);
        }
    }

    private void beginInteractionJankMonitor() {
        final boolean shouldPost =
                (mIsDozing && mDozeAmount == 0) || (!mIsDozing && mDozeAmount == 1);
        if (mInteractionJankMonitor != null && mView != null && mView.isAttachedToWindow()) {
            if (shouldPost) {
                Choreographer.getInstance().postCallback(
                        Choreographer.CALLBACK_ANIMATION, this::beginInteractionJankMonitor, null);
            } else {
                Configuration.Builder builder = Configuration.Builder.withView(getCujType(), mView)
                        .setDeferMonitorForAnimationStart(false);
                mInteractionJankMonitor.begin(builder);
            }
        }
    }

    private void endInteractionJankMonitor() {
        if (mInteractionJankMonitor == null) {
            return;
        }
        mInteractionJankMonitor.end(getCujType());
    }

    private void cancelInteractionJankMonitor() {
        if (mInteractionJankMonitor == null) {
            return;
        }
        mInteractionJankMonitor.cancel(getCujType());
    }

    private int getCujType() {
        return mIsDozing ? CUJ_LOCKSCREEN_TRANSITION_TO_AOD : CUJ_LOCKSCREEN_TRANSITION_FROM_AOD;
    }

    @Override
    public boolean goingToFullShade() {
        return mState == StatusBarState.SHADE && mLeaveOpenOnKeyguardHide;
    }

    @Override
    public void setLeaveOpenOnKeyguardHide(boolean leaveOpen) {
        mLeaveOpenOnKeyguardHide = leaveOpen;
    }

    @Override
    public boolean leaveOpenOnKeyguardHide() {
        return mLeaveOpenOnKeyguardHide;
    }

    @Override
    public boolean fromShadeLocked() {
        return mLastState == StatusBarState.SHADE_LOCKED;
    }

    @Override
    public void addCallback(@NonNull StateListener listener) {
        synchronized (mListeners) {
            addListenerInternalLocked(listener, Integer.MAX_VALUE);
        }
    }

    /**
     * Add a listener and a rank based on the priority of this message
     * @param listener the listener
     * @param rank the order in which you'd like to be called. Ranked listeners will be
     * notified before unranked, and we will sort ranked listeners from low to high
     *
     * @deprecated This method exists only to solve latent inter-dependencies from refactoring
     * StatusBarState out of CentralSurfaces.java. Any new listeners should be built not to need
     * ranking (i.e., they are non-dependent on the order of operations of StatusBarState
     * listeners).
     */
    @Deprecated
    @Override
    public void addCallback(StateListener listener, @SbStateListenerRank int rank) {
        synchronized (mListeners) {
            addListenerInternalLocked(listener, rank);
        }
    }

    @GuardedBy("mListeners")
    private void addListenerInternalLocked(StateListener listener, int rank) {
        // Protect against double-subscribe
        for (RankedListener rl : mListeners) {
            if (rl.mListener.equals(listener)) {
                return;
            }
        }

        RankedListener rl = new SysuiStatusBarStateController.RankedListener(listener, rank);
        mListeners.add(rl);
        mListeners.sort(sComparator);
    }


    @Override
    public void removeCallback(@NonNull StateListener listener) {
        synchronized (mListeners) {
            mListeners.removeIf((it) -> it.mListener.equals(listener));
        }
    }

    @Override
    public void setKeyguardRequested(boolean keyguardRequested) {
        mKeyguardRequested = keyguardRequested;
    }

    @Override
    public boolean isKeyguardRequested() {
        return mKeyguardRequested;
    }

    @Override
    public void setSystemBarAttributes(@Appearance int appearance, @Behavior int behavior,
            InsetsVisibilities requestedVisibilities, String packageName) {
        boolean isFullscreen = !requestedVisibilities.getVisibility(ITYPE_STATUS_BAR)
                || !requestedVisibilities.getVisibility(ITYPE_NAVIGATION_BAR);
        if (mIsFullscreen != isFullscreen) {
            mIsFullscreen = isFullscreen;
            synchronized (mListeners) {
                for (RankedListener rl : new ArrayList<>(mListeners)) {
                    rl.mListener.onFullscreenStateChanged(isFullscreen);
                }
            }
        }

        // TODO (b/190543382): Finish the logging logic.
        // This section can be removed if we don't need to print it on logcat.
        if (DEBUG_IMMERSIVE_APPS) {
            boolean dim = (appearance & APPEARANCE_LOW_PROFILE_BARS) != 0;
            String behaviorName = ViewDebug.flagsToString(InsetsFlags.class, "behavior", behavior);
            String requestedVisibilityString = requestedVisibilities.toString();
            if (requestedVisibilityString.isEmpty()) {
                requestedVisibilityString = "none";
            }
            Log.d(TAG, packageName + " dim=" + dim + " behavior=" + behaviorName
                    + " requested visibilities: " + requestedVisibilityString);
        }
    }

    @Override
    public void setPulsing(boolean pulsing) {
        if (mPulsing != pulsing) {
            mPulsing = pulsing;
            synchronized (mListeners) {
                for (RankedListener rl : new ArrayList<>(mListeners)) {
                    rl.mListener.onPulsingChanged(pulsing);
                }
            }
        }
    }

    /**
     * Returns String readable state of status bar from {@link StatusBarState}
     */
    public static String describe(int state) {
        return StatusBarState.toString(state);
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("StatusBarStateController: ");
        pw.println(" mState=" + mState + " (" + describe(mState) + ")");
        pw.println(" mLastState=" + mLastState + " (" + describe(mLastState) + ")");
        pw.println(" mLeaveOpenOnKeyguardHide=" + mLeaveOpenOnKeyguardHide);
        pw.println(" mKeyguardRequested=" + mKeyguardRequested);
        pw.println(" mIsDozing=" + mIsDozing);
        pw.println(" mListeners{" + mListeners.size() + "}=");
        for (RankedListener rl : mListeners) {
            pw.println("    " + rl.mListener);
        }
        pw.println(" Historical states:");
        // Ignore records without a timestamp
        int size = 0;
        for (int i = 0; i < HISTORY_SIZE; i++) {
            if (mHistoricalRecords[i].mTimestamp != 0) size++;
        }
        for (int i = mHistoryIndex + HISTORY_SIZE;
                i >= mHistoryIndex + HISTORY_SIZE - size + 1; i--) {
            pw.println("  (" + (mHistoryIndex + HISTORY_SIZE - i + 1) + ")"
                    + mHistoricalRecords[i & (HISTORY_SIZE - 1)]);
        }
    }

    private void recordHistoricalState(int newState, int lastState, boolean upcoming) {
        Trace.traceCounter(Trace.TRACE_TAG_APP, "statusBarState", newState);
        mHistoryIndex = (mHistoryIndex + 1) % HISTORY_SIZE;
        HistoricalState state = mHistoricalRecords[mHistoryIndex];
        state.mNewState = newState;
        state.mLastState = lastState;
        state.mTimestamp = System.currentTimeMillis();
        state.mUpcoming = upcoming;
    }

    /**
     * For keeping track of our previous state to help with debugging
     */
    private static class HistoricalState {
        int mNewState;
        int mLastState;
        long mTimestamp;
        boolean mUpcoming;

        @Override
        public String toString() {
            if (mTimestamp != 0) {
                StringBuilder sb = new StringBuilder();
                if (mUpcoming) {
                    sb.append("upcoming-");
                }
                sb.append("newState=").append(mNewState)
                        .append("(").append(describe(mNewState)).append(")");
                sb.append(" lastState=").append(mLastState).append("(").append(describe(mLastState))
                        .append(")");
                sb.append(" timestamp=")
                        .append(DateFormat.format("MM-dd HH:mm:ss", mTimestamp));

                return sb.toString();
            }
            return "Empty " + getClass().getSimpleName();
        }
    }
}
