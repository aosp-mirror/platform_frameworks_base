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

import static android.inputmethodservice.InputMethodService.ENABLE_HIDE_IME_CAPTION_BAR;
import static android.os.Trace.TRACE_TAG_VIEW;
import static android.view.InsetsControllerProto.CONTROL;
import static android.view.InsetsControllerProto.STATE;
import static android.view.InsetsSource.ID_IME;
import static android.view.InsetsSource.ID_IME_CAPTION_BAR;
import static android.view.WindowInsets.Type.FIRST;
import static android.view.WindowInsets.Type.LAST;
import static android.view.WindowInsets.Type.all;
import static android.view.WindowInsets.Type.captionBar;
import static android.view.WindowInsets.Type.ime;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TypeEvaluator;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.content.Context;
import android.content.res.CompatibilityInfo;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Trace;
import android.text.TextUtils;
import android.util.IntArray;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.InsetsSourceConsumer.ShowResult;
import android.view.SurfaceControl.Transaction;
import android.view.WindowInsets.Type;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowInsetsAnimation.Bounds;
import android.view.WindowManager.LayoutParams.SoftInputModeFlags;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.ImeTracker.InputMethodJankContext;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.inputmethod.ImeTracing;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.util.function.TriFunction;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Implements {@link WindowInsetsController} on the client.
 * @hide
 */
public class InsetsController implements WindowInsetsController, InsetsAnimationControlCallbacks {

    private int mTypesBeingCancelled;

    public interface Host {

        Handler getHandler();

        /**
         * Notifies host that {@link InsetsController#getState()} has changed.
         */
        void notifyInsetsChanged();

        void dispatchWindowInsetsAnimationPrepare(@NonNull WindowInsetsAnimation animation);
        Bounds dispatchWindowInsetsAnimationStart(
                @NonNull WindowInsetsAnimation animation, @NonNull Bounds bounds);
        WindowInsets dispatchWindowInsetsAnimationProgress(@NonNull WindowInsets insets,
                @NonNull List<WindowInsetsAnimation> runningAnimations);
        void dispatchWindowInsetsAnimationEnd(@NonNull WindowInsetsAnimation animation);

        /**
         * Requests host to apply surface params in synchronized manner.
         */
        void applySurfaceParams(final SyncRtSurfaceTransactionApplier.SurfaceParams... params);

        /**
         * @see ViewRootImpl#updateCompatSysUiVisibility(int, int, int)
         */
        default void updateCompatSysUiVisibility(@InsetsType int visibleTypes,
                @InsetsType int requestedVisibleTypes, @InsetsType int controllableTypes) { }

        /**
         * Called when the requested visibilities of insets have been modified by the client.
         * The visibilities should be reported back to WM.
         *
         * @param types Bitwise flags of types requested visible.
         */
        void updateRequestedVisibleTypes(@InsetsType int types);

        /**
         * @return Whether the host has any callbacks it wants to synchronize the animations with.
         *         If there are no callbacks, the animation will be off-loaded to another thread and
         *         slightly different animation curves are picked.
         */
        boolean hasAnimationCallbacks();

        /**
         * @see WindowInsetsController#setSystemBarsAppearance
         */
        void setSystemBarsAppearance(@Appearance int appearance, @Appearance int mask);

        /**
         * @see WindowInsetsController#getSystemBarsAppearance()
         */
        @Appearance int getSystemBarsAppearance();

        /**
         * @see WindowInsetsController#setSystemBarsBehavior
         */
        void setSystemBarsBehavior(@Behavior int behavior);

        /**
         * @see WindowInsetsController#getSystemBarsBehavior
         */
        @Behavior int getSystemBarsBehavior();

        /**
         * Releases a surface and ensure that this is done after {@link #applySurfaceParams} has
         * finished applying params.
         */
        void releaseSurfaceControlFromRt(SurfaceControl surfaceControl);

        /**
         * If this host is a view hierarchy, adds a pre-draw runnable to ensure proper ordering as
         * described in {@link WindowInsetsAnimation.Callback#onPrepare}.
         *
         * If this host isn't a view hierarchy, the runnable can be executed immediately.
         */
        void addOnPreDrawRunnable(Runnable r);

        /**
         * Adds a runnbale to be executed during {@link Choreographer#CALLBACK_INSETS_ANIMATION}
         * phase.
         */
        void postInsetsAnimationCallback(Runnable r);

        /**
         * Obtains {@link InputMethodManager} instance from host.
         */
        InputMethodManager getInputMethodManager();

        /**
         * @return title of the rootView, if it has one.
         * Note: this method is for debugging purposes only.
         */
        @Nullable
        String getRootViewTitle();

        /**
         * @return the context related to the rootView.
         */
        @Nullable
        default Context getRootViewContext() {
            return null;
        }

        /** @see ViewRootImpl#dipToPx */
        int dipToPx(int dips);

        /**
         * @return token associated with the host, if it has one.
         */
        @Nullable
        IBinder getWindowToken();

        /**
         * @return Translator associated with the host, if it has one.
         */
        @Nullable
        default CompatibilityInfo.Translator getTranslator() {
            return null;
        }

        /**
         * Notifies when the state of running animation is changed. The state is either "running" or
         * "idle".
         *
         * @param running {@code true} if there is any animation running; {@code false} otherwise.
         */
        default void notifyAnimationRunningStateChanged(boolean running) {}

        /** @see ViewRootImpl#isHandlingPointerEvent */
        default boolean isHandlingPointerEvent() {
            return false;
        }
    }

    private static final String TAG = "InsetsController";
    private static final int ANIMATION_DURATION_MOVE_IN_MS = 275;
    private static final int ANIMATION_DURATION_MOVE_OUT_MS = 340;
    private static final int ANIMATION_DURATION_FADE_IN_MS = 500;
    private static final int ANIMATION_DURATION_FADE_OUT_MS = 1500;

    /** Visible for WindowManagerWrapper */
    public static final int ANIMATION_DURATION_RESIZE = 300;

    private static final int ANIMATION_DELAY_DIM_MS = 500;

    private static final int ANIMATION_DURATION_SYNC_IME_MS = 285;
    private static final int ANIMATION_DURATION_UNSYNC_IME_MS = 200;

    private static final int PENDING_CONTROL_TIMEOUT_MS = 2000;

    private static final Interpolator SYSTEM_BARS_INSETS_INTERPOLATOR =
            new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    private static final Interpolator SYSTEM_BARS_ALPHA_INTERPOLATOR =
            new PathInterpolator(0.3f, 0f, 1f, 1f);
    private static final Interpolator SYSTEM_BARS_DIM_INTERPOLATOR = alphaFraction -> {
        // While playing dim animation, alphaFraction is changed from 1f to 0f. Here changes it to
        // time-based fraction for computing delay and interpolation.
        float fraction = 1 - alphaFraction;
        final float fractionDelay = (float) ANIMATION_DELAY_DIM_MS / ANIMATION_DURATION_FADE_OUT_MS;
        if (fraction <= fractionDelay) {
            return 1f;
        } else {
            float innerFraction = (fraction - fractionDelay) / (1f - fractionDelay);
            return 1f - SYSTEM_BARS_ALPHA_INTERPOLATOR.getInterpolation(innerFraction);
        }
    };
    private static final Interpolator SYNC_IME_INTERPOLATOR =
            new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final Interpolator LINEAR_OUT_SLOW_IN_INTERPOLATOR =
            new PathInterpolator(0, 0, 0.2f, 1f);
    private static final Interpolator FAST_OUT_LINEAR_IN_INTERPOLATOR =
            new PathInterpolator(0.4f, 0f, 1f, 1f);

    /** Visible for WindowManagerWrapper */
    public static final Interpolator RESIZE_INTERPOLATOR = new LinearInterpolator();

    /** The amount IME will move up/down when animating in floating mode. */
    private static final int FLOATING_IME_BOTTOM_INSET_DP = -80;

    private static final int ID_CAPTION_BAR =
            InsetsSource.createId(null /* owner */, 0 /* index */, captionBar());

    static final boolean DEBUG = false;
    static final boolean WARN = false;

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
    @VisibleForTesting(visibility = PACKAGE)
    public static final int ANIMATION_TYPE_NONE = -1;

    /** Running animation will show insets */
    public static final int ANIMATION_TYPE_SHOW = 0;

    /** Running animation will hide insets */
    public static final int ANIMATION_TYPE_HIDE = 1;

    /** Running animation is controlled by user via {@link #controlWindowInsetsAnimation} */
    @VisibleForTesting(visibility = PACKAGE)
    public static final int ANIMATION_TYPE_USER = 2;

    /** Running animation will resize insets */
    @VisibleForTesting(visibility = PACKAGE)
    public static final int ANIMATION_TYPE_RESIZE = 3;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {ANIMATION_TYPE_NONE, ANIMATION_TYPE_SHOW, ANIMATION_TYPE_HIDE,
            ANIMATION_TYPE_USER, ANIMATION_TYPE_RESIZE})
    public @interface AnimationType {
    }

    /**
     * Translation animation evaluator.
     */
    private static TypeEvaluator<Insets> sEvaluator = (fraction, startValue, endValue) -> Insets.of(
            (int) (startValue.left + fraction * (endValue.left - startValue.left)),
            (int) (startValue.top + fraction * (endValue.top - startValue.top)),
            (int) (startValue.right + fraction * (endValue.right - startValue.right)),
            (int) (startValue.bottom + fraction * (endValue.bottom - startValue.bottom)));

    /** Logging listener. */
    private WindowInsetsAnimationControlListener mLoggingListener;

    /** Context for {@link android.view.inputmethod.ImeTracker.ImeJankTracker} to monitor jank. */
    private final InputMethodJankContext mJankContext = new InputMethodJankContext() {
        @Override
        public Context getDisplayContext() {
            return mHost != null ? mHost.getRootViewContext() : null;
        }

        @Override
        public SurfaceControl getTargetSurfaceControl() {
            final InsetsSourceControl imeSourceControl = getImeSourceConsumer().getControl();
            return imeSourceControl != null ? imeSourceControl.getLeash() : null;
        }

        @Override
        public String getHostPackageName() {
            return mHost != null ? mHost.getRootViewContext().getPackageName() : null;
        }
    };

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
        private final @Behavior int mBehavior;
        private final long mDurationMs;
        private final boolean mDisable;
        private final int mFloatingImeBottomInset;
        private final WindowInsetsAnimationControlListener mLoggingListener;
        private final InputMethodJankContext mInputMethodJankContext;

        public InternalAnimationControlListener(boolean show, boolean hasAnimationCallbacks,
                @InsetsType int requestedTypes, @Behavior int behavior, boolean disable,
                int floatingImeBottomInset, WindowInsetsAnimationControlListener loggingListener,
                @Nullable InputMethodJankContext jankContext) {
            mShow = show;
            mHasAnimationCallbacks = hasAnimationCallbacks;
            mRequestedTypes = requestedTypes;
            mBehavior = behavior;
            mDurationMs = calculateDurationMs();
            mDisable = disable;
            mFloatingImeBottomInset = floatingImeBottomInset;
            mLoggingListener = loggingListener;
            mInputMethodJankContext = jankContext;
        }

        @Override
        public void onReady(WindowInsetsAnimationController controller, int types) {
            mController = controller;
            if (DEBUG) Log.d(TAG, "default animation onReady types: " + types);
            if (mLoggingListener != null) {
                mLoggingListener.onReady(controller, types);
            }

            if (mDisable) {
                onAnimationFinish();
                return;
            }
            mAnimator = ValueAnimator.ofFloat(0f, 1f);
            mAnimator.setDuration(mDurationMs);
            mAnimator.setInterpolator(new LinearInterpolator());
            Insets hiddenInsets = controller.getHiddenStateInsets();
            // IME with zero insets is a special case: it will animate-in from offscreen and end
            // with final insets of zero and vice-versa.
            hiddenInsets = controller.hasZeroInsetsIme()
                    ? Insets.of(hiddenInsets.left, hiddenInsets.top, hiddenInsets.right,
                            mFloatingImeBottomInset)
                    : hiddenInsets;
            Insets start = mShow
                    ? hiddenInsets
                    : controller.getShownStateInsets();
            Insets end = mShow
                    ? controller.getShownStateInsets()
                    : hiddenInsets;
            Interpolator insetsInterpolator = getInsetsInterpolator();
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
                if (DEBUG) Log.d(TAG, "Default animation setInsetsAndAlpha fraction: "
                        + insetsFraction);
            });
            mAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationStart(Animator animation) {
                    if (mInputMethodJankContext == null) return;
                    ImeTracker.forJank().onRequestAnimation(
                            mInputMethodJankContext,
                            getAnimationType(),
                            !mHasAnimationCallbacks);
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    if (mInputMethodJankContext == null) return;
                    ImeTracker.forJank().onCancelAnimation(getAnimationType());
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    onAnimationFinish();
                    if (mInputMethodJankContext == null) return;
                    ImeTracker.forJank().onFinishAnimation(getAnimationType());
                }
            });
            mAnimator.start();
        }

        @Override
        public void onFinished(WindowInsetsAnimationController controller) {
            if (DEBUG) Log.d(TAG, "InternalAnimationControlListener onFinished types:"
                    + Type.toString(mRequestedTypes));
            if (mLoggingListener != null) {
                mLoggingListener.onFinished(controller);
            }
        }

        @Override
        public void onCancelled(WindowInsetsAnimationController controller) {
            // Animator can be null when it is cancelled before onReady() completes.
            if (mAnimator != null) {
                mAnimator.cancel();
            }
            if (DEBUG) Log.d(TAG, "InternalAnimationControlListener onCancelled types:"
                    + mRequestedTypes);
            if (mLoggingListener != null) {
                mLoggingListener.onCancelled(controller);
            }
        }

        protected Interpolator getInsetsInterpolator() {
            if ((mRequestedTypes & ime()) != 0) {
                if (mHasAnimationCallbacks) {
                    return SYNC_IME_INTERPOLATOR;
                } else if (mShow) {
                    return LINEAR_OUT_SLOW_IN_INTERPOLATOR;
                } else {
                    return FAST_OUT_LINEAR_IN_INTERPOLATOR;
                }
            } else {
                if (mBehavior == BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) {
                    return SYSTEM_BARS_INSETS_INTERPOLATOR;
                } else {
                    // Makes insets stay at the shown position.
                    return input -> mShow ? 1f : 0f;
                }
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
                if (mBehavior == BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) {
                    return input -> 1f;
                } else {
                    if (mShow) {
                        return SYSTEM_BARS_ALPHA_INTERPOLATOR;
                    } else {
                        return SYSTEM_BARS_DIM_INTERPOLATOR;
                    }
                }
            }
        }

        protected void onAnimationFinish() {
            mController.finish(mShow);
            if (DEBUG) Log.d(TAG, "onAnimationFinish showOnFinish: " + mShow);
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
                if (mBehavior == BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) {
                    return mShow ? ANIMATION_DURATION_MOVE_IN_MS : ANIMATION_DURATION_MOVE_OUT_MS;
                } else {
                    return mShow ? ANIMATION_DURATION_FADE_IN_MS : ANIMATION_DURATION_FADE_OUT_MS;
                }
            }
        }

        /**
         * Returns the current animation type.
         */
        @AnimationType
        private int getAnimationType() {
            return mShow ? ANIMATION_TYPE_SHOW : ANIMATION_TYPE_HIDE;
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

        @InsetsType int types;
        final WindowInsetsAnimationControlListener listener;
        final long durationMs;
        final Interpolator interpolator;
        final @AnimationType int animationType;
        final @LayoutInsetsDuringAnimation int layoutInsetsDuringAnimation;
        final CancellationSignal cancellationSignal;
        final boolean useInsetsAnimationThread;
    }

    /** The local state */
    private final InsetsState mState = new InsetsState();

    /** The state dispatched from server */
    private final InsetsState mLastDispatchedState = new InsetsState();

    private final Rect mFrame = new Rect();
    private final TriFunction<InsetsController, Integer, Integer, InsetsSourceConsumer>
            mConsumerCreator;
    private final SparseArray<InsetsSourceConsumer> mSourceConsumers = new SparseArray<>();
    private final InsetsSourceConsumer mImeSourceConsumer;
    private final Host mHost;
    private final Handler mHandler;

    private final SparseArray<InsetsSourceControl> mTmpControlArray = new SparseArray<>();
    private final ArrayList<RunningAnimation> mRunningAnimations = new ArrayList<>();
    private WindowInsets mLastInsets;

    private boolean mAnimCallbackScheduled;

    private final Runnable mAnimCallback;

    /** Pending control request that is waiting on IME to be ready to be shown */
    @Nullable
    private PendingControlRequest mPendingImeControlRequest;

    private int mWindowType;
    private int mLastLegacySoftInputMode;
    private int mLastLegacyWindowFlags;
    private int mLastLegacySystemUiFlags;
    private int mLastActivityType;
    private boolean mStartingAnimation;
    private int mCaptionInsetsHeight = 0;
    private int mImeCaptionBarInsetsHeight = 0;
    private boolean mAnimationsDisabled;
    private boolean mCompatSysUiVisibilityStaled;
    private @Appearance int mAppearanceControlled;
    private @Appearance int mAppearanceFromResource;
    private boolean mBehaviorControlled;
    private boolean mIsPredictiveBackImeHideAnimInProgress;

    private final Runnable mPendingControlTimeout = this::abortPendingImeControlRequest;
    private final ArrayList<OnControllableInsetsChangedListener> mControllableInsetsChangedListeners
            = new ArrayList<>();

    /** Set of inset types for which an animation was started since last resetting this field */
    private @InsetsType int mLastStartedAnimTypes;

    /** Set of inset types which are existing */
    private @InsetsType int mExistingTypes = 0;

    /** Set of inset types which are visible */
    private @InsetsType int mVisibleTypes = WindowInsets.Type.defaultVisible();

    /** Set of inset types which are requested visible */
    private @InsetsType int mRequestedVisibleTypes = WindowInsets.Type.defaultVisible();

    /** Set of inset types which are requested visible which are reported to the host */
    private @InsetsType int mReportedRequestedVisibleTypes = WindowInsets.Type.defaultVisible();

    /** Set of inset types that we have controls of */
    private @InsetsType int mControllableTypes;

    private final Runnable mInvokeControllableInsetsChangedListeners =
            this::invokeControllableInsetsChangedListeners;

    private final InsetsState.OnTraverseCallbacks mRemoveGoneSources =
            new InsetsState.OnTraverseCallbacks() {

                private final IntArray mPendingRemoveIndexes = new IntArray();

                @Override
                public void onIdNotFoundInState2(int index1, InsetsSource source1) {
                    if (source1.getId() == ID_IME_CAPTION_BAR) {
                        return;
                    }

                    // Don't change the indexes of the sources while traversing. Remove it later.
                    mPendingRemoveIndexes.add(index1);
                }

                @Override
                public void onFinish(InsetsState state1, InsetsState state2) {
                    for (int i = mPendingRemoveIndexes.size() - 1; i >= 0; i--) {
                        state1.removeSourceAt(mPendingRemoveIndexes.get(i));
                    }
                    mPendingRemoveIndexes.clear();
                }
            };

    private final InsetsState.OnTraverseCallbacks mStartResizingAnimationIfNeeded =
            new InsetsState.OnTraverseCallbacks() {

                private @InsetsType int mTypes;
                private InsetsState mFromState;
                private InsetsState mToState;

                @Override
                public void onStart(InsetsState state1, InsetsState state2) {
                    mTypes = 0;
                    mFromState = null;
                    mToState = null;
                }

                @Override
                public void onIdMatch(InsetsSource source1, InsetsSource source2) {
                    final Rect frame1 = source1.getFrame();
                    final Rect frame2 = source2.getFrame();
                    if (!source1.hasFlags(InsetsSource.FLAG_ANIMATE_RESIZING)
                            || !source2.hasFlags(InsetsSource.FLAG_ANIMATE_RESIZING)
                            || !source1.isVisible() || !source2.isVisible()
                            || frame1.equals(frame2) || frame1.isEmpty() || frame2.isEmpty()
                            || !(Rect.intersects(mFrame, source1.getFrame())
                                    || Rect.intersects(mFrame, source2.getFrame()))) {
                        return;
                    }
                    mTypes |= source1.getType();
                    if (mFromState == null) {
                        mFromState = new InsetsState();
                    }
                    if (mToState == null) {
                        mToState = new InsetsState();
                    }
                    mFromState.addSource(new InsetsSource(source1));
                    mToState.addSource(new InsetsSource(source2));
                }

                @Override
                public void onFinish(InsetsState state1, InsetsState state2) {
                    if (mTypes == 0) {
                        return;
                    }
                    cancelExistingControllers(mTypes);
                    final InsetsAnimationControlRunner runner = new InsetsResizeAnimationRunner(
                            mFrame, mFromState, mToState, RESIZE_INTERPOLATOR,
                            ANIMATION_DURATION_RESIZE, mTypes, InsetsController.this);
                    if (mRunningAnimations.isEmpty()) {
                        mHost.notifyAnimationRunningStateChanged(true);
                    }
                    mRunningAnimations.add(new RunningAnimation(runner, runner.getAnimationType()));
                }
            };

    public InsetsController(Host host) {
        this(host, (controller, id, type) -> {
            if (type == ime()) {
                return new ImeInsetsSourceConsumer(id, controller.mState,
                        Transaction::new, controller);
            } else {
                return new InsetsSourceConsumer(id, type, controller.mState,
                        Transaction::new, controller);
            }
        }, host.getHandler());
    }

    @VisibleForTesting
    public InsetsController(Host host,
            TriFunction<InsetsController, Integer, Integer, InsetsSourceConsumer> consumerCreator,
            Handler handler) {
        mHost = host;
        mConsumerCreator = consumerCreator;
        mHandler = handler;
        mAnimCallback = () -> {
            mAnimCallbackScheduled = false;
            if (mRunningAnimations.isEmpty()) {
                return;
            }

            final List<WindowInsetsAnimation> runningAnimations = new ArrayList<>();
            final List<WindowInsetsAnimation> finishedAnimations = new ArrayList<>();
            final InsetsState state = new InsetsState(mState, true /* copySources */);
            for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
                RunningAnimation runningAnimation = mRunningAnimations.get(i);
                if (DEBUG) Log.d(TAG, "Running animation type: " + runningAnimation.type);
                final InsetsAnimationControlRunner runner = runningAnimation.runner;
                if (runner instanceof WindowInsetsAnimationController) {

                    // Keep track of running animation to be dispatched. Aggregate it here such that
                    // if it gets finished within applyChangeInsets we still dispatch it to
                    // onProgress.
                    if (runningAnimation.startDispatched) {
                        runningAnimations.add(runner.getAnimation());
                    }

                    if (((InternalInsetsAnimationController) runner).applyChangeInsets(state)) {
                        finishedAnimations.add(runner.getAnimation());
                    }
                }
            }

            WindowInsets insets = state.calculateInsets(mFrame,
                    mState /* ignoringVisibilityState */, mLastInsets.isRound(),
                    mLastLegacySoftInputMode, mLastLegacyWindowFlags, mLastLegacySystemUiFlags,
                    mWindowType, mLastActivityType, null /* idSideMap */);
            mHost.dispatchWindowInsetsAnimationProgress(insets,
                    Collections.unmodifiableList(runningAnimations));
            if (DEBUG) {
                for (WindowInsetsAnimation anim : runningAnimations) {
                    Log.d(TAG, String.format("Running animation type: %d, progress: %f",
                            anim.getTypeMask(), anim.getInterpolatedFraction()));
                }
            }

            for (int i = finishedAnimations.size() - 1; i >= 0; i--) {
                dispatchAnimationEnd(finishedAnimations.get(i));
            }
        };

        // Make mImeSourceConsumer always non-null.
        mImeSourceConsumer = getSourceConsumer(ID_IME, ime());
    }

    @VisibleForTesting
    public void onFrameChanged(Rect frame) {
        if (mFrame.equals(frame)) {
            return;
        }
        if (mImeCaptionBarInsetsHeight != 0) {
            setImeCaptionBarInsetsHeight(mImeCaptionBarInsetsHeight);
        }
        mHost.notifyInsetsChanged();
        mFrame.set(frame);
    }

    @Override
    public InsetsState getState() {
        return mState;
    }

    @Override
    public @InsetsType int getRequestedVisibleTypes() {
        return mRequestedVisibleTypes;
    }

    public InsetsState getLastDispatchedState() {
        return mLastDispatchedState;
    }

    public boolean onStateChanged(InsetsState state) {
        boolean stateChanged = !mState.equals(state, false /* excludesCaptionBar */,
                false /* excludesInvisibleIme */);
        if (!stateChanged && mLastDispatchedState.equals(state)) {
            return false;
        }
        if (DEBUG) Log.d(TAG, "onStateChanged: " + state);

        final InsetsState lastState = new InsetsState(mState, true /* copySources */);
        updateState(state);
        applyLocalVisibilityOverride();
        updateCompatSysUiVisibility();

        if (!mState.equals(lastState, false /* excludesCaptionBar */,
                true /* excludesInvisibleIme */)) {
            if (DEBUG) Log.d(TAG, "onStateChanged, notifyInsetsChanged");
            mHost.notifyInsetsChanged();
            if (mLastDispatchedState.getDisplayFrame().equals(state.getDisplayFrame())) {
                // Here compares the raw states instead of the overridden ones because we don't want
                // to animate an insets source that its mServerVisible is false.
                InsetsState.traverse(mLastDispatchedState, state, mStartResizingAnimationIfNeeded);
            }
        }
        mLastDispatchedState.set(state, true /* copySources */);
        return true;
    }

    private void updateState(InsetsState newState) {
        mState.set(newState, 0 /* types */);
        @InsetsType int existingTypes = 0;
        @InsetsType int visibleTypes = 0;
        @InsetsType int[] cancelledUserAnimationTypes = {0};
        for (int i = 0, size = newState.sourceSize(); i < size; i++) {
            final InsetsSource source = newState.sourceAt(i);
            @InsetsType int type = source.getType();
            @AnimationType int animationType = getAnimationType(type);
            final InsetsSourceConsumer consumer = mSourceConsumers.get(source.getId());
            if (consumer != null) {
                consumer.updateSource(source, animationType);
            } else {
                mState.addSource(source);
            }
            existingTypes |= type;
            if (source.isVisible()) {
                visibleTypes |= type;
            }
        }

        // If a type doesn't have a source, treat it as visible if it is visible by default.
        visibleTypes |= WindowInsets.Type.defaultVisible() & ~existingTypes;

        if (mVisibleTypes != visibleTypes) {
            if (WindowInsets.Type.hasCompatSystemBars(mVisibleTypes ^ visibleTypes)) {
                mCompatSysUiVisibilityStaled = true;
            }
            mVisibleTypes = visibleTypes;
        }
        if (mExistingTypes != existingTypes) {
            if (WindowInsets.Type.hasCompatSystemBars(mExistingTypes ^ existingTypes)) {
                mCompatSysUiVisibilityStaled = true;
            }
            mExistingTypes = existingTypes;
        }
        InsetsState.traverse(mState, newState, mRemoveGoneSources);

        if (cancelledUserAnimationTypes[0] != 0) {
            mHandler.post(() -> show(cancelledUserAnimationTypes[0]));
        }
    }

    /**
     * @see InsetsState#calculateInsets(Rect, InsetsState, boolean, int, int, int, int, int,
     *      android.util.SparseIntArray)
     */
    @VisibleForTesting
    public WindowInsets calculateInsets(boolean isScreenRound, int windowType, int activityType,
            int legacySoftInputMode, int legacyWindowFlags, int legacySystemUiFlags) {
        mWindowType = windowType;
        mLastActivityType = activityType;
        mLastLegacySoftInputMode = legacySoftInputMode;
        mLastLegacyWindowFlags = legacyWindowFlags;
        mLastLegacySystemUiFlags = legacySystemUiFlags;
        mLastInsets = mState.calculateInsets(mFrame, null /* ignoringVisibilityState */,
                isScreenRound, legacySoftInputMode, legacyWindowFlags,
                legacySystemUiFlags, windowType, activityType, null /* idSideMap */);
        return mLastInsets;
    }

    /**
     * @see InsetsState#calculateVisibleInsets(Rect, int, int, int, int)
     */
    public Insets calculateVisibleInsets(int windowType, int activityType,
            @SoftInputModeFlags int softInputMode, int windowFlags) {
        return mState.calculateVisibleInsets(mFrame, windowType, activityType, softInputMode,
                windowFlags);
    }

    /**
     * Called when the server has dispatched us a new set of inset controls.
     */
    public void onControlsChanged(InsetsSourceControl[] activeControls) {
        if (activeControls != null) {
            for (InsetsSourceControl activeControl : activeControls) {
                if (activeControl != null) {
                    // TODO(b/122982984): Figure out why it can be null.
                    mTmpControlArray.put(activeControl.getId(), activeControl);
                }
            }
        }

        @InsetsType int controllableTypes = 0;
        int consumedControlCount = 0;
        final @InsetsType int[] showTypes = new int[1];
        final @InsetsType int[] hideTypes = new int[1];

        // Ensure to update all existing source consumers
        for (int i = mSourceConsumers.size() - 1; i >= 0; i--) {
            final InsetsSourceConsumer consumer = mSourceConsumers.valueAt(i);
            if (consumer.getId() == ID_IME_CAPTION_BAR) {
                // The inset control for the IME caption bar will never be dispatched
                // by the server.
                continue;
            }

            final InsetsSourceControl control = mTmpControlArray.get(consumer.getId());
            if (control != null) {
                controllableTypes |= control.getType();
                consumedControlCount++;
            }

            // control may be null, but we still need to update the control to null if it got
            // revoked.
            consumer.setControl(control, showTypes, hideTypes);
        }

        // Ensure to create source consumers if not available yet.
        if (consumedControlCount != mTmpControlArray.size()) {
            for (int i = mTmpControlArray.size() - 1; i >= 0; i--) {
                final InsetsSourceControl control = mTmpControlArray.valueAt(i);
                getSourceConsumer(control.getId(), control.getType())
                        .setControl(control, showTypes, hideTypes);
            }
        }

        if (mTmpControlArray.size() > 0) {
            // Update surface positions for animations.
            for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
                mRunningAnimations.get(i).runner.updateSurfacePosition(mTmpControlArray);
            }
        }
        mTmpControlArray.clear();

        // Do not override any animations that the app started in the OnControllableInsetsChanged
        // listeners.
        int animatingTypes = invokeControllableInsetsChangedListeners();
        showTypes[0] &= ~animatingTypes;
        hideTypes[0] &= ~animatingTypes;

        if (showTypes[0] != 0) {
            final var statsToken = (showTypes[0] & ime()) == 0 ? null
                    : ImeTracker.forLogging().onStart(ImeTracker.TYPE_SHOW,
                            ImeTracker.ORIGIN_CLIENT, SoftInputShowHideReason.CONTROLS_CHANGED,
                            mHost.isHandlingPointerEvent() /* fromUser */);
            applyAnimation(showTypes[0], true /* show */, false /* fromIme */, statsToken);
        }
        if (hideTypes[0] != 0) {
            final var statsToken = (hideTypes[0] & ime()) == 0 ? null
                    : ImeTracker.forLogging().onStart(ImeTracker.TYPE_HIDE,
                            ImeTracker.ORIGIN_CLIENT, SoftInputShowHideReason.CONTROLS_CHANGED,
                            mHost.isHandlingPointerEvent() /* fromUser */);
            applyAnimation(hideTypes[0], false /* show */, false /* fromIme */, statsToken);
        }

        if (mControllableTypes != controllableTypes) {
            if (WindowInsets.Type.hasCompatSystemBars(mControllableTypes ^ controllableTypes)) {
                mCompatSysUiVisibilityStaled = true;
            }
            mControllableTypes = controllableTypes;
        }

        // InsetsSourceConsumer#setControl might change the requested visibility.
        reportRequestedVisibleTypes();
    }

    @VisibleForTesting(visibility = PACKAGE)
    public void setPredictiveBackImeHideAnimInProgress(boolean isInProgress) {
        mIsPredictiveBackImeHideAnimInProgress = isInProgress;
        if (isInProgress) {
            // The InsetsAnimationControlRunner has layoutInsetsDuringAnimation set to SHOWN during
            // predictive back. Let's set it to HIDDEN once the predictive back animation enters the
            // post-commit phase.
            // That prevents flickers in case the animation is cancelled by an incoming show request
            // during the hide animation.
            for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
                final InsetsAnimationControlRunner runner = mRunningAnimations.get(i).runner;
                if ((runner.getTypes() & ime()) != 0) {
                    runner.updateLayoutInsetsDuringAnimation(LAYOUT_INSETS_DURING_ANIMATION_HIDDEN);
                    break;
                }
            }
        }
    }

    boolean isPredictiveBackImeHideAnimInProgress() {
        return mIsPredictiveBackImeHideAnimInProgress;
    }

    @Override
    public void show(@InsetsType int types) {
        show(types, false /* fromIme */, null /* statsToken */);
    }

    @VisibleForTesting(visibility = PACKAGE)
    public void show(@InsetsType int types, boolean fromIme,
            @Nullable ImeTracker.Token statsToken) {
        if ((types & ime()) != 0) {
            Log.d(TAG, "show(ime(), fromIme=" + fromIme + ")");

            if (statsToken == null) {
                statsToken = ImeTracker.forLogging().onStart(ImeTracker.TYPE_SHOW,
                        ImeTracker.ORIGIN_CLIENT,
                        SoftInputShowHideReason.SHOW_SOFT_INPUT_BY_INSETS_API,
                        mHost.isHandlingPointerEvent() /* fromUser */);
            }
        }
        if (fromIme) {
            ImeTracing.getInstance().triggerClientDump("InsetsController#show",
                    mHost.getInputMethodManager(), null /* icProto */);
            Trace.asyncTraceEnd(TRACE_TAG_VIEW, "IC.showRequestFromApiToImeReady", 0);
            Trace.asyncTraceBegin(TRACE_TAG_VIEW, "IC.showRequestFromIme", 0);
        } else {
            Trace.asyncTraceBegin(TRACE_TAG_VIEW, "IC.showRequestFromApi", 0);
            Trace.asyncTraceBegin(TRACE_TAG_VIEW, "IC.showRequestFromApiToImeReady", 0);
        }
        // Handle pending request ready in case there was one set.
        if (fromIme && mPendingImeControlRequest != null) {
            if ((types & Type.ime()) != 0) {
                ImeTracker.forLatency().onShown(statsToken, ActivityThread::currentApplication);
            }
            handlePendingControlRequest(statsToken);
            return;
        }

        // TODO: Support a ResultReceiver for IME.
        // TODO(b/123718661): Make show() work for multi-session IME.
        int typesReady = 0;
        final boolean imeVisible = mState.isSourceOrDefaultVisible(
                mImeSourceConsumer.getId(), ime());
        for (int type = FIRST; type <= LAST; type = type << 1) {
            if ((types & type) == 0) {
                continue;
            }
            @AnimationType final int animationType = getAnimationType(type);
            final boolean requestedVisible = (type & mRequestedVisibleTypes) != 0;
            final boolean isIme = type == ime();
            var alreadyVisible = requestedVisible && (!isIme || imeVisible)
                    && animationType == ANIMATION_TYPE_NONE;
            var alreadyAnimatingShow = animationType == ANIMATION_TYPE_SHOW;
            if (alreadyVisible || alreadyAnimatingShow) {
                // no-op: already shown or animating in (because window visibility is
                // applied before starting animation).
                if (DEBUG) Log.d(TAG, String.format(
                        "show ignored for type: %d animType: %d requestedVisible: %s",
                        type, animationType, requestedVisible));
                if (isIme) {
                    ImeTracker.forLogging().onCancelled(statsToken,
                            ImeTracker.PHASE_CLIENT_APPLY_ANIMATION);
                }
                continue;
            }
            if (fromIme && animationType == ANIMATION_TYPE_USER
                    && !mIsPredictiveBackImeHideAnimInProgress) {
                // App is already controlling the IME, don't cancel it.
                if (isIme) {
                    ImeTracker.forLogging().onFailed(
                            statsToken, ImeTracker.PHASE_CLIENT_APPLY_ANIMATION);
                }
                continue;
            }
            if (isIme) {
                ImeTracker.forLogging().onProgress(
                        statsToken, ImeTracker.PHASE_CLIENT_APPLY_ANIMATION);
            }
            typesReady |= type;
        }
        if (DEBUG) Log.d(TAG, "show typesReady: " + typesReady);
        if (fromIme && (typesReady & Type.ime()) != 0) {
            ImeTracker.forLatency().onShown(statsToken, ActivityThread::currentApplication);
        }
        applyAnimation(typesReady, true /* show */, fromIme, statsToken);
    }

    /**
     * Handle the {@link #mPendingImeControlRequest} when:
     * <ul>
     *     <li> The IME insets is ready to show.
     *     <li> The IME insets has being requested invisible.
     * </ul>
     */
    private void handlePendingControlRequest(@Nullable ImeTracker.Token statsToken) {
        PendingControlRequest pendingRequest = mPendingImeControlRequest;
        mPendingImeControlRequest = null;
        mHandler.removeCallbacks(mPendingControlTimeout);

        // We are about to playing the default animation. Passing a null frame indicates the
        // controlled types should be animated regardless of the frame.
        controlAnimationUnchecked(
                pendingRequest.types, pendingRequest.cancellationSignal,
                pendingRequest.listener, null /* frame */,
                true /* fromIme */, pendingRequest.durationMs, pendingRequest.interpolator,
                pendingRequest.animationType,
                pendingRequest.layoutInsetsDuringAnimation,
                pendingRequest.useInsetsAnimationThread, statsToken);
    }

    @Override
    public void hide(@InsetsType int types) {
        hide(types, false /* fromIme */, null /* statsToken */);
    }

    @VisibleForTesting
    public void hide(@InsetsType int types, boolean fromIme,
            @Nullable ImeTracker.Token statsToken) {
        if ((types & ime()) != 0) {
            Log.d(TAG, "hide(ime(), fromIme=" + fromIme + ")");

            if (statsToken == null) {
                statsToken = ImeTracker.forLogging().onStart(ImeTracker.TYPE_HIDE,
                        ImeTracker.ORIGIN_CLIENT,
                        SoftInputShowHideReason.HIDE_SOFT_INPUT_BY_INSETS_API,
                        mHost.isHandlingPointerEvent() /* fromUser */);
            }
        }
        if (fromIme) {
            ImeTracing.getInstance().triggerClientDump("InsetsController#hide",
                    mHost.getInputMethodManager(), null /* icProto */);
            Trace.asyncTraceBegin(TRACE_TAG_VIEW, "IC.hideRequestFromIme", 0);
        } else {
            Trace.asyncTraceBegin(TRACE_TAG_VIEW, "IC.hideRequestFromApi", 0);
        }
        int typesReady = 0;
        boolean hasImeRequestedHidden = false;
        final boolean hadPendingImeControlRequest = mPendingImeControlRequest != null;
        for (int type = FIRST; type <= LAST; type = type << 1) {
            if ((types & type) == 0) {
                continue;
            }
            @AnimationType final int animationType = getAnimationType(type);
            final boolean requestedVisible = (type & mRequestedVisibleTypes) != 0;
            final boolean isImeAnimation = type == ime();
            if (mPendingImeControlRequest != null && !requestedVisible) {
                // Remove the hide insets type from the pending show request.
                mPendingImeControlRequest.types &= ~type;
                if (mPendingImeControlRequest.types == 0) {
                    abortPendingImeControlRequest();
                }
            }
            if (isImeAnimation && !requestedVisible && animationType == ANIMATION_TYPE_NONE) {
                hasImeRequestedHidden = true;
                // Ensure to request hide IME in case there is any pending requested visible
                // being applied from setControl when receiving the insets control.
                if (hadPendingImeControlRequest
                        || getImeSourceConsumer().isRequestedVisibleAwaitingControl()) {
                    getImeSourceConsumer().requestHide(fromIme, statsToken);
                }
            }
            if (!requestedVisible && animationType == ANIMATION_TYPE_NONE
                    || animationType == ANIMATION_TYPE_HIDE || (animationType
                    == ANIMATION_TYPE_USER && mIsPredictiveBackImeHideAnimInProgress)) {
                // no-op: already hidden or animating out (because window visibility is
                // applied before starting animation).
                if (isImeAnimation) {
                    ImeTracker.forLogging().onCancelled(statsToken,
                            ImeTracker.PHASE_CLIENT_APPLY_ANIMATION);
                }
                continue;
            }
            if (isImeAnimation) {
                ImeTracker.forLogging().onProgress(
                        statsToken, ImeTracker.PHASE_CLIENT_APPLY_ANIMATION);
            }
            typesReady |= type;
        }
        if (hasImeRequestedHidden && mPendingImeControlRequest != null) {
            // Handle the pending show request for other insets types since the IME insets has being
            // requested hidden.
            handlePendingControlRequest(statsToken);
            getImeSourceConsumer().removeSurface();
        }
        applyAnimation(typesReady, false /* show */, fromIme, statsToken);
    }

    @Override
    public void controlWindowInsetsAnimation(@InsetsType int types, long durationMillis,
            @Nullable Interpolator interpolator,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull WindowInsetsAnimationControlListener listener) {
        controlWindowInsetsAnimation(types, cancellationSignal, listener,
                false /* fromIme */, durationMillis, interpolator, ANIMATION_TYPE_USER,
                false /* fromPredictiveBack */);
    }

    @VisibleForTesting(visibility = PACKAGE)
    public void controlWindowInsetsAnimation(@InsetsType int types,
            @Nullable CancellationSignal cancellationSignal,
            WindowInsetsAnimationControlListener listener,
            boolean fromIme, long durationMs, @Nullable Interpolator interpolator,
            @AnimationType int animationType, boolean fromPredictiveBack) {
        if ((mState.calculateUncontrollableInsetsFromFrame(mFrame) & types) != 0) {
            listener.onCancelled(null);
            return;
        }
        if (fromIme) {
            ImeTracing.getInstance().triggerClientDump(
                    "InsetsController#controlWindowInsetsAnimation",
                    mHost.getInputMethodManager(), null /* icProto */);
        }

        // TODO(b/342111149): Create statsToken here once ImeTracker#onStart becomes async.
        controlAnimationUnchecked(types, cancellationSignal, listener, mFrame, fromIme, durationMs,
                interpolator, animationType,
                getLayoutInsetsDuringAnimationMode(types, fromPredictiveBack),
                false /* useInsetsAnimationThread */, null);
    }

    private void controlAnimationUnchecked(@InsetsType int types,
            @Nullable CancellationSignal cancellationSignal,
            WindowInsetsAnimationControlListener listener, @Nullable Rect frame, boolean fromIme,
            long durationMs, Interpolator interpolator,
            @AnimationType int animationType,
            @LayoutInsetsDuringAnimation int layoutInsetsDuringAnimation,
            boolean useInsetsAnimationThread, @Nullable ImeTracker.Token statsToken) {
        final boolean visible = layoutInsetsDuringAnimation == LAYOUT_INSETS_DURING_ANIMATION_SHOWN;

        // Basically, we accept the requested visibilities from the upstream callers...
        setRequestedVisibleTypes(visible ? types : 0, types);

        // However, we might reject the request in some cases, such as delaying showing IME or
        // rejecting showing IME.
        controlAnimationUncheckedInner(types, cancellationSignal, listener, frame, fromIme,
                durationMs, interpolator, animationType, layoutInsetsDuringAnimation,
                useInsetsAnimationThread, statsToken);

        // We are finishing setting the requested visible types. Report them to the server and/or
        // the app.
        reportRequestedVisibleTypes();
    }

    private void controlAnimationUncheckedInner(@InsetsType int types,
            @Nullable CancellationSignal cancellationSignal,
            WindowInsetsAnimationControlListener listener, @Nullable Rect frame, boolean fromIme,
            long durationMs, Interpolator interpolator,
            @AnimationType int animationType,
            @LayoutInsetsDuringAnimation int layoutInsetsDuringAnimation,
            boolean useInsetsAnimationThread, @Nullable ImeTracker.Token statsToken) {
        if ((types & mTypesBeingCancelled) != 0) {
            final boolean monitoredAnimation =
                    animationType == ANIMATION_TYPE_SHOW || animationType == ANIMATION_TYPE_HIDE;
            if (monitoredAnimation && (types & Type.ime()) != 0) {
                if (animationType == ANIMATION_TYPE_SHOW) {
                    ImeTracker.forLatency().onShowCancelled(statsToken,
                            ImeTracker.PHASE_CLIENT_ANIMATION_CANCEL,
                            ActivityThread::currentApplication);
                } else {
                    ImeTracker.forLatency().onHideCancelled(statsToken,
                            ImeTracker.PHASE_CLIENT_ANIMATION_CANCEL,
                            ActivityThread::currentApplication);
                }
                ImeTracker.forLogging().onCancelled(statsToken,
                        ImeTracker.PHASE_CLIENT_CONTROL_ANIMATION);
            }
            throw new IllegalStateException("Cannot start a new insets animation of "
                    + Type.toString(types)
                    + " while an existing " + Type.toString(mTypesBeingCancelled)
                    + " is being cancelled.");
        }
        ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_CLIENT_CONTROL_ANIMATION);
        if (types == 0) {
            // nothing to animate.
            listener.onCancelled(null);
            if (DEBUG) Log.d(TAG, "no types to animate in controlAnimationUnchecked");
            Trace.asyncTraceEnd(TRACE_TAG_VIEW, "IC.showRequestFromApi", 0);
            Trace.asyncTraceEnd(TRACE_TAG_VIEW, "IC.showRequestFromApiToImeReady", 0);
            return;
        }
        if (DEBUG) Log.d(TAG, "controlAnimation types: " + types);
        mLastStartedAnimTypes |= types;

        final SparseArray<InsetsSourceControl> controls = new SparseArray<>();

        Pair<Integer, Boolean> typesReadyPair = collectSourceControls(
                fromIme, types, controls, animationType, statsToken);
        int typesReady = typesReadyPair.first;
        boolean imeReady = typesReadyPair.second;
        if (DEBUG) Log.d(TAG, String.format(
                "controlAnimationUnchecked, typesReady: %s imeReady: %s", typesReady, imeReady));
        if (!imeReady) {
            // IME isn't ready, all requested types will be animated once IME is ready
            abortPendingImeControlRequest();
            final PendingControlRequest request = new PendingControlRequest(types,
                    listener, durationMs,
                    interpolator, animationType, layoutInsetsDuringAnimation, cancellationSignal,
                    useInsetsAnimationThread);
            mPendingImeControlRequest = request;
            mHandler.postDelayed(mPendingControlTimeout, PENDING_CONTROL_TIMEOUT_MS);
            if (DEBUG) Log.d(TAG, "Ime not ready. Create pending request");
            if (cancellationSignal != null) {
                cancellationSignal.setOnCancelListener(() -> {
                    if (mPendingImeControlRequest == request) {
                        if (DEBUG) Log.d(TAG,
                                "Cancellation signal abortPendingImeControlRequest");
                        abortPendingImeControlRequest();
                    }
                });
            }

            // The leashes are copied, but they won't be used.
            releaseControls(controls);

            // The requested visibilities should be delayed as well. Otherwise, we might override
            // the insets visibility before playing animation.
            setRequestedVisibleTypes(mReportedRequestedVisibleTypes, types);

            Trace.asyncTraceEnd(TRACE_TAG_VIEW, "IC.showRequestFromApi", 0);
            if (!fromIme) {
                Trace.asyncTraceEnd(TRACE_TAG_VIEW, "IC.showRequestFromApiToImeReady", 0);
            }
            return;
        }

        if (typesReady == 0) {
            if (DEBUG) Log.d(TAG, "No types ready. onCancelled()");
            listener.onCancelled(null);
            Trace.asyncTraceEnd(TRACE_TAG_VIEW, "IC.showRequestFromApi", 0);
            if (!fromIme) {
                Trace.asyncTraceEnd(TRACE_TAG_VIEW, "IC.showRequestFromApiToImeReady", 0);
            }
            return;
        }

        cancelExistingControllers(typesReady);

        final InsetsAnimationControlRunner runner = useInsetsAnimationThread
                ? new InsetsAnimationThreadControlRunner(controls,
                        frame, mState, listener, typesReady, this, durationMs, interpolator,
                        animationType, layoutInsetsDuringAnimation, mHost.getTranslator(),
                        mHost.getHandler(), statsToken)
                : new InsetsAnimationControlImpl(controls,
                        frame, mState, listener, typesReady, this, durationMs, interpolator,
                        animationType, layoutInsetsDuringAnimation, mHost.getTranslator(),
                        statsToken);
        if ((typesReady & WindowInsets.Type.ime()) != 0) {
            ImeTracing.getInstance().triggerClientDump("InsetsAnimationControlImpl",
                    mHost.getInputMethodManager(), null /* icProto */);
            if (animationType == ANIMATION_TYPE_HIDE) {
                ImeTracker.forLatency().onHidden(statsToken, ActivityThread::currentApplication);
            }
        }
        ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_CLIENT_ANIMATION_RUNNING);
        if (mRunningAnimations.isEmpty()) {
            mHost.notifyAnimationRunningStateChanged(true);
        }
        mRunningAnimations.add(new RunningAnimation(runner, animationType));
        if (DEBUG) Log.d(TAG, "Animation added to runner. useInsetsAnimationThread: "
                + useInsetsAnimationThread);
        if (cancellationSignal != null) {
            cancellationSignal.setOnCancelListener(() -> {
                cancelAnimation(runner, true /* invokeCallback */);
            });
        } else {
            Trace.asyncTraceBegin(TRACE_TAG_VIEW, "IC.pendingAnim", 0);
        }
        onAnimationStateChanged(types, true /* running */);

        if (fromIme) {
            switch (animationType) {
                case ANIMATION_TYPE_SHOW:
                    Trace.asyncTraceEnd(TRACE_TAG_VIEW, "IC.showRequestFromIme", 0);
                    break;
                case ANIMATION_TYPE_HIDE:
                    Trace.asyncTraceEnd(TRACE_TAG_VIEW, "IC.hideRequestFromIme", 0);
                    break;
            }
        } else if (animationType == ANIMATION_TYPE_HIDE) {
            Trace.asyncTraceEnd(TRACE_TAG_VIEW, "IC.hideRequestFromApi", 0);
        }
    }

    static void releaseControls(SparseArray<InsetsSourceControl> controls) {
        for (int i = controls.size() - 1; i >= 0; i--) {
            controls.valueAt(i).release(SurfaceControl::release);
        }
    }

    // TODO(b/242962223): Make this setter restrictive.
    @Override
    public void setSystemDrivenInsetsAnimationLoggingListener(
            @Nullable WindowInsetsAnimationControlListener listener) {
        mLoggingListener = listener;
    }

    /**
     * @return Pair of (types ready to animate, IME ready to animate).
     */
    private Pair<Integer, Boolean> collectSourceControls(boolean fromIme, @InsetsType int types,
            SparseArray<InsetsSourceControl> controls, @AnimationType int animationType,
            @Nullable ImeTracker.Token statsToken) {
        ImeTracker.forLogging().onProgress(statsToken,
                ImeTracker.PHASE_CLIENT_COLLECT_SOURCE_CONTROLS);

        int typesReady = 0;
        boolean imeReady = true;
        for (int i = mSourceConsumers.size() - 1; i >= 0; i--) {
            final InsetsSourceConsumer consumer = mSourceConsumers.valueAt(i);
            if ((consumer.getType() & types) == 0) {
                continue;
            }
            boolean show = animationType == ANIMATION_TYPE_SHOW
                    || animationType == ANIMATION_TYPE_USER;
            boolean canRun = true;
            if (show) {
                // Show request
                switch(consumer.requestShow(fromIme, statsToken)) {
                    case ShowResult.SHOW_IMMEDIATELY:
                        break;
                    case ShowResult.IME_SHOW_DELAYED:
                        imeReady = false;
                        if (DEBUG) Log.d(TAG, "requestShow IME_SHOW_DELAYED");
                        break;
                    case ShowResult.IME_SHOW_FAILED:
                        if (WARN) Log.w(TAG, "requestShow IME_SHOW_FAILED. fromIme: "
                                + fromIme);
                        // IME cannot be shown (since it didn't have focus), proceed
                        // with animation of other types.
                        canRun = false;

                        // Reject the show request.
                        setRequestedVisibleTypes(0 /* visibleTypes */, consumer.getType());
                        break;
                }
            } else {
                consumer.requestHide(fromIme, statsToken);
            }
            if (!canRun) {
                if (WARN) Log.w(TAG, String.format(
                        "collectSourceControls can't continue show for type: %s fromIme: %b",
                        WindowInsets.Type.toString(consumer.getType()), fromIme));
                continue;
            }
            final InsetsSourceControl control = consumer.getControl();
            if (control != null
                    && (control.getLeash() != null || control.getId() == ID_IME_CAPTION_BAR)) {
                controls.put(control.getId(), new InsetsSourceControl(control));
                typesReady |= consumer.getType();
            } else if (fromIme) {
                Log.w(TAG, "collectSourceControls can't continue for type: ime,"
                        + " fromIme: true requires a control with a leash but we have "
                        + ((control == null)
                            ? "control: null"
                            : "control: non-null and control.getLeash(): null"));
                ImeTracker.forLogging().onFailed(statsToken,
                        ImeTracker.PHASE_CLIENT_COLLECT_SOURCE_CONTROLS);
            }
        }
        return new Pair<>(typesReady, imeReady);
    }

    private @LayoutInsetsDuringAnimation int getLayoutInsetsDuringAnimationMode(
            @InsetsType int types, boolean fromPredictiveBack) {
        if (fromPredictiveBack) {
            // When insets are animated by predictive back, we want insets to be shown to prevent a
            // jump cut from shown to hidden at the start of the predictive back animation
            return LAYOUT_INSETS_DURING_ANIMATION_SHOWN;
        }
        // Generally, we want to layout the opposite of the current state. This is to make animation
        // callbacks easy to use: The can capture the layout values and then treat that as end-state
        // during the animation.
        //
        // However, if controlling multiple sources, we want to treat it as shown if any of the
        // types is currently hidden.
        return (mRequestedVisibleTypes & types) != types
                ? LAYOUT_INSETS_DURING_ANIMATION_SHOWN
                : LAYOUT_INSETS_DURING_ANIMATION_HIDDEN;
    }

    private void cancelExistingControllers(@InsetsType int types) {
        final int originalmTypesBeingCancelled = mTypesBeingCancelled;
        mTypesBeingCancelled |= types;
        try {
            for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
                InsetsAnimationControlRunner control = mRunningAnimations.get(i).runner;
                if ((control.getTypes() & types) != 0) {
                    cancelAnimation(control, true /* invokeCallback */);
                }
            }
            if ((types & ime()) != 0) {
                abortPendingImeControlRequest();
            }
        } finally {
            mTypesBeingCancelled = originalmTypesBeingCancelled;
        }
    }

    private void abortPendingImeControlRequest() {
        if (mPendingImeControlRequest != null) {
            mPendingImeControlRequest.listener.onCancelled(null);
            mPendingImeControlRequest = null;
            mHandler.removeCallbacks(mPendingControlTimeout);
            if (DEBUG) Log.d(TAG, "abortPendingImeControlRequest");
        }
    }

    @VisibleForTesting
    @Override
    public void notifyFinished(InsetsAnimationControlRunner runner, boolean shown) {
        setRequestedVisibleTypes(shown ? runner.getTypes() : 0, runner.getTypes());
        cancelAnimation(runner, false /* invokeCallback */);
        if (DEBUG) Log.d(TAG, "notifyFinished. shown: " + shown);
        if (runner.getAnimationType() == ANIMATION_TYPE_RESIZE) {
            // The resize animation doesn't show or hide the insets. We shouldn't change the
            // requested visibility.
            return;
        }
        final ImeTracker.Token statsToken = runner.getStatsToken();
        if (runner.getAnimationType() == ANIMATION_TYPE_USER) {
            ImeTracker.forLogging().onUserFinished(statsToken, shown);
        } else if (shown) {
            ImeTracker.forLogging().onProgress(statsToken,
                    ImeTracker.PHASE_CLIENT_ANIMATION_FINISHED_SHOW);
            ImeTracker.forLogging().onShown(statsToken);
        } else {
            ImeTracker.forLogging().onProgress(statsToken,
                    ImeTracker.PHASE_CLIENT_ANIMATION_FINISHED_HIDE);
            ImeTracker.forLogging().onHidden(statsToken);
        }
        reportRequestedVisibleTypes();
    }

    @Override
    public void applySurfaceParams(final SyncRtSurfaceTransactionApplier.SurfaceParams... params) {
        mHost.applySurfaceParams(params);
    }

    void notifyControlRevoked(InsetsSourceConsumer consumer) {
        final @InsetsType int type = consumer.getType();
        for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
            InsetsAnimationControlRunner control = mRunningAnimations.get(i).runner;
            control.notifyControlRevoked(type);
            if (control.getControllingTypes() == 0) {
                cancelAnimation(control, true /* invokeCallback */);
            }
        }
        if (type == ime()) {
            abortPendingImeControlRequest();
        }
        if (consumer.getType() != ime()) {
            // IME consumer should always be there since we need to communicate with
            // InputMethodManager no matter we have the control or not.
            mSourceConsumers.remove(consumer.getId());
        }
    }

    private void cancelAnimation(InsetsAnimationControlRunner control, boolean invokeCallback) {
        if (invokeCallback) {
            ImeTracker.forLogging().onCancelled(control.getStatsToken(),
                    ImeTracker.PHASE_CLIENT_ANIMATION_CANCEL);
            control.cancel();
        } else {
            // Succeeds if invokeCallback is false (i.e. when called from notifyFinished).
            ImeTracker.forLogging().onProgress(control.getStatsToken(),
                    ImeTracker.PHASE_CLIENT_ANIMATION_CANCEL);
        }
        if (DEBUG) {
            Log.d(TAG, TextUtils.formatSimple(
                    "cancelAnimation of types: %d, animType: %d, host: %s",
                    control.getTypes(), control.getAnimationType(), mHost.getRootViewTitle()));
        }
        @InsetsType int removedTypes = 0;
        for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
            RunningAnimation runningAnimation = mRunningAnimations.get(i);
            if (runningAnimation.runner == control) {
                mRunningAnimations.remove(i);
                removedTypes = control.getTypes();
                if (invokeCallback) {
                    dispatchAnimationEnd(runningAnimation.runner.getAnimation());
                }
                break;
            }
        }
        if (mRunningAnimations.isEmpty()) {
            mHost.notifyAnimationRunningStateChanged(false);
        }
        onAnimationStateChanged(removedTypes, false /* running */);
    }

    void onAnimationStateChanged(@InsetsType int types, boolean running) {
        boolean insetsChanged = false;
        for (int i = mSourceConsumers.size() - 1; i >= 0; i--) {
            final InsetsSourceConsumer consumer = mSourceConsumers.valueAt(i);
            if ((consumer.getType() & types) != 0) {
                insetsChanged |= consumer.onAnimationStateChanged(running);
            }
        }
        if (insetsChanged) {
            notifyVisibilityChanged();
        }
    }

    private void applyLocalVisibilityOverride() {
        for (int i = mSourceConsumers.size() - 1; i >= 0; i--) {
            final InsetsSourceConsumer consumer = mSourceConsumers.valueAt(i);
            consumer.applyLocalVisibilityOverride();
        }
    }

    @VisibleForTesting
    public @NonNull InsetsSourceConsumer getSourceConsumer(int id, int type) {
        InsetsSourceConsumer consumer = mSourceConsumers.get(id);
        if (consumer != null) {
            return consumer;
        }
        if (type == ime() && mImeSourceConsumer != null) {
            // WindowInsets.Type.ime() should be only provided by one source.
            mSourceConsumers.remove(mImeSourceConsumer.getId());
            consumer = mImeSourceConsumer;
            consumer.setId(id);
        } else {
            consumer = mConsumerCreator.apply(this, id, type);
        }
        mSourceConsumers.put(id, consumer);
        return consumer;
    }

    @VisibleForTesting
    public @NonNull InsetsSourceConsumer getImeSourceConsumer() {
        return mImeSourceConsumer;
    }

    void notifyVisibilityChanged() {
        mHost.notifyInsetsChanged();
    }

    /**
     * @see ViewRootImpl#updateCompatSysUiVisibility(int, int, int)
     */
    public void updateCompatSysUiVisibility() {
        if (mCompatSysUiVisibilityStaled) {
            mCompatSysUiVisibilityStaled = false;
            mHost.updateCompatSysUiVisibility(
                    // Treat non-existing types as controllable types for compatibility.
                    mVisibleTypes, mRequestedVisibleTypes, mControllableTypes | ~mExistingTypes);
        }
    }

    /**
     * Called when current window gains focus.
     */
    public void onWindowFocusGained(boolean hasViewFocused) {
        mImeSourceConsumer.onWindowFocusGained(hasViewFocused);
    }

    /**
     * Called when current window loses focus.
     */
    public void onWindowFocusLost() {
        mImeSourceConsumer.onWindowFocusLost();
    }

    @VisibleForTesting(visibility = PACKAGE)
    public @AnimationType int getAnimationType(@InsetsType int type) {
        for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
            InsetsAnimationControlRunner control = mRunningAnimations.get(i).runner;
            if (control.controlsType(type)) {
                return mRunningAnimations.get(i).type;
            }
        }
        return ANIMATION_TYPE_NONE;
    }

    @VisibleForTesting(visibility = PACKAGE)
    public void setRequestedVisibleTypes(@InsetsType int visibleTypes, @InsetsType int mask) {
        final @InsetsType int requestedVisibleTypes =
                (mRequestedVisibleTypes & ~mask) | (visibleTypes & mask);
        if (mRequestedVisibleTypes != requestedVisibleTypes) {
            mRequestedVisibleTypes = requestedVisibleTypes;
        }
    }

    /**
     * Called when finishing setting requested visible types or finishing setting controls.
     */
    private void reportRequestedVisibleTypes() {
        if (mReportedRequestedVisibleTypes != mRequestedVisibleTypes) {
            final @InsetsType int diff = mRequestedVisibleTypes ^ mReportedRequestedVisibleTypes;
            if (WindowInsets.Type.hasCompatSystemBars(diff)) {
                mCompatSysUiVisibilityStaled = true;
            }
            mReportedRequestedVisibleTypes = mRequestedVisibleTypes;
            mHost.updateRequestedVisibleTypes(mReportedRequestedVisibleTypes);
        }
        updateCompatSysUiVisibility();
    }

    @VisibleForTesting
    public void applyAnimation(@InsetsType final int types, boolean show, boolean fromIme,
            @Nullable ImeTracker.Token statsToken) {
        // TODO(b/166736352): We should only skip the animation of specific types, not all types.
        boolean skipAnim = false;
        if ((types & ime()) != 0) {
            final InsetsSourceControl imeControl = mImeSourceConsumer.getControl();
            // Skip showing animation once that made by system for some reason.
            // (e.g. starting window with IME snapshot)
            if (imeControl != null) {
                skipAnim = imeControl.getAndClearSkipAnimationOnce() && show
                        && mImeSourceConsumer.hasViewFocusWhenWindowFocusGain();
            }
        }
        applyAnimation(types, show, fromIme, skipAnim, statsToken);
    }

    @VisibleForTesting
    public void applyAnimation(@InsetsType final int types, boolean show, boolean fromIme,
            boolean skipAnim, @Nullable ImeTracker.Token statsToken) {
        if (types == 0) {
            // nothing to animate.
            if (DEBUG) Log.d(TAG, "applyAnimation, nothing to animate");
            Trace.asyncTraceEnd(TRACE_TAG_VIEW, "IC.showRequestFromApi", 0);
            if (!fromIme) {
                Trace.asyncTraceEnd(TRACE_TAG_VIEW, "IC.showRequestFromApiToImeReady", 0);
            }
            return;
        }

        boolean hasAnimationCallbacks = mHost.hasAnimationCallbacks();
        final InternalAnimationControlListener listener = new InternalAnimationControlListener(
                show, hasAnimationCallbacks, types, mHost.getSystemBarsBehavior(),
                skipAnim || mAnimationsDisabled, mHost.dipToPx(FLOATING_IME_BOTTOM_INSET_DP),
                mLoggingListener, mJankContext);

        // We are about to playing the default animation (show/hide). Passing a null frame indicates
        // the controlled types should be animated regardless of the frame.
        controlAnimationUnchecked(
                types, null /* cancellationSignal */, listener, null /* frame */, fromIme,
                listener.getDurationMs(), listener.getInsetsInterpolator(),
                show ? ANIMATION_TYPE_SHOW : ANIMATION_TYPE_HIDE,
                show ? LAYOUT_INSETS_DURING_ANIMATION_SHOWN : LAYOUT_INSETS_DURING_ANIMATION_HIDDEN,
                !hasAnimationCallbacks /* useInsetsAnimationThread */, statsToken);
    }

    /**
     * Cancel on-going animation to show/hide {@link InsetsType}.
     */
    @VisibleForTesting
    public void cancelExistingAnimations() {
        cancelExistingControllers(all());
    }

    void dump(String prefix, PrintWriter pw) {
        final String innerPrefix = prefix + "    ";
        pw.println(prefix + "InsetsController:");
        mState.dump(innerPrefix, pw);
        pw.println(innerPrefix + "mIsPredictiveBackImeHideAnimInProgress="
                + mIsPredictiveBackImeHideAnimInProgress);
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        mState.dumpDebug(proto, STATE);
        for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
            InsetsAnimationControlRunner runner = mRunningAnimations.get(i).runner;
            runner.dumpDebug(proto, CONTROL);
        }
        proto.end(token);
    }

    @VisibleForTesting
    @Override
    public <T extends InsetsAnimationControlRunner & InternalInsetsAnimationController>
    void startAnimation(T runner, WindowInsetsAnimationControlListener listener, int types,
            WindowInsetsAnimation animation, Bounds bounds) {
        mHost.dispatchWindowInsetsAnimationPrepare(animation);
        mHost.addOnPreDrawRunnable(() -> {
            if (runner.isCancelled()) {
                if (WARN) Log.w(TAG, "startAnimation canceled before preDraw");
                return;
            }
            Trace.asyncTraceBegin(TRACE_TAG_VIEW,
                    "InsetsAnimation: " + WindowInsets.Type.toString(types), types);
            for (int i = mRunningAnimations.size() - 1; i >= 0; i--) {
                RunningAnimation runningAnimation = mRunningAnimations.get(i);
                if (runningAnimation.runner == runner) {
                    runningAnimation.startDispatched = true;
                }
            }
            Trace.asyncTraceEnd(TRACE_TAG_VIEW, "IC.pendingAnim", 0);
            mHost.dispatchWindowInsetsAnimationStart(animation, bounds);
            mStartingAnimation = true;
            if (runner.getAnimationType() == ANIMATION_TYPE_USER) {
                ImeTracker.forLogging().onDispatched(runner.getStatsToken());
            }
            runner.setReadyDispatched(true);
            listener.onReady(runner, types);
            mStartingAnimation = false;
        });
    }

    @VisibleForTesting
    public void dispatchAnimationEnd(WindowInsetsAnimation animation) {
        Trace.asyncTraceEnd(TRACE_TAG_VIEW,
                "InsetsAnimation: " + WindowInsets.Type.toString(animation.getTypeMask()),
                animation.getTypeMask());
        mHost.dispatchWindowInsetsAnimationEnd(animation);
    }

    @VisibleForTesting
    @Override
    public void scheduleApplyChangeInsets(InsetsAnimationControlRunner runner) {
        if (mStartingAnimation || runner.getAnimationType() == ANIMATION_TYPE_USER) {
            mAnimCallback.run();
            mAnimCallbackScheduled = false;
            return;
        }
        if (!mAnimCallbackScheduled) {
            mHost.postInsetsAnimationCallback(mAnimCallback);
            mAnimCallbackScheduled = true;
        }
    }

    @Override
    public void setSystemBarsAppearance(@Appearance int appearance, @Appearance int mask) {
        mAppearanceControlled |= mask;
        mHost.setSystemBarsAppearance(appearance, mask);
    }

    @Override
    public void setSystemBarsAppearanceFromResource(@Appearance int appearance,
            @Appearance int mask) {
        mAppearanceFromResource = (mAppearanceFromResource & ~mask) | (appearance & mask);

        // Don't change the flags which are already controlled by setSystemBarsAppearance.
        mHost.setSystemBarsAppearance(appearance, mask & ~mAppearanceControlled);
    }

    @Override
    public @Appearance int getSystemBarsAppearance() {
        // We only return the requested appearance, not the implied one.
        return (mHost.getSystemBarsAppearance() & mAppearanceControlled)
                | (mAppearanceFromResource & ~mAppearanceControlled);
    }

    public @Appearance int getAppearanceControlled() {
        return mAppearanceControlled;
    }

    @Override
    public void setImeCaptionBarInsetsHeight(int height) {
        if (!ENABLE_HIDE_IME_CAPTION_BAR) {
            return;
        }
        Rect newFrame = new Rect(mFrame.left, mFrame.bottom - height, mFrame.right, mFrame.bottom);
        InsetsSource source = mState.peekSource(ID_IME_CAPTION_BAR);
        if (mImeCaptionBarInsetsHeight != height
                || (source != null && !newFrame.equals(source.getFrame()))) {
            mImeCaptionBarInsetsHeight = height;
            if (mImeCaptionBarInsetsHeight != 0) {
                mState.getOrCreateSource(ID_IME_CAPTION_BAR, captionBar())
                        .setFrame(newFrame);
                getSourceConsumer(ID_IME_CAPTION_BAR, captionBar()).setControl(
                        new InsetsSourceControl(ID_IME_CAPTION_BAR, captionBar(),
                                null /* leash */, false /* initialVisible */,
                                new Point(), Insets.NONE),
                        new int[1], new int[1]);
            } else {
                mState.removeSource(ID_IME_CAPTION_BAR);
                InsetsSourceConsumer sourceConsumer = mSourceConsumers.get(ID_IME_CAPTION_BAR);
                if (sourceConsumer != null) {
                    sourceConsumer.setControl(null, new int[1], new int[1]);
                }
            }
            mHost.notifyInsetsChanged();
        }
    }

    @Override
    public void setSystemBarsBehavior(@Behavior int behavior) {
        mBehaviorControlled = true;
        mHost.setSystemBarsBehavior(behavior);
    }

    @Override
    public @Behavior int getSystemBarsBehavior() {
        if (!mBehaviorControlled) {
            // We only return the requested behavior, not the implied one.
            return BEHAVIOR_DEFAULT;
        }
        return mHost.getSystemBarsBehavior();
    }

    public boolean isBehaviorControlled() {
        return mBehaviorControlled;
    }

    @Override
    public void setAnimationsDisabled(boolean disable) {
        mAnimationsDisabled = disable;
    }

    private @InsetsType int calculateControllableTypes() {
        @InsetsType int result = 0;
        for (int i = mSourceConsumers.size() - 1; i >= 0; i--) {
            InsetsSourceConsumer consumer = mSourceConsumers.valueAt(i);
            InsetsSource source = mState.peekSource(consumer.getId());
            if (consumer.getControl() != null && source != null) {
                result |= consumer.getType();
            }
        }
        return result & ~mState.calculateUncontrollableInsetsFromFrame(mFrame);
    }

    /**
     * @return The types that are now animating due to a listener invoking control/show/hide
     */
    private @InsetsType int invokeControllableInsetsChangedListeners() {
        mHandler.removeCallbacks(mInvokeControllableInsetsChangedListeners);
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

    @Override
    public void releaseSurfaceControlFromRt(SurfaceControl sc) {
        mHost.releaseSurfaceControlFromRt(sc);
    }

    @Override
    public void reportPerceptible(@InsetsType int types, boolean perceptible) {
        final int size = mSourceConsumers.size();
        for (int i = 0; i < size; i++) {
            final InsetsSourceConsumer consumer = mSourceConsumers.valueAt(i);
            if ((consumer.getType() & types) != 0) {
                consumer.onPerceptible(perceptible);
            }
        }
    }

    @VisibleForTesting(visibility = PACKAGE)
    public Host getHost() {
        return mHost;
    }
}
