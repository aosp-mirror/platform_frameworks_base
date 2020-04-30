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

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.text.format.DateFormat;
import android.util.FloatProperty;
import android.util.Log;
import android.view.animation.Interpolator;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.DejankUtils;
import com.android.systemui.Dumpable;
import com.android.systemui.Interpolators;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.policy.CallbackController;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Tracks and reports on {@link StatusBarState}.
 */
@Singleton
public class StatusBarStateControllerImpl implements SysuiStatusBarStateController,
        CallbackController<StateListener>, Dumpable {
    private static final String TAG = "SbStateController";
    // Must be a power of 2
    private static final int HISTORY_SIZE = 32;

    private static final int MAX_STATE = StatusBarState.FULLSCREEN_USER_SWITCHER;
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
    private int mState;
    private int mLastState;
    private boolean mLeaveOpenOnKeyguardHide;
    private boolean mKeyguardRequested;

    // Record the HISTORY_SIZE most recent states
    private int mHistoryIndex = 0;
    private HistoricalState[] mHistoricalRecords = new HistoricalState[HISTORY_SIZE];

    /**
     * If any of the system bars is hidden.
     */
    private boolean mIsFullscreen = false;

    /**
     * If the navigation bar can stay hidden when the display gets tapped.
     */
    private boolean mIsImmersive = false;

    /**
     * If the device is currently pulsing (AOD2).
     */
    private boolean mPulsing;

    /**
     * If the device is currently dozing or not.
     */
    private boolean mIsDozing;

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
    public StatusBarStateControllerImpl(UiEventLogger uiEventLogger) {
        mUiEventLogger = uiEventLogger;
        for (int i = 0; i < HISTORY_SIZE; i++) {
            mHistoricalRecords[i] = new HistoricalState();
        }
    }

    @Override
    public int getState() {
        return mState;
    }

    @Override
    public boolean setState(int state) {
        if (state > MAX_STATE || state < MIN_STATE) {
            throw new IllegalArgumentException("Invalid state " + state);
        }
        if (state == mState) {
            return false;
        }

        // Record the to-be mState and mLastState
        recordHistoricalState(state, mState);

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
            mUiEventLogger.log(StatusBarStateEvent.fromState(mState));
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
    public boolean isDozing() {
        return mIsDozing;
    }

    @Override
    public float getDozeAmount() {
        return mDozeAmount;
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
    public void setDozeAmount(float dozeAmount, boolean animated) {
        if (mDarkAnimator != null && mDarkAnimator.isRunning()) {
            if (animated && mDozeAmountTarget == dozeAmount) {
                return;
            } else {
                mDarkAnimator.cancel();
            }
        }

        mDozeAmountTarget = dozeAmount;
        if (animated) {
            startDozeAnimation();
        } else {
            setDozeAmountInternal(dozeAmount);
        }
    }

    private void startDozeAnimation() {
        if (mDozeAmount == 0f || mDozeAmount == 1f) {
            mDozeInterpolator = mIsDozing
                    ? Interpolators.FAST_OUT_SLOW_IN
                    : Interpolators.TOUCH_RESPONSE_REVERSE;
        }
        mDarkAnimator = ObjectAnimator.ofFloat(this, SET_DARK_AMOUNT_PROPERTY, mDozeAmountTarget);
        mDarkAnimator.setInterpolator(Interpolators.LINEAR);
        mDarkAnimator.setDuration(StackStateAnimator.ANIMATION_DURATION_WAKEUP);
        mDarkAnimator.start();
    }

    private void setDozeAmountInternal(float dozeAmount) {
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
    public void addCallback(StateListener listener) {
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
     * StatusBarState out of StatusBar.java. Any new listeners should be built not to need ranking
     * (i.e., they are non-dependent on the order of operations of StatusBarState listeners).
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
    public void removeCallback(StateListener listener) {
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
    public void setFullscreenState(boolean isFullscreen, boolean isImmersive) {
        if (mIsFullscreen != isFullscreen || mIsImmersive != isImmersive) {
            mIsFullscreen = isFullscreen;
            mIsImmersive = isImmersive;
            synchronized (mListeners) {
                for (RankedListener rl : new ArrayList<>(mListeners)) {
                    rl.mListener.onFullscreenStateChanged(isFullscreen, isImmersive);
                }
            }
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
        return StatusBarState.toShortString(state);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("StatusBarStateController: ");
        pw.println(" mState=" + mState + " (" + describe(mState) + ")");
        pw.println(" mLastState=" + mLastState + " (" + describe(mLastState) + ")");
        pw.println(" mLeaveOpenOnKeyguardHide=" + mLeaveOpenOnKeyguardHide);
        pw.println(" mKeyguardRequested=" + mKeyguardRequested);
        pw.println(" mIsDozing=" + mIsDozing);
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

    private void recordHistoricalState(int currentState, int lastState) {
        mHistoryIndex = (mHistoryIndex + 1) % HISTORY_SIZE;
        HistoricalState state = mHistoricalRecords[mHistoryIndex];
        state.mState = currentState;
        state.mLastState = lastState;
        state.mTimestamp = System.currentTimeMillis();
    }

    /**
     * For keeping track of our previous state to help with debugging
     */
    private static class HistoricalState {
        int mState;
        int mLastState;
        long mTimestamp;

        @Override
        public String toString() {
            if (mTimestamp != 0) {
                StringBuilder sb = new StringBuilder();
                sb.append("state=").append(mState)
                        .append(" (").append(describe(mState)).append(")");
                sb.append("lastState=").append(mLastState).append(" (").append(describe(mLastState))
                        .append(")");
                sb.append("timestamp=")
                        .append(DateFormat.format("MM-dd HH:mm:ss", mTimestamp));

                return sb.toString();
            }
            return "Empty " + getClass().getSimpleName();
        }
    }
}
