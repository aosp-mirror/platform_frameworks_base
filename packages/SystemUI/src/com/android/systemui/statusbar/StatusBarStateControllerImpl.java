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

import static com.android.internal.jank.InteractionJankMonitor.CUJ_LOCKSCREEN_TRANSITION_FROM_AOD;
import static com.android.internal.jank.InteractionJankMonitor.CUJ_LOCKSCREEN_TRANSITION_TO_AOD;
import static com.android.systemui.keyguard.shared.model.KeyguardState.GONE;
import static com.android.systemui.util.kotlin.JavaAdapterKt.combineFlows;

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
import android.view.View;
import android.view.animation.Interpolator;

import androidx.annotation.NonNull;

import com.android.app.animation.Interpolators;
import com.android.compose.animation.scene.OverlayKey;
import com.android.compose.animation.scene.SceneKey;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.jank.InteractionJankMonitor.Configuration;
import com.android.internal.logging.UiEventLogger;
import com.android.keyguard.KeyguardClockSwitch;
import com.android.systemui.DejankUtils;
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.deviceentry.domain.interactor.DeviceUnlockedInteractor;
import com.android.systemui.deviceentry.shared.model.DeviceUnlockStatus;
import com.android.systemui.keyguard.MigrateClocksToBlueprint;
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor;
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.res.R;
import com.android.systemui.scene.data.model.SceneStack;
import com.android.systemui.scene.data.model.SceneStackKt;
import com.android.systemui.scene.domain.interactor.SceneBackInteractor;
import com.android.systemui.scene.domain.interactor.SceneContainerOcclusionInteractor;
import com.android.systemui.scene.domain.interactor.SceneInteractor;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.scene.shared.model.Overlays;
import com.android.systemui.scene.shared.model.Scenes;
import com.android.systemui.shade.domain.interactor.ShadeInteractor;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.policy.CallbackController;
import com.android.systemui.util.Compile;
import com.android.systemui.util.kotlin.JavaAdapter;

import dagger.Lazy;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;

import javax.inject.Inject;

/**
 * Tracks and reports on {@link StatusBarState}.
 */
@SysUISingleton
public class StatusBarStateControllerImpl implements
        SysuiStatusBarStateController,
        CallbackController<StateListener> {
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
    private final Lazy<InteractionJankMonitor> mInteractionJankMonitorLazy;
    private final JavaAdapter mJavaAdapter;
    private final Lazy<KeyguardInteractor> mKeyguardInteractorLazy;
    private final Lazy<KeyguardTransitionInteractor> mKeyguardTransitionInteractorLazy;
    private final Lazy<ShadeInteractor> mShadeInteractorLazy;
    private final Lazy<DeviceUnlockedInteractor> mDeviceUnlockedInteractorLazy;
    private final Lazy<SceneInteractor> mSceneInteractorLazy;
    private final Lazy<SceneContainerOcclusionInteractor> mSceneContainerOcclusionInteractorLazy;
    private final Lazy<KeyguardClockInteractor> mKeyguardClockInteractorLazy;
    private final Lazy<SceneBackInteractor> mSceneBackInteractorLazy;
    private final Lazy<AlternateBouncerInteractor> mAlternateBouncerInteractorLazy;
    private int mState;
    private int mLastState;
    private int mUpcomingState;
    private boolean mLeaveOpenOnKeyguardHide;
    private boolean mKeyguardRequested;

    // Record the HISTORY_SIZE most recent states
    private int mHistoryIndex = 0;
    private HistoricalState[] mHistoricalRecords = new HistoricalState[HISTORY_SIZE];
    // These views are used by InteractionJankMonitor to get callback from HWUI.
    private View mView;
    private KeyguardClockSwitch mClockSwitchView;

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
     * If the device is currently dreaming or not.
     */
    private boolean mIsDreaming;

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
            Lazy<InteractionJankMonitor> interactionJankMonitorLazy,
            JavaAdapter javaAdapter,
            Lazy<KeyguardInteractor> keyguardInteractor,
            Lazy<KeyguardTransitionInteractor> keyguardTransitionInteractor,
            Lazy<ShadeInteractor> shadeInteractorLazy,
            Lazy<DeviceUnlockedInteractor> deviceUnlockedInteractorLazy,
            Lazy<SceneInteractor> sceneInteractorLazy,
            Lazy<SceneContainerOcclusionInteractor> sceneContainerOcclusionInteractor,
            Lazy<KeyguardClockInteractor> keyguardClockInteractorLazy,
            Lazy<SceneBackInteractor> sceneBackInteractorLazy,
            Lazy<AlternateBouncerInteractor> alternateBouncerInteractorLazy) {
        mUiEventLogger = uiEventLogger;
        mInteractionJankMonitorLazy = interactionJankMonitorLazy;
        mJavaAdapter = javaAdapter;
        mKeyguardInteractorLazy = keyguardInteractor;
        mKeyguardTransitionInteractorLazy = keyguardTransitionInteractor;
        mShadeInteractorLazy = shadeInteractorLazy;
        mDeviceUnlockedInteractorLazy = deviceUnlockedInteractorLazy;
        mSceneInteractorLazy = sceneInteractorLazy;
        mSceneContainerOcclusionInteractorLazy = sceneContainerOcclusionInteractor;
        mKeyguardClockInteractorLazy = keyguardClockInteractorLazy;
        mSceneBackInteractorLazy = sceneBackInteractorLazy;
        mAlternateBouncerInteractorLazy = alternateBouncerInteractorLazy;
        for (int i = 0; i < HISTORY_SIZE; i++) {
            mHistoricalRecords[i] = new HistoricalState();
        }
    }

    @Override
    public void start() {
        mJavaAdapter.alwaysCollectFlow(
                mKeyguardTransitionInteractorLazy.get().isFinishedIn(
                        /* scene */ Scenes.Gone,
                        /* stateWithoutSceneContainer */ GONE),
                (Boolean isFinishedInState) -> {
                    if (isFinishedInState) {
                        setLeaveOpenOnKeyguardHide(false);
                    }
                });

        mJavaAdapter.alwaysCollectFlow(mShadeInteractorLazy.get().isAnyExpanded(),
                this::onShadeOrQsExpanded);

        if (SceneContainerFlag.isEnabled()) {
            mJavaAdapter.alwaysCollectFlow(
                    combineFlows(
                        mDeviceUnlockedInteractorLazy.get().getDeviceUnlockStatus(),
                        mSceneInteractorLazy.get().getCurrentScene(),
                        mSceneInteractorLazy.get().getCurrentOverlays(),
                        mSceneBackInteractorLazy.get().getBackStack(),
                        mSceneContainerOcclusionInteractorLazy.get().getInvisibleDueToOcclusion(),
                        mAlternateBouncerInteractorLazy.get().isVisible(),
                        this::calculateStateFromSceneFramework),
                    this::onStatusBarStateChanged);

            mJavaAdapter.alwaysCollectFlow(
                    mKeyguardInteractorLazy.get().getDozeAmount(),
                    this::setDozeAmountInternal);
        }
    }

    @Override
    public int getState() {
        return mState;
    }

    @Override
    public boolean setState(int state, boolean force) {
        if (SceneContainerFlag.isEnabled()) {
            return false;
        }

        if (state > MAX_STATE || state < MIN_STATE) {
            throw new IllegalArgumentException("Invalid state " + state);
        }

        // Unless we're explicitly asked to force the state change, don't apply the new state if
        // it's identical to both the current and upcoming states, since that should not be
        // necessary.
        if (!force && state == mState && state == mUpcomingState) {
            return false;
        }

        updateStateAndNotifyListeners(state);
        return true;
    }

    /**
     * Updates the {@link StatusBarState} and notifies registered listeners, if needed.
     */
    private void updateStateAndNotifyListeners(int state) {
        if (state != mUpcomingState && !SceneContainerFlag.isEnabled()) {
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
    }

    @Override
    public void setUpcomingState(int nextState) {
        if (SceneContainerFlag.isEnabled()) {
            return;
        }

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
    public boolean setIsDreaming(boolean isDreaming) {
        if (Log.isLoggable(TAG, Log.DEBUG) || Compile.IS_DEBUG) {
            Log.d(TAG, "setIsDreaming:" + isDreaming);
        }
        if (mIsDreaming == isDreaming) {
            return false;
        }

        mIsDreaming = isDreaming;

        synchronized (mListeners) {
            String tag = getClass().getSimpleName() + "#setIsDreaming";
            DejankUtils.startDetectingBlockingIpcs(tag);
            for (RankedListener rl : new ArrayList<>(mListeners)) {
                rl.mListener.onDreamingChanged(isDreaming);
            }
            DejankUtils.stopDetectingBlockingIpcs(tag);
        }

        return true;
    }

    @Override
    public boolean isDreaming() {
        return mIsDreaming;
    }

    @Override
    public void setAndInstrumentDozeAmount(View view, float dozeAmount, boolean animated) {
        SceneContainerFlag.assertInLegacyMode();
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
            mClockSwitchView = view.findViewById(R.id.keyguard_clock_container);
        }
        mDozeAmountTarget = dozeAmount;
        if (animated) {
            startDozeAnimation();
        } else {
            setDozeAmountInternal(dozeAmount);
        }
    }

    private void onShadeOrQsExpanded(Boolean isExpanded) {
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
        SceneContainerFlag.assertInLegacyMode();
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
        SceneContainerFlag.assertInLegacyMode();
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

    /** Returns the id of the currently rendering clock */
    public String getClockId() {
        if (MigrateClocksToBlueprint.isEnabled()) {
            return mKeyguardClockInteractorLazy.get().getRenderedClockId();
        }

        if (mClockSwitchView == null) {
            Log.e(TAG, "Clock container was missing");
            return KeyguardClockSwitch.MISSING_CLOCK_ID;
        }

        return mClockSwitchView.getClockId();
    }

    private void beginInteractionJankMonitor() {
        final boolean shouldPost =
                (mIsDozing && mDozeAmount == 0) || (!mIsDozing && mDozeAmount == 1);
        InteractionJankMonitor monitor = mInteractionJankMonitorLazy.get();
        if (monitor != null && mView != null && mView.isAttachedToWindow()) {
            if (shouldPost) {
                Choreographer.getInstance().postCallback(
                        Choreographer.CALLBACK_ANIMATION, this::beginInteractionJankMonitor, null);
            } else {
                Configuration.Builder builder = Configuration.Builder.withView(getCujType(), mView)
                        .setTag(getClockId())
                        .setDeferMonitorForAnimationStart(false);
                monitor.begin(builder);
            }
        }
    }

    private void endInteractionJankMonitor() {
        InteractionJankMonitor monitor = mInteractionJankMonitorLazy.get();
        if (monitor == null) {
            return;
        }
        monitor.end(getCujType());
    }

    private void cancelInteractionJankMonitor() {
        InteractionJankMonitor monitor = mInteractionJankMonitorLazy.get();
        if (monitor == null) {
            return;
        }
        monitor.cancel(getCujType());
    }

    private int getCujType() {
        return mIsDozing ? CUJ_LOCKSCREEN_TRANSITION_TO_AOD : CUJ_LOCKSCREEN_TRANSITION_FROM_AOD;
    }

    @Override
    public boolean goingToFullShade() {
        return getState() == StatusBarState.SHADE && mLeaveOpenOnKeyguardHide;
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
        pw.println(" mIsDreaming=" + mIsDreaming);
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

    private int calculateStateFromSceneFramework(
            DeviceUnlockStatus deviceUnlockStatus,
            SceneKey currentScene,
            Set<OverlayKey> currentOverlays,
            SceneStack backStack,
            boolean isOccluded,
            boolean alternateBouncerIsVisible) {
        SceneContainerFlag.isUnexpectedlyInLegacyMode();

        final boolean onBouncer = currentScene.equals(Scenes.Bouncer);
        final boolean onCommunal = currentScene.equals(Scenes.Communal);
        final boolean onGone = currentScene.equals(Scenes.Gone);
        final boolean onLockscreen = currentScene.equals(Scenes.Lockscreen);
        final boolean onQuickSettings = currentScene.equals(Scenes.QuickSettings);
        final boolean onShade = currentScene.equals(Scenes.Shade);

        final boolean overCommunal = SceneStackKt.contains(backStack, Scenes.Communal);
        final boolean overLockscreen = SceneStackKt.contains(backStack, Scenes.Lockscreen);
        final boolean overShade = SceneStackKt.contains(backStack, Scenes.Shade);

        final boolean overlaidShade = currentOverlays.contains(Overlays.NotificationsShade);
        final boolean overlaidQuickSettings = currentOverlays.contains(Overlays.QuickSettingsShade);

        final boolean isUnlocked = deviceUnlockStatus.isUnlocked();

        final String inputLogString = "currentScene=" + currentScene.getTestTag()
                + " currentOverlays=" + currentOverlays + " backStack=" + backStack
                + " isUnlocked=" + isUnlocked + " isOccluded=" + isOccluded
                + " alternateBouncerIsVisible=" + alternateBouncerIsVisible;

        int newState;

        // When the device unlocks, several things happen 'at once':
        // 1. deviceUnlockStatus.isUnlocked changes from false to true.
        // 2. Lockscreen changes to Gone, either in currentScene or in backStack.
        // 3. Bouncer is removed from currentScene or backStack, if it was present.
        // 4. the alternate bouncer is hidden, if it was visible.
        //
        // From this function's perspective, though, deviceUnlockStatus, currentScene, and backStack
        // each update separately, and the relative order of those updates is not well-defined. This
        // doesn't work well for clients of this class (like remote input) that expect the device to
        // be fully and properly unlocked when the state changes to SHADE.
        //
        // Therefore, we consider the device to be in a keyguardish state (KEYGUARD or SHADE_LOCKED,
        // but not SHADE) if *any* of these are still true:
        // 1. deviceUnlockStatus.isUnlocked is false.
        // 2. currentScene is a keyguardish scene (Lockscreen, Bouncer, or Communal).
        // 3. backStack contains a keyguardish scene (Lockscreen or Communal).
        // 4. the alternate bouncer is visible.

        final boolean onKeyguardish = onLockscreen || onBouncer || onCommunal;
        final boolean overKeyguardish = overLockscreen || overCommunal;

        if (isOccluded) {
            // Occlusion is special; even though the device is still technically on the lockscreen,
            // the UI behaves as if it is unlocked.
            newState = StatusBarState.SHADE;
        } else if (onKeyguardish || overKeyguardish || alternateBouncerIsVisible) {
            // We get here if we are on or over a keyguardish scene, even if isUnlocked is true; we
            // want to return SHADE_LOCKED or KEYGUARD until we are also neither on nor over a
            // keyguardish scene.
            if (onShade || onQuickSettings || overShade || overlaidShade || overlaidQuickSettings) {
                newState = StatusBarState.SHADE_LOCKED;
            } else {
                newState = StatusBarState.KEYGUARD;
            }
        } else if (isUnlocked || onGone) {
            newState = StatusBarState.SHADE;
        } else if (onShade || onQuickSettings) {
            // We get here if deviceUnlockStatus.isUnlocked is false but we are no longer on or over
            // a keyguardish scene; we want to return SHADE_LOCKED until isUnlocked is also true.
            newState = StatusBarState.SHADE_LOCKED;
        } else {
            throw new IllegalArgumentException(
                    "unhandled input to calculateStateFromSceneFramework: " + inputLogString);
        }

        if (Compile.IS_DEBUG) {
            Log.v(TAG, "calculateStateFromSceneFramework: "
                    + inputLogString + " -> " + StatusBarState.toString(newState));
        }

        return newState;
    }

    /** Notifies that the {@link StatusBarState} has changed to the given new state. */
    private void onStatusBarStateChanged(int newState) {
        SceneContainerFlag.isUnexpectedlyInLegacyMode();

        if (newState == mState) {
            return;
        }

        updateStateAndNotifyListeners(newState);
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
