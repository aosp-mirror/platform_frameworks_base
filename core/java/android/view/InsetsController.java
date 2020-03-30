/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.view;

import static android.view.InsetsState.ITYPE_CAPTION_BAR;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.toInternalType;
import static android.view.InsetsState.toPublicType;
import static android.view.WindowInsets.Type.all;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_APPEARANCE_CONTROLLED;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_BEHAVIOR_CONTROLLED;

import android.animation.AnimationHandler;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TypeEvaluator;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.view.InsetsSourceConsumer.ShowResult;
import android.view.InsetsState.InternalInsetsType;
import android.view.SurfaceControl.Transaction;
import android.view.ViewTreeObserver.OnPreDrawListener;
import android.view.WindowInsets.Type;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsAnimation.Bounds;
import android.view.WindowManager.LayoutParams.SoftInputModeFlags;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.SfVsyncFrameCallbackProvider;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Implements {@link WindowInsetsController} on the client.
 * @hide
 */
public class InsetsController implements WindowInsetsController, InsetsAnimationControlCallbacks {

    private static final int ANIMATION_DURATION_SHOW_MS = 275;
    private static final int ANIMATION_DURATION_HIDE_MS = 340;

    private static final int ANIMATION_DURATION_SYNC_IME_MS = 285;
    private static final int ANIMATION_DURATION_UNSYNC_IME_MS = 200;

    private static final int PENDING_CONTROL_TIMEOUT_MS = 2000;

    public static final Interpolator SYSTEM_BARS_INTERPOLATOR =
            new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    private static final Interpolator SYNC_IME_INTERPOLATOR =
            new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final Interpolator LINEAR_OUT_SLOW_IN_INTERPOLATOR =
            new PathInterpolator(0, 0, 0.2f, 1f);
    private static final Interpolator FAST_OUT_LINEAR_IN_INTERPOLATOR =
            new PathInterpolator(0.4f, 0f, 1f, 1f);

    /**
     * Layout mode during insets animation: The views should be laid out as if the changing inset
     * types are fully shown. Before starting the animation, {@link View#onApplyWindowInsets} will
     * be called as if the changing insets types are shown, which will result in the views being
     * laid out as if the insets are fully shown.
     */
    public static final int LAYOUT_INSETS_DURING_ANIMATION_SHOWN = 0;

    /**
     * Layout mode during insets animation: The views should be laid out as if the changing inset
     * types are fully hidden. Before starting the animation, {@link View#onApplyWindowInsets} will
     * be called as if the changing insets types are hidden, which will result in the views being
     * laid out as if the insets are fully hidden.
     */
    public static final int LAYOUT_INSETS_DURING_ANIMATION_HIDDEN = 1;

    /**
     * Determines the behavior of how the views should be laid out during an insets animation that
     * is controlled by the application by calling {@link #controlWindowInsetsAnimation}.
     * <p>
     * When the animation is system-initiated, the layout mode is always chosen such that the
     * pre-animation layout will represent the opposite of the starting state, i.e. when insets
     * are appearing, {@link #LAYOUT_INSETS_DURING_ANIMATION_SHOWN} will be used. When insets
     * are disappearing, {@link #LAYOUT_INSETS_DURING_ANIMATION_HIDDEN} will be used.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {LAYOUT_INSETS_DURING_ANIMATION_SHOWN,
            LAYOUT_INSETS_DURING_ANIMATION_HIDDEN})
    @interface LayoutInsetsDuringAnimation {
    }

    /** Not running an animation. */
    @VisibleForTesting
    public static final int ANIMATION_TYPE_NONE = -1;

    /** Running animation will show insets */
    @VisibleForTesting
    public static final int ANIMATION_TYPE_SHOW = 0;

    /** Running animation will hide insets */
    @VisibleForTesting
    public static final int ANIMATION_TYPE_HIDE = 1;

    /** Running animation is controlled by user via {@link #controlWindowInsetsAnimation} */
    @VisibleForTesting
    public static final int ANIMATION_TYPE_USER = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {ANIMATION_TYPE_NONE, ANIMATION_TYPE_SHOW, ANIMATION_TYPE_HIDE,
            ANIMATION_TYPE_USER})
    @interface AnimationType {
    }

    /**
     * Translation animation evaluator.
     */
    private static TypeEvaluator<Insets> sEvaluator = (fraction, startValue, endValue) -> Insets.of(
            (int) (startValue.left + fraction * (endValue.left - startValue.left)),
            (int) (startValue.top + fraction * (endValue.top - startValue.top)),
            (int) (startValue.right + fraction * (endValue.right - startValue.right)),
            (int) (startValue.bottom + fraction * (endValue.bottom - startValue.bottom)));

    /**
     * The default implementation of listener, to be used by InsetsController and InsetsPolicy to
     * animate insets.
     */
    public static class InternalAnimationControlListener
            implements WindowInsetsAnimationControlListener {

        private WindowInsetsAnimationController mController;
        private ValueAnimator mAnimator;
        private final boolean mShow;
        private final boolean mHasAnimationCallbacks;
        private final @InsetsType int mRequestedTypes;
        private final long mDurationMs;

        private ThreadLocal<AnimationHandler> mSfAnimationHandlerThreadLocal =
                new ThreadLocal<AnimationHandler>() {
            @Override
            protected AnimationHandler initialValue() {
                AnimationHandler handler = new AnimationHandler();
                handler.setProvider(new SfVsyncFrameCallbackProvider());
                return handler;
            }
        };

        public InternalAnimationControlListener(boolean show, boolean hasAnimationCallbacks,
                int requestedTypes) {
            mShow = show;
            mHasAnimationCallbacks = hasAnimationCallbacks;
            mRequestedTypes = requestedTypes;
            mDurationMs = calculateDurationMs();
        }

        @Override
        public void onReady(WindowInsetsAnimationController controller, int types) {
            mController = controller;

            mAnimator = ValueAnimator.ofFloat(0f, 1f);
            mAnimator.setDuration(mDurationMs);
            mAnimator.setInterpolator(new LinearInterpolator());
            Insets start = mShow
                    ? controller.getHiddenStateInsets()
                    : controller.getShownStateInsets();
            Insets end = mShow
                    ? controller.getShownStateInsets()
                    : controller.getHiddenStateInsets();
            Interpolator insetsInterpolator = getInterpolator();
            Interpolator alphaInterpolator = getAlphaInterpolator();
            mAnimator.addUpdateListener(animation -> {
                float rawFraction = animation.getAnimatedFraction();
                float alphaFraction = mShow
                        ? rawFraction
                        : 1 - rawFraction;
                float insetsFraction = insetsInterpolator.getInterpolation(rawFraction);
                controller.setInsetsAndAlpha(
                        sEvaluator.evaluate(insetsFraction, start, end),
                        alphaInterpolator.getInterpolation(alphaFraction),
                        rawFraction);
            });
            mAnimator.addListener(new AnimatorListenerAdapter() {

                @Override
                public void onAnimationEnd(Animator animation) {
                    onAnimationFinish();
                }
            });
            if (!mHasAnimationCallbacks) {
                mAnimator.setAnimationHandler(mSfAnimationHandlerThreadLocal.get());
            }
            mAnimator.start();
        }

        @Override
        public void onFinished(WindowInsetsAnimationController controller) {
        }

        @Override
        public void onCancelled(WindowInsetsAnimationController controller) {
            // Animator can be null when it is cancelled before onReady() completes.
            if (mAnimator != null) {
                mAnimator.cancel();
            }
        }

        Interpolator getInterpolator() {
            if ((mRequestedTypes & ime()) != 0) {
                if (mHasAnimationCallbacks) {
                    return SYNC_IME_INTERPOLATOR;
                } else if (mShow) {
                    return LINEAR_OUT_SLOW_IN_INTERPOLATOR;
                } else {
                    return FAST_OUT_LINEAR_IN_INTERPOLATOR;
                }
            } else {
                return SYSTEM_BARS_INTERPOLATOR;
            }
        }

        Interpolator getAlphaInterpolator() {
            if ((mRequestedTypes & ime()) != 0) {
                if (mHasAnimationCallbacks) {
                    return input -> 1f;
                } else if (mShow) {

                    // Alpha animation takes half the time with linear interpolation;
                    return input -> Math.min(1f, 2 * input);
                } else {
                    return FAST_OUT_LINEAR_IN_INTERPOLATOR;
                }
            } else {
                return input -> 1f;
            }
        }

        protected void onAnimationFinish() {
            mController.finish(mShow);
        }

        /**
         * To get the animation duration in MS.
         */
        public long getDurationMs() {
            return mDurationMs;
        }

        private long calculateDurationMs() {
            if ((mRequestedTypes & ime()) != 0) {
                if (mHasAnimationCallbacks) {
                    return ANIMATION_DURATION_SYNC_IME_MS;
                } else {
                    return ANIMATION_DURATION_UNSYNC_IME_MS;
                }
            } else {
                return mShow ? ANIMATION_DURATION_SHOW_MS : ANIMATION_DURATION_HIDE_MS;
            }
        }
    }

    /**
     * Represents a running animation
     */
    private static class RunningAnimation {

        RunningAnimation(InsetsAnimationControlRunner runner, int type) {
            this.runner = runner;
            this.type = type;
        }

        final InsetsAnimationControlRunner runner;
        final @AnimationType int type;

        /**
         * Whether {@link WindowInsetsAnimation.Callback#onStart(WindowInsetsAnimation, Bounds)} has
         * been dispatched already for this animation.
         */
        boolean startDispatched;
    }

    /**
     * Represents a control request that we had to defer because we are waiting for the IME to
     * process our show request.
     */
    private static class PendingControlRequest {

        PendingControlRequest(@InsetsType int types, WindowInsetsAnimationControlListener listener,
                long durationMs, Interpolator interpolator, @AnimationType int animationType,
                @LayoutInsetsDuringAnimation int layoutInsetsDuringAnimation,
                CancellationSignal cancellationSignal, boolean useInsetsAnimationThread) {
            this.types = types;
            this.listener = listener;
            this.durationMs = durationMs;
            this.interpolator = interpolator;
            this.animationType = animationType;
            this.layoutInsetsDuringAnimation = layoutInsetsDuringAnimation;
            this.cancellationSignal = cancellationSignal;
            this.useInsetsAnimationThread = useInsetsAnimationThread;
        }

        final @InsetsType int types;
        final WindowInsetsAnimationControlListener listener;
        final long durationMs;
        final Interpolator interpolator;
        final @AnimationType int animationType;
        final @LayoutInsetsDuringAnimation int layoutInsetsDuringAnimation;
        final CancellationSignal cancellationSignal;
        final boolean useInsetsAnimationThread;
    }

    private final String TAG = "InsetsControllerImpl";

    private final InsetsState mState = new InsetsState();
    private final InsetsState mLastDispachedState = new InsetsState();

    private final Rect mFrame = new Rect();
    private final BiFunction<InsetsController, Integer, InsetsSourceConsumer> mConsumerCreator;
    private final SparseArray<InsetsSourceConsumer> mSourceConsumers = new SparseArray<>();
    private final ViewRootImpl mViewRoot;
    private final Handler mHandler;

    private final SparseArray<InsetsSourceControl> mTmpControlArray = new SparseArray<>();
    private final ArrayList<RunningAnimation> mRunningAnimations = new ArrayList<>();
    private final ArrayList<WindowInsetsAnimation> mTmpRunningAnims = new ArrayList<>();
    private final List<WindowInsetsAnimation> mUnmodifiableTmpRunningAnims =
            Collections.unmodifiableList(mTmpRunningAnims);
    private final ArrayList<InsetsAnimationControlImpl> mTmpFinishedControls = new ArrayList<>();
    private WindowInsets mLastInsets;

    private boolean mAnimCallbackScheduled;

    private final Runnable mAnimCallback;

    /** Pending control request that is waiting on IME to be ready to be shown */
    private PendingControlRequest mPendingImeControlRequest;

    private int mLastLegacySoftInputMode;
    private int mLastLegacySystemUiFlags;
    private DisplayCutout mLastDisplayCutout;
    private boolean mStartingAnimation;
    private int mCaptionInsetsHeight = 0;

    private SyncRtSurfaceTransactionApplier mApplier;

    private Runnable mPendingControlTimeout = this::abortPendingImeControlRequest;
    private final ArrayList<OnControllableInsetsChangedListener> mControllableInsetsChangedListeners
            = new ArrayList<>();

    /** Set of inset types for which an animation was started since last resetting this field */
    private @InsetsType int mLastStartedAnimTypes;

    public InsetsController(ViewRootImpl viewRoot) {
        this(viewRoot, (controller, type) -> {
            if (type == ITYPE_IME) {
                return new ImeInsetsSourceConsumer(controller.mState, Transaction::new, controller);
            } else {
                return new InsetsSourceConsumer(type, controller.mState, Transaction::new,
                        controller);
            }
        }, viewRoot.mHandler);
    }

    @VisibleForTesting
    public InsetsController(ViewRootImpl viewRoot,
            BiFunction<InsetsController, Integer, InsetsSourceConsumer> consumerCreator,
            Handler handler) {
        mViewRoot = viewRoot;
        mConsumerCreator = consumerCreator;
        mHandler = handler;
        mAnimCallback = () -> {
            mAnimCallbackScheduled = false;
            if (mRunningAnimations.isEmpty()) {
                return;
            }
            if (mViewRoot.mView == null) {
                // The view has already detached from window.
                return;
            }

            mTmpFinishedControls.clear();
            mTmpRunningAnims.clear();
            InsetsState state = new InsetsState(mState, true /* copySources */);
            for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
                RunningAnimation runningAnimation = mRunningAnimations.get(i);
                InsetsAnimationControlRunner runner = runningAnimation.runner;
                if (runner instanceof InsetsAnimationControlImpl) {
                    InsetsAnimationControlImpl control = (InsetsAnimationControlImpl) runner;

                    // Keep track of running animation to be dispatched. Aggregate it here such that
                    // if it gets finished within applyChangeInsets we still dispatch it to
                    // onProgress.
                    if (runningAnimation.startDispatched) {
                        mTmpRunningAnims.add(control.getAnimation());
                    }

                    if (control.applyChangeInsets(state)) {
                        mTmpFinishedControls.add(control);
                    }
                }
            }

            WindowInsets insets = state.calculateInsets(mFrame, mState /* ignoringVisibilityState*/,
                    mLastInsets.isRound(), mLastInsets.shouldAlwaysConsumeSystemBars(),
                    mLastDisplayCutout, mLastLegacySoftInputMode, mLastLegacySystemUiFlags,
                    null /* typeSideMap */);
            mViewRoot.mView.dispatchWindowInsetsAnimationProgress(insets,
                    mUnmodifiableTmpRunningAnims);

            for (int i = mTmpFinishedControls.size() - 1; i >= 0; i--) {
                dispatchAnimationEnd(mTmpFinishedControls.get(i).getAnimation());
            }
        };
    }

    @VisibleForTesting
    public void onFrameChanged(Rect frame) {
        if (mFrame.equals(frame)) {
            return;
        }
        mViewRoot.notifyInsetsChanged();
        mFrame.set(frame);
    }

    @Override
    public InsetsState getState() {
        return mState;
    }

    @Override
    public boolean isRequestedVisible(int type) {
        return getSourceConsumer(type).isRequestedVisible();
    }

    public InsetsState getLastDispatchedState() {
        return mLastDispachedState;
    }

    @VisibleForTesting
    public boolean onStateChanged(InsetsState state) {
        boolean localStateChanged = !mState.equals(state, true /* excludingCaptionInsets */)
                || !captionInsetsUnchanged();
        if (!localStateChanged && mLastDispachedState.equals(state)) {
            return false;
        }
        updateState(state);
        mLastDispachedState.set(state, true /* copySources */);
        applyLocalVisibilityOverride();
        if (localStateChanged) {
            mViewRoot.notifyInsetsChanged();
        }
        if (!mState.equals(mLastDispachedState, true /* excludingCaptionInsets */)) {
            sendStateToWindowManager();
        }
        return true;
    }

    private void updateState(InsetsState newState) {
        mState.setDisplayFrame(newState.getDisplayFrame());
        for (int i = newState.getSourcesCount() - 1; i >= 0; i--) {
            InsetsSource source = newState.sourceAt(i);
            getSourceConsumer(source.getType()).updateSource(source);
        }
        for (int i = mState.getSourcesCount() - 1; i >= 0; i--) {
            InsetsSource source = mState.sourceAt(i);
            if (newState.peekSource(source.getType()) == null) {
                mState.removeSource(source.getType());
            }
        }
        if (mCaptionInsetsHeight != 0) {
            mState.getSource(ITYPE_CAPTION_BAR).setFrame(new Rect(mFrame.left, mFrame.top,
                    mFrame.right, mFrame.top + mCaptionInsetsHeight));
        }
    }

    private boolean captionInsetsUnchanged() {
        if (mState.peekSource(ITYPE_CAPTION_BAR) == null
                && mCaptionInsetsHeight == 0) {
            return true;
        }
        if (mState.peekSource(ITYPE_CAPTION_BAR) != null
                && mCaptionInsetsHeight
                == mState.peekSource(ITYPE_CAPTION_BAR).getFrame().height()) {
            return true;
        }
        return false;
    }

    /**
     * @see InsetsState#calculateInsets
     */
    @VisibleForTesting
    public WindowInsets calculateInsets(boolean isScreenRound,
            boolean alwaysConsumeSystemBars, DisplayCutout cutout,
            int legacySoftInputMode, int legacySystemUiFlags) {
        mLastLegacySoftInputMode = legacySoftInputMode;
        mLastLegacySystemUiFlags = legacySystemUiFlags;
        mLastDisplayCutout = cutout;
        mLastInsets = mState.calculateInsets(mFrame, null /* ignoringVisibilityState*/,
                isScreenRound, alwaysConsumeSystemBars, cutout,
                legacySoftInputMode, legacySystemUiFlags,
                null /* typeSideMap */);
        return mLastInsets;
    }

    /**
     * @see InsetsState#calculateVisibleInsets(Rect, int)
     */
    public Rect calculateVisibleInsets(@SoftInputModeFlags int softInputMode) {
        return mState.calculateVisibleInsets(mFrame, softInputMode);
    }

    /**
     * Called when the server has dispatched us a new set of inset controls.
     */
    public void onControlsChanged(InsetsSourceControl[] activeControls) {
        if (activeControls != null) {
            for (InsetsSourceControl activeControl : activeControls) {
                if (activeControl != null) {
                    // TODO(b/122982984): Figure out why it can be null.
                    mTmpControlArray.put(activeControl.getType(), activeControl);
                }
            }
        }

        int[] showTypes = new int[1];
        int[] hideTypes = new int[1];

        // Ensure to update all existing source consumers
        for (int i = mSourceConsumers.size() - 1; i >= 0; i--) {
            final InsetsSourceConsumer consumer = mSourceConsumers.valueAt(i);
            final InsetsSourceControl control = mTmpControlArray.get(consumer.getType());

            // control may be null, but we still need to update the control to null if it got
            // revoked.
            consumer.setControl(control, showTypes, hideTypes);
        }

        // Ensure to create source consumers if not available yet.
        for (int i = mTmpControlArray.size() - 1; i >= 0; i--) {
            final InsetsSourceControl control = mTmpControlArray.valueAt(i);
            InsetsSourceConsumer consumer = getSourceConsumer(control.getType());
            consumer.setControl(control, showTypes, hideTypes);

        }
        mTmpControlArray.clear();

        // Do not override any animations that the app started in the OnControllableInsetsChanged
        // listeners.
        int animatingTypes = invokeControllableInsetsChangedListeners();
        showTypes[0] &= ~animatingTypes;
        hideTypes[0] &= ~animatingTypes;

        if (showTypes[0] != 0) {
            applyAnimation(showTypes[0], true /* show */, false /* fromIme */);
        }
        if (hideTypes[0] != 0) {
            applyAnimation(hideTypes[0], false /* show */, false /* fromIme */);
        }
    }

    @Override
    public void show(@InsetsType int types) {
        show(types, false /* fromIme */);
    }

    @VisibleForTesting
    public void show(@InsetsType int types, boolean fromIme) {

        // Handle pending request ready in case there was one set.
        if (fromIme && mPendingImeControlRequest != null) {
            PendingControlRequest pendingRequest = mPendingImeControlRequest;
            mPendingImeControlRequest = null;
            mHandler.removeCallbacks(mPendingControlTimeout);
            controlAnimationUnchecked(
                    pendingRequest.types, pendingRequest.cancellationSignal,
                    pendingRequest.listener, mFrame,
                    true /* fromIme */, pendingRequest.durationMs, pendingRequest.interpolator,
                    pendingRequest.animationType,
                    pendingRequest.layoutInsetsDuringAnimation,
                    pendingRequest.useInsetsAnimationThread);
            return;
        }

        // TODO: Support a ResultReceiver for IME.
        // TODO(b/123718661): Make show() work for multi-session IME.
        int typesReady = 0;
        final ArraySet<Integer> internalTypes = InsetsState.toInternalType(types);
        for (int i = internalTypes.size() - 1; i >= 0; i--) {
            @InternalInsetsType int internalType = internalTypes.valueAt(i);
            @AnimationType int animationType = getAnimationType(internalType);
            InsetsSourceConsumer consumer = getSourceConsumer(internalType);
            if (consumer.isRequestedVisible() && animationType == ANIMATION_TYPE_NONE
                    || animationType == ANIMATION_TYPE_SHOW) {
                // no-op: already shown or animating in (because window visibility is
                // applied before starting animation).
                continue;
            }
            typesReady |= InsetsState.toPublicType(consumer.getType());
        }
        applyAnimation(typesReady, true /* show */, fromIme);
    }

    @Override
    public void hide(@InsetsType int types) {
        hide(types, false /* fromIme */);
    }

    void hide(@InsetsType int types, boolean fromIme) {
        int typesReady = 0;
        final ArraySet<Integer> internalTypes = InsetsState.toInternalType(types);
        for (int i = internalTypes.size() - 1; i >= 0; i--) {
            @InternalInsetsType int internalType = internalTypes.valueAt(i);
            @AnimationType int animationType = getAnimationType(internalType);
            InsetsSourceConsumer consumer = getSourceConsumer(internalType);
            if (!consumer.isRequestedVisible() && animationType == ANIMATION_TYPE_NONE
                    || animationType == ANIMATION_TYPE_HIDE) {
                // no-op: already hidden or animating out.
                continue;
            }
            typesReady |= InsetsState.toPublicType(consumer.getType());
        }
        applyAnimation(typesReady, false /* show */, fromIme /* fromIme */);
    }

    @Override
    public void controlWindowInsetsAnimation(@InsetsType int types, long durationMillis,
            @Nullable Interpolator interpolator,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull WindowInsetsAnimationControlListener listener) {
        controlWindowInsetsAnimation(types, cancellationSignal, listener,
                false /* fromIme */, durationMillis, interpolator, ANIMATION_TYPE_USER);
    }

    private void controlWindowInsetsAnimation(@InsetsType int types,
            @Nullable CancellationSignal cancellationSignal,
            WindowInsetsAnimationControlListener listener,
            boolean fromIme, long durationMs, @Nullable Interpolator interpolator,
            @AnimationType int animationType) {
        if (!checkDisplayFramesForControlling()) {
            listener.onCancelled(null);
            return;
        }

        controlAnimationUnchecked(types, cancellationSignal, listener, mFrame, fromIme, durationMs,
                interpolator, animationType, getLayoutInsetsDuringAnimationMode(types),
                false /* useInsetsAnimationThread */);
    }

    private boolean checkDisplayFramesForControlling() {

        // If the frame of our window doesn't span the entire display, the control API makes very
        // little sense, as we don't deal with negative insets. So just cancel immediately.
        return mState.getDisplayFrame().equals(mFrame);
    }

    private void controlAnimationUnchecked(@InsetsType int types,
            @Nullable CancellationSignal cancellationSignal,
            WindowInsetsAnimationControlListener listener, Rect frame, boolean fromIme,
            long durationMs, Interpolator interpolator,
            @AnimationType int animationType,
            @LayoutInsetsDuringAnimation int layoutInsetsDuringAnimation,
            boolean useInsetsAnimationThread) {
        if (types == 0) {
            // nothing to animate.
            listener.onCancelled(null);
            return;
        }
        cancelExistingControllers(types);
        mLastStartedAnimTypes |= types;

        final ArraySet<Integer> internalTypes = InsetsState.toInternalType(types);
        final SparseArray<InsetsSourceControl> controls = new SparseArray<>();

        Pair<Integer, Boolean> typesReadyPair = collectSourceControls(
                fromIme, internalTypes, controls, animationType);
        int typesReady = typesReadyPair.first;
        boolean imeReady = typesReadyPair.second;
        if (!imeReady) {
            // IME isn't ready, all requested types will be animated once IME is ready
            abortPendingImeControlRequest();
            final PendingControlRequest request = new PendingControlRequest(types,
                    listener, durationMs,
                    interpolator, animationType, layoutInsetsDuringAnimation, cancellationSignal,
                    useInsetsAnimationThread);
            mPendingImeControlRequest = request;
            mHandler.postDelayed(mPendingControlTimeout, PENDING_CONTROL_TIMEOUT_MS);
            if (cancellationSignal != null) {
                cancellationSignal.setOnCancelListener(() -> {
                    if (mPendingImeControlRequest == request) {
                        abortPendingImeControlRequest();
                    }
                });
            }
            return;
        }

        if (typesReady == 0) {
            listener.onCancelled(null);
            return;
        }


        final InsetsAnimationControlRunner runner = useInsetsAnimationThread
                ? new InsetsAnimationThreadControlRunner(controls,
                        frame, mState, listener, typesReady, this, durationMs, interpolator,
                        animationType, mViewRoot.mHandler)
                : new InsetsAnimationControlImpl(controls,
                        frame, mState, listener, typesReady, this, durationMs, interpolator,
                        animationType);
        mRunningAnimations.add(new RunningAnimation(runner, animationType));
        if (cancellationSignal != null) {
            cancellationSignal.setOnCancelListener(runner::cancel);
        }
        if (layoutInsetsDuringAnimation == LAYOUT_INSETS_DURING_ANIMATION_SHOWN) {
            showDirectly(types);
        } else {
            hideDirectly(types, false /* animationFinished */, animationType);
        }
    }

    /**
     * @return Pair of (types ready to animate, IME ready to animate).
     */
    private Pair<Integer, Boolean> collectSourceControls(boolean fromIme,
            ArraySet<Integer> internalTypes, SparseArray<InsetsSourceControl> controls,
            @AnimationType int animationType) {
        int typesReady = 0;
        boolean imeReady = true;
        for (int i = internalTypes.size() - 1; i >= 0; i--) {
            final InsetsSourceConsumer consumer = getSourceConsumer(internalTypes.valueAt(i));
            boolean show = animationType == ANIMATION_TYPE_SHOW
                    || animationType == ANIMATION_TYPE_USER;
            boolean canRun = false;
            if (show) {
                // Show request
                switch(consumer.requestShow(fromIme)) {
                    case ShowResult.SHOW_IMMEDIATELY:
                        canRun = true;
                        break;
                    case ShowResult.IME_SHOW_DELAYED:
                        imeReady = false;
                        break;
                    case ShowResult.IME_SHOW_FAILED:
                        // IME cannot be shown (since it didn't have focus), proceed
                        // with animation of other types.
                        break;
                }
            } else {
                // Hide request
                // TODO: Move notifyHidden() to beginning of the hide animation
                // (when visibility actually changes using hideDirectly()).
                if (!fromIme) {
                    consumer.notifyHidden();
                }
                canRun = true;
            }
            if (!canRun) {
                continue;
            }
            final InsetsSourceControl control = consumer.getControl();
            if (control != null) {
                controls.put(consumer.getType(), new InsetsSourceControl(control));
                typesReady |= toPublicType(consumer.getType());
            } else if (animationType == ANIMATION_TYPE_SHOW) {

                // We don't have a control at the moment. However, we still want to update requested
                // visibility state such that in case we get control, we can apply show animation.
                consumer.show(fromIme);
            } else if (animationType == ANIMATION_TYPE_HIDE) {
                consumer.hide();
            }
        }
        return new Pair<>(typesReady, imeReady);
    }

    private @LayoutInsetsDuringAnimation int getLayoutInsetsDuringAnimationMode(
            @InsetsType int types) {

        final ArraySet<Integer> internalTypes = InsetsState.toInternalType(types);

        // Generally, we want to layout the opposite of the current state. This is to make animation
        // callbacks easy to use: The can capture the layout values and then treat that as end-state
        // during the animation.
        //
        // However, if controlling multiple sources, we want to treat it as shown if any of the
        // types is currently hidden.
        for (int i = internalTypes.size() - 1; i >= 0; i--) {
            InsetsSourceConsumer consumer = mSourceConsumers.get(internalTypes.valueAt(i));
            if (consumer == null) {
                continue;
            }
            if (!consumer.isRequestedVisible()) {
                return LAYOUT_INSETS_DURING_ANIMATION_SHOWN;
            }
        }
        return LAYOUT_INSETS_DURING_ANIMATION_HIDDEN;
    }

    private void cancelExistingControllers(@InsetsType int types) {
        for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
            InsetsAnimationControlRunner control = mRunningAnimations.get(i).runner;
            if ((control.getTypes() & types) != 0) {
                cancelAnimation(control, true /* invokeCallback */);
            }
        }
        if ((types & ime()) != 0) {
            abortPendingImeControlRequest();
        }
    }

    private void abortPendingImeControlRequest() {
        if (mPendingImeControlRequest != null) {
            mPendingImeControlRequest.listener.onCancelled(null);
            mPendingImeControlRequest = null;
            mHandler.removeCallbacks(mPendingControlTimeout);
        }
    }

    @VisibleForTesting
    @Override
    public void notifyFinished(InsetsAnimationControlRunner runner, boolean shown) {
        cancelAnimation(runner, false /* invokeCallback */);
        if (shown) {
            showDirectly(runner.getTypes());
        } else {
            hideDirectly(runner.getTypes(), true /* animationFinished */,
                    runner.getAnimationType());
        }
    }

    @Override
    public void applySurfaceParams(final SyncRtSurfaceTransactionApplier.SurfaceParams... params) {
        if (mApplier == null) {
            if (mViewRoot.mView == null) {
                throw new IllegalStateException("View of the ViewRootImpl is not initiated.");
            }
            mApplier = new SyncRtSurfaceTransactionApplier(mViewRoot.mView);
        }
        if (mViewRoot.mView.isHardwareAccelerated()) {
            mApplier.scheduleApply(false /* earlyWakeup */, params);
        } else {
            // Window doesn't support hardware acceleration, no synchronization for now.
            // TODO(b/149342281): use mViewRoot.mSurface.getNextFrameNumber() to sync on every
            //  frame instead.
            mApplier.applyParams(new Transaction(), -1 /* frame */, false /* earlyWakeup */,
                    params);
        }
    }

    void notifyControlRevoked(InsetsSourceConsumer consumer) {
        for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
            InsetsAnimationControlRunner control = mRunningAnimations.get(i).runner;
            if ((control.getTypes() & toPublicType(consumer.getType())) != 0) {
                cancelAnimation(control, true /* invokeCallback */);
            }
        }
        if (consumer.getType() == ITYPE_IME) {
            abortPendingImeControlRequest();
        }
    }

    private void cancelAnimation(InsetsAnimationControlRunner control, boolean invokeCallback) {
        if (invokeCallback) {
            control.cancel();
        }
        for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
            RunningAnimation runningAnimation = mRunningAnimations.get(i);
            if (runningAnimation.runner == control) {
                mRunningAnimations.remove(i);
                ArraySet<Integer> types = toInternalType(control.getTypes());
                for (int j = types.size() - 1; j >= 0; j--) {
                    if (getSourceConsumer(types.valueAt(j)).notifyAnimationFinished()) {
                        mViewRoot.notifyInsetsChanged();
                    }
                }
                break;
            }
        }
    }

    private void applyLocalVisibilityOverride() {
        for (int i = mSourceConsumers.size() - 1; i >= 0; i--) {
            final InsetsSourceConsumer controller = mSourceConsumers.valueAt(i);
            controller.applyLocalVisibilityOverride();
        }
    }

    @VisibleForTesting
    public @NonNull InsetsSourceConsumer getSourceConsumer(@InternalInsetsType int type) {
        InsetsSourceConsumer controller = mSourceConsumers.get(type);
        if (controller != null) {
            return controller;
        }
        controller = mConsumerCreator.apply(this, type);
        mSourceConsumers.put(type, controller);
        return controller;
    }

    @VisibleForTesting
    public void notifyVisibilityChanged() {
        mViewRoot.notifyInsetsChanged();
        sendStateToWindowManager();
    }

    /**
     * @see ViewRootImpl#updateCompatSysUiVisibility(int, boolean, boolean)
     */
    public void updateCompatSysUiVisibility(@InternalInsetsType int type, boolean visible,
            boolean hasControl) {
        mViewRoot.updateCompatSysUiVisibility(type, visible, hasControl);
    }

    /**
     * Called when current window gains focus.
     */
    public void onWindowFocusGained() {
        getSourceConsumer(ITYPE_IME).onWindowFocusGained();
    }

    /**
     * Called when current window loses focus.
     */
    public void onWindowFocusLost() {
        getSourceConsumer(ITYPE_IME).onWindowFocusLost();
    }

    ViewRootImpl getViewRoot() {
        return mViewRoot;
    }

    /**
     * Used by {@link ImeInsetsSourceConsumer} when IME decides to be shown/hidden.
     * @hide
     */
    @VisibleForTesting
    public void applyImeVisibility(boolean setVisible) {
        if (setVisible) {
            show(Type.IME, true /* fromIme */);
        } else {
            hide(Type.IME);
        }
    }

    @VisibleForTesting
    public @AnimationType int getAnimationType(@InternalInsetsType int type) {
        for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
            InsetsAnimationControlRunner control = mRunningAnimations.get(i).runner;
            if (control.controlsInternalType(type)) {
                return mRunningAnimations.get(i).type;
            }
        }
        return ANIMATION_TYPE_NONE;
    }

    /**
     * Sends the local visibility state back to window manager.
     */
    private void sendStateToWindowManager() {
        InsetsState tmpState = new InsetsState();
        for (int i = mSourceConsumers.size() - 1; i >= 0; i--) {
            final InsetsSourceConsumer consumer = mSourceConsumers.valueAt(i);
            if (consumer.getType() == ITYPE_CAPTION_BAR) continue;
            if (consumer.getControl() != null) {
                tmpState.addSource(mState.getSource(consumer.getType()));
            }
        }

        try {
            mViewRoot.mWindowSession.insetsModified(mViewRoot.mWindow, tmpState);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to call insetsModified", e);
        }
    }

    private void applyAnimation(@InsetsType final int types, boolean show, boolean fromIme) {
        if (types == 0) {
            // nothing to animate.
            return;
        }

        boolean hasAnimationCallbacks = hasAnimationCallbacks();
        final InternalAnimationControlListener listener =
                new InternalAnimationControlListener(show, hasAnimationCallbacks, types);

        // Show/hide animations always need to be relative to the display frame, in order that shown
        // and hidden state insets are correct.
        controlAnimationUnchecked(
                types, null /* cancellationSignal */, listener, mState.getDisplayFrame(), fromIme,
                listener.getDurationMs(), listener.getInterpolator(),
                show ? ANIMATION_TYPE_SHOW : ANIMATION_TYPE_HIDE,
                show ? LAYOUT_INSETS_DURING_ANIMATION_SHOWN : LAYOUT_INSETS_DURING_ANIMATION_HIDDEN,
                !hasAnimationCallbacks /* useInsetsAnimationThread */);

    }

    private boolean hasAnimationCallbacks() {
        if (mViewRoot.mView == null) {
            return false;
        }
        return mViewRoot.mView.hasWindowInsetsAnimationCallback();
    }

    private void hideDirectly(
            @InsetsType int types, boolean animationFinished, @AnimationType int animationType) {
        final ArraySet<Integer> internalTypes = InsetsState.toInternalType(types);
        for (int i = internalTypes.size() - 1; i >= 0; i--) {
            getSourceConsumer(internalTypes.valueAt(i)).hide(animationFinished, animationType);
        }
    }

    private void showDirectly(@InsetsType int types) {
        final ArraySet<Integer> internalTypes = InsetsState.toInternalType(types);
        for (int i = internalTypes.size() - 1; i >= 0; i--) {
            getSourceConsumer(internalTypes.valueAt(i)).show(false /* fromIme */);
        }
    }

    /**
     * Cancel on-going animation to show/hide {@link InsetsType}.
     */
    @VisibleForTesting
    public void cancelExistingAnimation() {
        cancelExistingControllers(all());
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix); pw.println("InsetsController:");
        mState.dump(prefix + "  ", pw);
    }

    @VisibleForTesting
    @Override
    public void startAnimation(InsetsAnimationControlImpl controller,
            WindowInsetsAnimationControlListener listener, int types,
            WindowInsetsAnimation animation, Bounds bounds) {
        if (mViewRoot.mView == null) {
            return;
        }
        mViewRoot.mView.dispatchWindowInsetsAnimationPrepare(animation);
        mViewRoot.mView.getViewTreeObserver().addOnPreDrawListener(new OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                mViewRoot.mView.getViewTreeObserver().removeOnPreDrawListener(this);
                if (controller.isCancelled()) {
                    return true;
                }
                for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
                    RunningAnimation runningAnimation = mRunningAnimations.get(i);
                    if (runningAnimation.runner == controller) {
                        runningAnimation.startDispatched = true;
                    }
                }
                mViewRoot.mView.dispatchWindowInsetsAnimationStart(animation, bounds);
                mStartingAnimation = true;
                listener.onReady(controller, types);
                mStartingAnimation = false;
                return true;
            }
        });
        mViewRoot.mView.invalidate();
    }

    @VisibleForTesting
    public void dispatchAnimationEnd(WindowInsetsAnimation animation) {
        mViewRoot.mView.dispatchWindowInsetsAnimationEnd(animation);
    }

    @VisibleForTesting
    @Override
    public void scheduleApplyChangeInsets() {
        if (mStartingAnimation) {
            mAnimCallback.run();
            mAnimCallbackScheduled = false;
            return;
        }
        if (!mAnimCallbackScheduled) {
            mViewRoot.mChoreographer.postCallback(Choreographer.CALLBACK_INSETS_ANIMATION,
                    mAnimCallback, null /* token*/);
            mAnimCallbackScheduled = true;
        }
    }

    @Override
    public void setSystemBarsAppearance(@Appearance int appearance, @Appearance int mask) {
        mViewRoot.mWindowAttributes.privateFlags |= PRIVATE_FLAG_APPEARANCE_CONTROLLED;
        final InsetsFlags insetsFlags = mViewRoot.mWindowAttributes.insetsFlags;
        if (insetsFlags.appearance != appearance) {
            insetsFlags.appearance = (insetsFlags.appearance & ~mask) | (appearance & mask);
            mViewRoot.mWindowAttributesChanged = true;
            mViewRoot.scheduleTraversals();
        }
    }

    @Override
    public @Appearance int getSystemBarsAppearance() {
        if ((mViewRoot.mWindowAttributes.privateFlags & PRIVATE_FLAG_APPEARANCE_CONTROLLED) == 0) {
            // We only return the requested appearance, not the implied one.
            return 0;
        }
        return mViewRoot.mWindowAttributes.insetsFlags.appearance;
    }

    @Override
    public void setCaptionInsetsHeight(int height) {
        mCaptionInsetsHeight = height;
    }

    @Override
    public void setSystemBarsBehavior(@Behavior int behavior) {
        mViewRoot.mWindowAttributes.privateFlags |= PRIVATE_FLAG_BEHAVIOR_CONTROLLED;
        if (mViewRoot.mWindowAttributes.insetsFlags.behavior != behavior) {
            mViewRoot.mWindowAttributes.insetsFlags.behavior = behavior;
            mViewRoot.mWindowAttributesChanged = true;
            mViewRoot.scheduleTraversals();
        }
    }

    @Override
    public @Appearance int getSystemBarsBehavior() {
        if ((mViewRoot.mWindowAttributes.privateFlags & PRIVATE_FLAG_BEHAVIOR_CONTROLLED) == 0) {
            // We only return the requested behavior, not the implied one.
            return 0;
        }
        return mViewRoot.mWindowAttributes.insetsFlags.behavior;
    }

    private @InsetsType int calculateControllableTypes() {
        if (!checkDisplayFramesForControlling()) {
            return 0;
        }
        @InsetsType int result = 0;
        for (int i = mSourceConsumers.size() - 1; i >= 0; i--) {
            InsetsSourceConsumer consumer = mSourceConsumers.valueAt(i);
            if (consumer.getControl() != null) {
                result |= toPublicType(consumer.mType);
            }
        }
        return result;
    }

    /**
     * @return The types that are now animating due to a listener invoking control/show/hide
     */
    private @InsetsType int invokeControllableInsetsChangedListeners() {
        mLastStartedAnimTypes = 0;
        @InsetsType int types = calculateControllableTypes();
        int size = mControllableInsetsChangedListeners.size();
        for (int i = 0; i < size; i++) {
            mControllableInsetsChangedListeners.get(i).onControllableInsetsChanged(this, types);
        }
        return mLastStartedAnimTypes;
    }

    @Override
    public void addOnControllableInsetsChangedListener(
            OnControllableInsetsChangedListener listener) {
        Objects.requireNonNull(listener);
        mControllableInsetsChangedListeners.add(listener);
        listener.onControllableInsetsChanged(this, calculateControllableTypes());
    }

    @Override
    public void removeOnControllableInsetsChangedListener(
            OnControllableInsetsChangedListener listener) {
        Objects.requireNonNull(listener);
        mControllableInsetsChangedListeners.remove(listener);
    }

    /**
     * At the time we receive new leashes (e.g. InsetsSourceConsumer is processing
     * setControl) we need to release the old leash. But we may have already scheduled
     * a SyncRtSurfaceTransaction applier to use it from the RenderThread. To avoid
     * synchronization issues we also release from the RenderThread so this release
     * happens after any existing items on the work queue.
     */
    public void releaseSurfaceControlFromRt(SurfaceControl sc) {
        if (mViewRoot.mView != null && mViewRoot.mView.isHardwareAccelerated()) {
            mViewRoot.registerRtFrameCallback(frame -> {
                  sc.release();
            });
            // Make sure a frame gets scheduled.
            mViewRoot.mView.invalidate();
        } else {
            sc.release();
        }
    }
}
