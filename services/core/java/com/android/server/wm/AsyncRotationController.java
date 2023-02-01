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

package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;

import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_TOKEN_TRANSFORM;

import android.annotation.IntDef;
import android.os.HandlerExecutor;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;

import com.android.internal.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.function.Consumer;

/**
 * Controller to handle the appearance of non-activity windows which can update asynchronously when
 * the display rotation is changing. This is an optimization to reduce the latency to start screen
 * rotation or app transition animation.
 * <pre>The appearance:
 * - Open app with rotation change: the target windows are faded out with open transition, and then
 *   faded in after the transition when the windows are drawn with new rotation.
 * - Normal rotation: the target windows are hidden by a parent leash with zero alpha after the
 *   screenshot layer is shown, and will be faded in when they are drawn with new rotation.
 * - Seamless rotation: Only shell transition uses this controller in this case. The target windows
 *   will be requested to use sync transaction individually. Their window token will rotate to old
 *   rotation. After the start transaction of transition is applied and the window is drawn in new
 *   rotation, the old rotation transformation will be removed with applying the sync transaction.
 * </pre>
 * For the windows which are forced to be seamless (e.g. screen decor overlay), the case is the
 * same as above mentioned seamless rotation (only shell). Just the appearance may be mixed, e.g.
 * 2 windows FADE and 2 windows SEAMLESS in normal rotation or app transition. And 4 (all) windows
 * SEAMLESS in seamless rotation.
 */
class AsyncRotationController extends FadeAnimationController implements Consumer<WindowState> {
    private static final String TAG = "AsyncRotation";
    private static final boolean DEBUG = false;

    private final WindowManagerService mService;
    /** The map of async windows to the operations of rotation appearance. */
    private final ArrayMap<WindowToken, Operation> mTargetWindowTokens = new ArrayMap<>();
    /** If non-null, it usually indicates that there will be a screen rotation animation. */
    private Runnable mTimeoutRunnable;
    /** Non-null to indicate that the navigation bar is always handled by legacy seamless. */
    private WindowToken mNavBarToken;

    /** A runnable which gets called when the {@link #completeAll()} is called. */
    private Runnable mOnShowRunnable;

    /** Whether to use constant zero alpha animation. */
    private boolean mHideImmediately;

    /** The case of legacy transition. */
    private static final int OP_LEGACY = 0;
    /** It is usually OPEN/CLOSE/TO_FRONT/TO_BACK. */
    private static final int OP_APP_SWITCH = 1;
    /** The normal display change transition which should have a screen rotation animation. */
    private static final int OP_CHANGE = 2;
    /** The app requests seamless and the display supports. But the decision is still in shell. */
    private static final int OP_CHANGE_MAY_SEAMLESS = 3;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = { OP_LEGACY, OP_APP_SWITCH, OP_CHANGE, OP_CHANGE_MAY_SEAMLESS })
    @interface TransitionOp {}

    /** Non-zero if this controller is triggered by shell transition. */
    private final @TransitionOp int mTransitionOp;

    /** Whether the start transaction of the transition is committed (by shell). */
    private boolean mIsStartTransactionCommitted;

    /** Whether the target windows have been requested to sync their draw transactions. */
    private boolean mIsSyncDrawRequested;

    private SeamlessRotator mRotator;

    private final int mOriginalRotation;
    private final boolean mHasScreenRotationAnimation;

    AsyncRotationController(DisplayContent displayContent) {
        super(displayContent);
        mService = displayContent.mWmService;
        mOriginalRotation = displayContent.getWindowConfiguration().getRotation();
        final int transitionType =
                displayContent.mTransitionController.getCollectingTransitionType();
        if (transitionType == WindowManager.TRANSIT_CHANGE) {
            final DisplayRotation dr = displayContent.getDisplayRotation();
            final WindowState w = displayContent.getDisplayPolicy().getTopFullscreenOpaqueWindow();
            // A rough condition to check whether it may be seamless style. Though the final
            // decision in shell may be different, it is fine because the jump cut can be covered
            // by a screenshot if shell falls back to use normal rotation animation.
            if (w != null && w.mAttrs.rotationAnimation == ROTATION_ANIMATION_SEAMLESS
                    && w.getTask() != null
                    && dr.canRotateSeamlessly(mOriginalRotation, dr.getRotation())) {
                mTransitionOp = OP_CHANGE_MAY_SEAMLESS;
            } else {
                mTransitionOp = OP_CHANGE;
            }
        } else if (displayContent.mTransitionController.isShellTransitionsEnabled()) {
            mTransitionOp = OP_APP_SWITCH;
        } else {
            mTransitionOp = OP_LEGACY;
        }

        // Although OP_CHANGE_MAY_SEAMLESS may still play screen rotation animation because shell
        // decides not to perform seamless rotation, it only affects whether to use fade animation
        // when the windows are drawn. If the windows are not too slow (after rotation animation is
        // done) to be drawn, the visual result can still look smooth.
        mHasScreenRotationAnimation =
                displayContent.getRotationAnimation() != null || mTransitionOp == OP_CHANGE;
        if (mHasScreenRotationAnimation) {
            // Hide the windows immediately because screen should have been covered by screenshot.
            mHideImmediately = true;
        }

        // Collect the windows which can rotate asynchronously without blocking the display.
        displayContent.forAllWindows(this, true /* traverseTopToBottom */);

        // Legacy animation doesn't need to wait for the start transaction.
        if (mTransitionOp == OP_LEGACY) {
            mIsStartTransactionCommitted = true;
        } else if (displayContent.mTransitionController.isCollecting(displayContent)) {
            keepAppearanceInPreviousRotation();
        }
    }

    /** Assigns the operation for the window tokens which can update rotation asynchronously. */
    @Override
    public void accept(WindowState w) {
        if (!w.mHasSurface || !canBeAsync(w.mToken)) {
            return;
        }
        if (mTransitionOp == OP_LEGACY && w.mForceSeamlesslyRotate) {
            // Legacy transition already handles seamlessly windows.
            return;
        }
        if (w.mAttrs.type == TYPE_NAVIGATION_BAR) {
            int action = Operation.ACTION_FADE;
            final boolean navigationBarCanMove =
                    mDisplayContent.getDisplayPolicy().navigationBarCanMove();
            if (mTransitionOp == OP_LEGACY) {
                mNavBarToken = w.mToken;
                // Do not animate movable navigation bar (e.g. 3-buttons mode).
                if (navigationBarCanMove) return;
                // Or when the navigation bar is currently controlled by recents animation.
                final RecentsAnimationController recents = mService.getRecentsAnimationController();
                if (recents != null && recents.isNavigationBarAttachedToApp()) {
                    return;
                }
            } else if (navigationBarCanMove || mTransitionOp == OP_CHANGE_MAY_SEAMLESS) {
                action = Operation.ACTION_SEAMLESS;
            }
            mTargetWindowTokens.put(w.mToken, new Operation(action));
            return;
        }

        final int action = mTransitionOp == OP_CHANGE_MAY_SEAMLESS || w.mForceSeamlesslyRotate
                ? Operation.ACTION_SEAMLESS : Operation.ACTION_FADE;
        mTargetWindowTokens.put(w.mToken, new Operation(action));
    }

    /** Returns {@code true} if the window token can update rotation independently. */
    static boolean canBeAsync(WindowToken token) {
        final int type = token.windowType;
        return type > WindowManager.LayoutParams.LAST_APPLICATION_WINDOW
                && type != WindowManager.LayoutParams.TYPE_INPUT_METHOD
                && type != WindowManager.LayoutParams.TYPE_WALLPAPER
                && type != WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE;
    }

    /**
     * Enables {@link #handleFinishDrawing(WindowState, SurfaceControl.Transaction)} to capture the
     * draw transactions of the target windows if needed.
     */
    void keepAppearanceInPreviousRotation() {
        if (mIsSyncDrawRequested) return;
        // The transition sync group may be finished earlier because it doesn't wait for these
        // target windows. But the windows still need to use sync transaction to keep the appearance
        // in previous rotation, so request a no-op sync to keep the state.
        for (int i = mTargetWindowTokens.size() - 1; i >= 0; i--) {
            if (TransitionController.SYNC_METHOD != BLASTSyncEngine.METHOD_BLAST
                    && mTargetWindowTokens.valueAt(i).mAction != Operation.ACTION_SEAMLESS) {
                // Expect a screenshot layer will cover the non seamless windows.
                continue;
            }
            final WindowToken token = mTargetWindowTokens.keyAt(i);
            for (int j = token.getChildCount() - 1; j >= 0; j--) {
                // TODO(b/234585256): The consumer should be handleFinishDrawing().
                token.getChildAt(j).applyWithNextDraw(t -> {});
                if (DEBUG) Slog.d(TAG, "Sync draw for " + token.getChildAt(j));
            }
        }
        mIsSyncDrawRequested = true;
        if (DEBUG) Slog.d(TAG, "Requested to sync draw transaction");
    }

    /** Lets the window fit in new rotation naturally. */
    private void finishOp(WindowToken windowToken) {
        final Operation op = mTargetWindowTokens.remove(windowToken);
        if (op == null) return;
        if (op.mDrawTransaction != null) {
            // Unblock the window to show its latest content.
            mDisplayContent.getPendingTransaction().merge(op.mDrawTransaction);
            op.mDrawTransaction = null;
            if (DEBUG) Slog.d(TAG, "finishOp merge transaction " + windowToken.getTopChild());
        }
        if (op.mAction == Operation.ACTION_FADE) {
            if (DEBUG) Slog.d(TAG, "finishOp fade-in " + windowToken.getTopChild());
            // The previous animation leash will be dropped when preparing fade-in animation, so
            // simply apply new animation without restoring the transformation.
            fadeWindowToken(true /* show */, windowToken, ANIMATION_TYPE_TOKEN_TRANSFORM);
        } else if (op.mAction == Operation.ACTION_SEAMLESS && mRotator != null
                && op.mLeash != null && op.mLeash.isValid()) {
            if (DEBUG) Slog.d(TAG, "finishOp undo seamless " + windowToken.getTopChild());
            mRotator.setIdentityMatrix(mDisplayContent.getPendingTransaction(), op.mLeash);
        }
    }

    /**
     * Completes all operations such as applying fade-in animation on the previously hidden window
     * tokens. This is called if all windows are ready in new rotation or timed out.
     */
    void completeAll() {
        for (int i = mTargetWindowTokens.size() - 1; i >= 0; i--) {
            finishOp(mTargetWindowTokens.keyAt(i));
        }
        mTargetWindowTokens.clear();
        if (mTimeoutRunnable != null) {
            mService.mH.removeCallbacks(mTimeoutRunnable);
        }
        if (mOnShowRunnable != null) {
            mOnShowRunnable.run();
            mOnShowRunnable = null;
        }
    }

    /**
     * Notifies that the window is ready in new rotation. Returns {@code true} if all target
     * windows have completed their rotation operations.
     */
    boolean completeRotation(WindowToken token) {
        if (!mIsStartTransactionCommitted) {
            final Operation op = mTargetWindowTokens.get(token);
            // The animation or draw transaction should only start after the start transaction is
            // applied by shell (e.g. show screenshot layer). Otherwise the window will be blinking
            // before the rotation animation starts. So store to a pending list and animate them
            // until the transaction is committed.
            if (op != null) {
                if (DEBUG) Slog.d(TAG, "Complete set pending " + token.getTopChild());
                op.mIsCompletionPending = true;
            }
            return false;
        }
        if (mTransitionOp == OP_APP_SWITCH && token.mTransitionController.inTransition()) {
            final Operation op = mTargetWindowTokens.get(token);
            if (op != null && op.mAction == Operation.ACTION_FADE) {
                // Defer showing to onTransitionFinished().
                if (DEBUG) Slog.d(TAG, "Defer completion " + token.getTopChild());
                return false;
            }
        }
        if (!isTargetToken(token)) return false;
        if (mHasScreenRotationAnimation || mTransitionOp != OP_LEGACY) {
            if (DEBUG) Slog.d(TAG, "Complete directly " + token.getTopChild());
            finishOp(token);
            if (mTargetWindowTokens.isEmpty()) {
                if (mTimeoutRunnable != null) mService.mH.removeCallbacks(mTimeoutRunnable);
                return true;
            }
        }
        // The case (legacy fixed rotation) will be handled by completeAll() when all seamless
        // windows are done.
        return false;
    }

    /**
     * Prepares the corresponding operations (e.g. hide animation) for the window tokens which may
     * be seamlessly rotated later.
     */
    void start() {
        for (int i = mTargetWindowTokens.size() - 1; i >= 0; i--) {
            final WindowToken windowToken = mTargetWindowTokens.keyAt(i);
            final Operation op = mTargetWindowTokens.valueAt(i);
            if (op.mAction == Operation.ACTION_FADE) {
                fadeWindowToken(false /* show */, windowToken, ANIMATION_TYPE_TOKEN_TRANSFORM);
                op.mLeash = windowToken.getAnimationLeash();
                if (DEBUG) Slog.d(TAG, "Start fade-out " + windowToken.getTopChild());
            } else if (op.mAction == Operation.ACTION_SEAMLESS) {
                op.mLeash = windowToken.mSurfaceControl;
                if (DEBUG) Slog.d(TAG, "Start seamless " + windowToken.getTopChild());
            }
        }
        if (mHasScreenRotationAnimation) {
            scheduleTimeout();
        }
    }

    private void scheduleTimeout() {
        if (mTimeoutRunnable == null) {
            mTimeoutRunnable = () -> {
                synchronized (mService.mGlobalLock) {
                    Slog.i(TAG, "Async rotation timeout: " + mTargetWindowTokens);
                    mIsStartTransactionCommitted = true;
                    mDisplayContent.finishAsyncRotationIfPossible();
                    mService.mWindowPlacerLocked.performSurfacePlacement();
                }
            };
        }
        mService.mH.postDelayed(mTimeoutRunnable,
                WindowManagerService.WINDOW_FREEZE_TIMEOUT_DURATION);
    }

    /** Hides the window immediately until it is drawn in new rotation. */
    void hideImmediately(WindowToken windowToken) {
        final boolean original = mHideImmediately;
        mHideImmediately = true;
        final Operation op = new Operation(Operation.ACTION_FADE);
        mTargetWindowTokens.put(windowToken, op);
        fadeWindowToken(false /* show */, windowToken, ANIMATION_TYPE_TOKEN_TRANSFORM);
        op.mLeash = windowToken.getAnimationLeash();
        mHideImmediately = original;
        if (DEBUG) Slog.d(TAG, "hideImmediately " + windowToken.getTopChild());
    }

    /** Returns {@code true} if the window will rotate independently. */
    boolean isAsync(WindowState w) {
        return w.mToken == mNavBarToken
                || (w.mForceSeamlesslyRotate && mTransitionOp == OP_LEGACY)
                || isTargetToken(w.mToken);
    }

    /** Returns {@code true} if the controller will run fade animations on the window. */
    boolean isTargetToken(WindowToken token) {
        return mTargetWindowTokens.containsKey(token);
    }

    /**
     * Whether the insets animation leash should use previous position when running fade animation
     * or seamless transformation in a rotated display.
     */
    boolean shouldFreezeInsetsPosition(WindowState w) {
        if (TransitionController.SYNC_METHOD != BLASTSyncEngine.METHOD_BLAST) {
            // Expect a screenshot layer has covered the screen, so it is fine to let client side
            // insets animation runner update the position directly.
            return false;
        }
        return mTransitionOp != OP_LEGACY && !mIsStartTransactionCommitted
                && isTargetToken(w.mToken);
    }

    /**
     * Returns the transaction which will be applied after the window redraws in new rotation.
     * This is used to update the position of insets animation leash synchronously.
     */
    SurfaceControl.Transaction getDrawTransaction(WindowToken token) {
        if (mTransitionOp == OP_LEGACY) {
            // Legacy transition uses startSeamlessRotation and finishSeamlessRotation of
            // InsetsSourceProvider.
            return null;
        }
        final Operation op = mTargetWindowTokens.get(token);
        if (op != null) {
            if (op.mDrawTransaction == null) {
                op.mDrawTransaction = new SurfaceControl.Transaction();
            }
            return op.mDrawTransaction;
        }
        return null;
    }

    void setOnShowRunnable(Runnable onShowRunnable) {
        mOnShowRunnable = onShowRunnable;
    }

    /**
     * Puts initial operation of leash to the transaction which will be executed when the
     * transition starts. And associate transaction callback to consume pending animations.
     */
    void setupStartTransaction(SurfaceControl.Transaction t) {
        if (mIsStartTransactionCommitted) return;
        for (int i = mTargetWindowTokens.size() - 1; i >= 0; i--) {
            final Operation op = mTargetWindowTokens.valueAt(i);
            final SurfaceControl leash = op.mLeash;
            if (leash == null || !leash.isValid()) continue;
            if (mHasScreenRotationAnimation && op.mAction == Operation.ACTION_FADE) {
                // Hide the windows immediately because a screenshot layer should cover the screen.
                t.setAlpha(leash, 0f);
                if (DEBUG) {
                    Slog.d(TAG, "Setup alpha0 " + mTargetWindowTokens.keyAt(i).getTopChild());
                }
            } else {
                // Take OPEN/CLOSE transition type as the example, the non-activity windows need to
                // fade out in previous rotation while display has rotated to the new rotation, so
                // their leashes are transformed with the start transaction.
                if (mRotator == null) {
                    mRotator = new SeamlessRotator(mOriginalRotation,
                            mDisplayContent.getWindowConfiguration().getRotation(),
                            mDisplayContent.getDisplayInfo(),
                            false /* applyFixedTransformationHint */);
                }
                mRotator.applyTransform(t, leash);
                if (DEBUG) {
                    Slog.d(TAG, "Setup unrotate " + mTargetWindowTokens.keyAt(i).getTopChild());
                }
            }
        }

        // If there are windows have redrawn in new rotation but the start transaction has not
        // been applied yet, the fade-in animation will be deferred. So once the transaction is
        // committed, the fade-in animation can run with screen rotation animation.
        t.addTransactionCommittedListener(new HandlerExecutor(mService.mH), () -> {
            synchronized (mService.mGlobalLock) {
                if (DEBUG) Slog.d(TAG, "Start transaction is committed");
                mIsStartTransactionCommitted = true;
                for (int i = mTargetWindowTokens.size() - 1; i >= 0; i--) {
                    if (mTargetWindowTokens.valueAt(i).mIsCompletionPending) {
                        if (DEBUG) {
                            Slog.d(TAG, "Continue pending completion "
                                    + mTargetWindowTokens.keyAt(i).getTopChild());
                        }
                        mDisplayContent.finishAsyncRotation(mTargetWindowTokens.keyAt(i));
                    }
                }
            }
        });
    }

    /** Called when the transition by shell is done. */
    void onTransitionFinished() {
        if (mTransitionOp == OP_CHANGE) {
            // With screen rotation animation, the windows are always faded in when they are drawn.
            // Because if they are drawn fast enough, the fade animation should not be observable.
            return;
        }
        if (DEBUG) Slog.d(TAG, "onTransitionFinished " + mTargetWindowTokens);
        // For other transition types, the fade-in animation runs after the transition to make the
        // transition animation (e.g. launch activity) look cleaner.
        for (int i = mTargetWindowTokens.size() - 1; i >= 0; i--) {
            final WindowToken token = mTargetWindowTokens.keyAt(i);
            if (!token.isVisible()) {
                mDisplayContent.finishAsyncRotation(token);
                continue;
            }
            for (int j = token.getChildCount() - 1; j >= 0; j--) {
                // Only fade in the drawn windows. If the remaining windows are drawn later,
                // show(WindowToken) will be called to fade in them.
                if (token.getChildAt(j).isDrawFinishedLw()) {
                    mDisplayContent.finishAsyncRotation(token);
                    break;
                }
            }
        }
        if (!mTargetWindowTokens.isEmpty()) {
            scheduleTimeout();
        }
    }

    /**
     * Captures the post draw transaction if the window should keep its appearance in previous
     * rotation when running transition. Returns {@code true} if the draw transaction is handled
     * by this controller.
     */
    boolean handleFinishDrawing(WindowState w, SurfaceControl.Transaction postDrawTransaction) {
        if (mTransitionOp == OP_LEGACY || postDrawTransaction == null || !mIsSyncDrawRequested) {
            return false;
        }
        final Operation op = mTargetWindowTokens.get(w.mToken);
        if (op == null) return false;
        if (DEBUG) Slog.d(TAG, "handleFinishDrawing " + w);
        if (op.mDrawTransaction == null) {
            if (w.isClientLocal()) {
                // Use a new transaction to merge the draw transaction of local window because the
                // same instance will be cleared (Transaction#clear()) after reporting draw.
                op.mDrawTransaction = mService.mTransactionFactory.get();
                op.mDrawTransaction.merge(postDrawTransaction);
            } else {
                // The transaction read from parcel (the client is in a different process) is
                // already a copy, so just reference it directly.
                op.mDrawTransaction = postDrawTransaction;
            }
        } else {
            op.mDrawTransaction.merge(postDrawTransaction);
        }
        mDisplayContent.finishAsyncRotation(w.mToken);
        return true;
    }

    @Override
    public Animation getFadeInAnimation() {
        if (mHasScreenRotationAnimation) {
            // Use a shorter animation so it is easier to align with screen rotation animation.
            return AnimationUtils.loadAnimation(mContext, R.anim.screen_rotate_0_enter);
        }
        return super.getFadeInAnimation();
    }

    @Override
    public Animation getFadeOutAnimation() {
        if (mHideImmediately) {
            // For change transition, the hide transaction needs to be applied with sync transaction
            // (setupStartTransaction). So keep alpha 1 just to get the animation leash.
            final float alpha = mTransitionOp == OP_CHANGE ? 1 : 0;
            return new AlphaAnimation(alpha /* fromAlpha */, alpha /* toAlpha */);
        }
        return super.getFadeOutAnimation();
    }

    /** The operation to control the rotation appearance associated with window token. */
    private static class Operation {
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(value = { ACTION_SEAMLESS, ACTION_FADE })
        @interface Action {}

        static final int ACTION_SEAMLESS = 1;
        static final int ACTION_FADE = 2;
        final @Action int mAction;
        /** The leash of window token. It can be animation leash or the token itself. */
        SurfaceControl mLeash;
        /** Whether the window is drawn before the transition starts. */
        boolean mIsCompletionPending;

        /**
         * The sync transaction of the target window. It is used when the display has rotated but
         * the window needs to show in previous rotation. The transaction will be applied after the
         * the start transaction of transition, so there won't be a flickering such as the window
         * has redrawn during fading out.
         */
        SurfaceControl.Transaction mDrawTransaction;

        Operation(@Action int action) {
            mAction = action;
        }
    }
}
