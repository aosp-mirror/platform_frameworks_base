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

package com.android.wm.shell.legacysplitscreen;

import static android.content.res.Configuration.SCREEN_HEIGHT_DP_UNDEFINED;
import static android.content.res.Configuration.SCREEN_WIDTH_DP_UNDEFINED;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.util.Slog;
import android.view.Choreographer;
import android.view.SurfaceControl;
import android.window.TaskOrganizer;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TransactionPool;

class DividerImeController implements DisplayImeController.ImePositionProcessor {
    private static final String TAG = "DividerImeController";
    private static final boolean DEBUG = LegacySplitScreenController.DEBUG;

    private static final float ADJUSTED_NONFOCUS_DIM = 0.3f;

    private final LegacySplitScreenTaskListener mSplits;
    private final TransactionPool mTransactionPool;
    private final ShellExecutor mMainExecutor;
    private final TaskOrganizer mTaskOrganizer;

    /**
     * These are the y positions of the top of the IME surface when it is hidden and when it is
     * shown respectively. These are NOT necessarily the top of the visible IME itself.
     */
    private int mHiddenTop = 0;
    private int mShownTop = 0;

    // The following are target states (what we are curretly animating towards).
    /**
     * {@code true} if, at the end of the animation, the split task positions should be
     * adjusted by height of the IME. This happens when the secondary split is the IME target.
     */
    private boolean mTargetAdjusted = false;
    /**
     * {@code true} if, at the end of the animation, the IME should be shown/visible
     * regardless of what has focus.
     */
    private boolean mTargetShown = false;
    private float mTargetPrimaryDim = 0.f;
    private float mTargetSecondaryDim = 0.f;

    // The following are the current (most recent) states set during animation
    /** {@code true} if the secondary split has IME focus. */
    private boolean mSecondaryHasFocus = false;
    /** The dimming currently applied to the primary/secondary splits. */
    private float mLastPrimaryDim = 0.f;
    private float mLastSecondaryDim = 0.f;
    /** The most recent y position of the top of the IME surface */
    private int mLastAdjustTop = -1;

    // The following are states reached last time an animation fully completed.
    /** {@code true} if the IME was shown/visible by the last-completed animation. */
    private boolean mImeWasShown = false;
    /** {@code true} if the split positions were adjusted by the last-completed animation. */
    private boolean mAdjusted = false;

    /**
     * When some aspect of split-screen needs to animate independent from the IME,
     * this will be non-null and control split animation.
     */
    @Nullable
    private ValueAnimator mAnimation = null;

    private boolean mPaused = true;
    private boolean mPausedTargetAdjusted = false;

    DividerImeController(LegacySplitScreenTaskListener splits, TransactionPool pool,
            ShellExecutor mainExecutor, TaskOrganizer taskOrganizer) {
        mSplits = splits;
        mTransactionPool = pool;
        mMainExecutor = mainExecutor;
        mTaskOrganizer = taskOrganizer;
    }

    private DividerView getView() {
        return mSplits.mSplitScreenController.getDividerView();
    }

    private LegacySplitDisplayLayout getLayout() {
        return mSplits.mSplitScreenController.getSplitLayout();
    }

    private boolean isDividerHidden() {
        final DividerView view = mSplits.mSplitScreenController.getDividerView();
        return view == null || view.isHidden();
    }

    private boolean getSecondaryHasFocus(int displayId) {
        WindowContainerToken imeSplit = mTaskOrganizer.getImeTarget(displayId);
        return imeSplit != null
                && (imeSplit.asBinder() == mSplits.mSecondary.token.asBinder());
    }

    void reset() {
        mPaused = true;
        mPausedTargetAdjusted = false;
        mAnimation = null;
        mAdjusted = mTargetAdjusted = false;
        mImeWasShown = mTargetShown = false;
        mTargetPrimaryDim = mTargetSecondaryDim = mLastPrimaryDim = mLastSecondaryDim = 0.f;
        mSecondaryHasFocus = false;
        mLastAdjustTop = -1;
    }

    private void updateDimTargets() {
        final boolean splitIsVisible = !getView().isHidden();
        mTargetPrimaryDim = (mSecondaryHasFocus && mTargetShown && splitIsVisible)
                ? ADJUSTED_NONFOCUS_DIM : 0.f;
        mTargetSecondaryDim = (!mSecondaryHasFocus && mTargetShown && splitIsVisible)
                ? ADJUSTED_NONFOCUS_DIM : 0.f;
    }


    @Override
    public void onImeControlTargetChanged(int displayId, boolean controlling) {
        // Restore the split layout when wm-shell is not controlling IME insets anymore.
        if (!controlling && mTargetShown) {
            mPaused = false;
            mTargetAdjusted = mTargetShown = false;
            mTargetPrimaryDim = mTargetSecondaryDim = 0.f;
            updateImeAdjustState(true /* force */);
            startAsyncAnimation();
        }
    }

    @Override
    @ImeAnimationFlags
    public int onImeStartPositioning(int displayId, int hiddenTop, int shownTop,
            boolean imeShouldShow, boolean imeIsFloating, SurfaceControl.Transaction t) {
        if (isDividerHidden()) {
            return 0;
        }
        mHiddenTop = hiddenTop;
        mShownTop = shownTop;
        mTargetShown = imeShouldShow;
        mSecondaryHasFocus = getSecondaryHasFocus(displayId);
        final boolean targetAdjusted = imeShouldShow && mSecondaryHasFocus
                && !imeIsFloating && !getLayout().mDisplayLayout.isLandscape()
                && !mSplits.mSplitScreenController.isMinimized();
        if (mLastAdjustTop < 0) {
            mLastAdjustTop = imeShouldShow ? hiddenTop : shownTop;
        } else if (mLastAdjustTop != (imeShouldShow ? mShownTop : mHiddenTop)) {
            if (mTargetAdjusted != targetAdjusted && targetAdjusted == mAdjusted) {
                // Check for an "interruption" of an existing animation. In this case, we
                // need to fake-flip the last-known state direction so that the animation
                // completes in the other direction.
                mAdjusted = mTargetAdjusted;
            } else if (targetAdjusted && mTargetAdjusted && mAdjusted) {
                // Already fully adjusted for IME, but IME height has changed; so, force-start
                // an async animation to the new IME height.
                mAdjusted = false;
            }
        }
        if (mPaused) {
            mPausedTargetAdjusted = targetAdjusted;
            if (DEBUG) Slog.d(TAG, " ime starting but paused " + dumpState());
            return (targetAdjusted || mAdjusted) ? IME_ANIMATION_NO_ALPHA : 0;
        }
        mTargetAdjusted = targetAdjusted;
        updateDimTargets();
        if (DEBUG) Slog.d(TAG, " ime starting.  " + dumpState());
        if (mAnimation != null || (mImeWasShown && imeShouldShow
                && mTargetAdjusted != mAdjusted)) {
            // We need to animate adjustment independently of the IME position, so
            // start our own animation to drive adjustment. This happens when a
            // different split's editor has gained focus while the IME is still visible.
            startAsyncAnimation();
        }
        updateImeAdjustState();

        return (mTargetAdjusted || mAdjusted) ? IME_ANIMATION_NO_ALPHA : 0;
    }

    private void updateImeAdjustState() {
        updateImeAdjustState(false /* force */);
    }

    private void updateImeAdjustState(boolean force) {
        if (mAdjusted != mTargetAdjusted || force) {
            // Reposition the server's secondary split position so that it evaluates
            // insets properly.
            WindowContainerTransaction wct = new WindowContainerTransaction();
            final LegacySplitDisplayLayout splitLayout = getLayout();
            if (mTargetAdjusted) {
                splitLayout.updateAdjustedBounds(mShownTop, mHiddenTop, mShownTop);
                wct.setBounds(mSplits.mSecondary.token, splitLayout.mAdjustedSecondary);
                // "Freeze" the configuration size so that the app doesn't get a config
                // or relaunch. This is required because normally nav-bar contributes
                // to configuration bounds (via nondecorframe).
                Rect adjustAppBounds = new Rect(mSplits.mSecondary.configuration
                        .windowConfiguration.getAppBounds());
                adjustAppBounds.offset(0, splitLayout.mAdjustedSecondary.top
                        - splitLayout.mSecondary.top);
                wct.setAppBounds(mSplits.mSecondary.token, adjustAppBounds);
                wct.setScreenSizeDp(mSplits.mSecondary.token,
                        mSplits.mSecondary.configuration.screenWidthDp,
                        mSplits.mSecondary.configuration.screenHeightDp);

                wct.setBounds(mSplits.mPrimary.token, splitLayout.mAdjustedPrimary);
                adjustAppBounds = new Rect(mSplits.mPrimary.configuration
                        .windowConfiguration.getAppBounds());
                adjustAppBounds.offset(0, splitLayout.mAdjustedPrimary.top
                        - splitLayout.mPrimary.top);
                wct.setAppBounds(mSplits.mPrimary.token, adjustAppBounds);
                wct.setScreenSizeDp(mSplits.mPrimary.token,
                        mSplits.mPrimary.configuration.screenWidthDp,
                        mSplits.mPrimary.configuration.screenHeightDp);
            } else {
                wct.setBounds(mSplits.mSecondary.token, splitLayout.mSecondary);
                wct.setAppBounds(mSplits.mSecondary.token, null);
                wct.setScreenSizeDp(mSplits.mSecondary.token,
                        SCREEN_WIDTH_DP_UNDEFINED, SCREEN_HEIGHT_DP_UNDEFINED);
                wct.setBounds(mSplits.mPrimary.token, splitLayout.mPrimary);
                wct.setAppBounds(mSplits.mPrimary.token, null);
                wct.setScreenSizeDp(mSplits.mPrimary.token,
                        SCREEN_WIDTH_DP_UNDEFINED, SCREEN_HEIGHT_DP_UNDEFINED);
            }

            if (!mSplits.mSplitScreenController.getWmProxy().queueSyncTransactionIfWaiting(wct)) {
                mTaskOrganizer.applyTransaction(wct);
            }
        }

        // Update all the adjusted-for-ime states
        if (!mPaused) {
            final DividerView view = getView();
            if (view != null) {
                view.setAdjustedForIme(mTargetShown, mTargetShown
                        ? DisplayImeController.ANIMATION_DURATION_SHOW_MS
                        : DisplayImeController.ANIMATION_DURATION_HIDE_MS);
            }
        }
        mSplits.mSplitScreenController.setAdjustedForIme(mTargetShown && !mPaused);
    }

    @Override
    public void onImePositionChanged(int displayId, int imeTop,
            SurfaceControl.Transaction t) {
        if (mAnimation != null || isDividerHidden() || mPaused) {
            // Not synchronized with IME anymore, so return.
            return;
        }
        final float fraction = ((float) imeTop - mHiddenTop) / (mShownTop - mHiddenTop);
        final float progress = mTargetShown ? fraction : 1.f - fraction;
        onProgress(progress, t);
    }

    @Override
    public void onImeEndPositioning(int displayId, boolean cancelled,
            SurfaceControl.Transaction t) {
        if (mAnimation != null || isDividerHidden() || mPaused) {
            // Not synchronized with IME anymore, so return.
            return;
        }
        onEnd(cancelled, t);
    }

    private void onProgress(float progress, SurfaceControl.Transaction t) {
        final DividerView view = getView();
        if (mTargetAdjusted != mAdjusted && !mPaused) {
            final LegacySplitDisplayLayout splitLayout = getLayout();
            final float fraction = mTargetAdjusted ? progress : 1.f - progress;
            mLastAdjustTop = (int) (fraction * mShownTop + (1.f - fraction) * mHiddenTop);
            splitLayout.updateAdjustedBounds(mLastAdjustTop, mHiddenTop, mShownTop);
            view.resizeSplitSurfaces(t, splitLayout.mAdjustedPrimary,
                    splitLayout.mAdjustedSecondary);
        }
        final float invProg = 1.f - progress;
        view.setResizeDimLayer(t, true /* primary */,
                mLastPrimaryDim * invProg + progress * mTargetPrimaryDim);
        view.setResizeDimLayer(t, false /* primary */,
                mLastSecondaryDim * invProg + progress * mTargetSecondaryDim);
    }

    void setDimsHidden(SurfaceControl.Transaction t, boolean hidden) {
        final DividerView view = getView();
        if (hidden) {
            view.setResizeDimLayer(t, true /* primary */, 0.f /* alpha */);
            view.setResizeDimLayer(t, false /* primary */, 0.f /* alpha */);
        } else {
            updateDimTargets();
            view.setResizeDimLayer(t, true /* primary */, mTargetPrimaryDim);
            view.setResizeDimLayer(t, false /* primary */, mTargetSecondaryDim);
        }
    }

    private void onEnd(boolean cancelled, SurfaceControl.Transaction t) {
        if (!cancelled) {
            onProgress(1.f, t);
            mAdjusted = mTargetAdjusted;
            mImeWasShown = mTargetShown;
            mLastAdjustTop = mAdjusted ? mShownTop : mHiddenTop;
            mLastPrimaryDim = mTargetPrimaryDim;
            mLastSecondaryDim = mTargetSecondaryDim;
        }
    }

    private void startAsyncAnimation() {
        if (mAnimation != null) {
            mAnimation.cancel();
        }
        mAnimation = ValueAnimator.ofFloat(0.f, 1.f);
        mAnimation.setDuration(DisplayImeController.ANIMATION_DURATION_SHOW_MS);
        if (mTargetAdjusted != mAdjusted) {
            final float fraction =
                    ((float) mLastAdjustTop - mHiddenTop) / (mShownTop - mHiddenTop);
            final float progress = mTargetAdjusted ? fraction : 1.f - fraction;
            mAnimation.setCurrentFraction(progress);
        }

        mAnimation.addUpdateListener(animation -> {
            SurfaceControl.Transaction t = mTransactionPool.acquire();
            float value = (float) animation.getAnimatedValue();
            onProgress(value, t);
            t.setFrameTimelineVsync(Choreographer.getSfInstance().getVsyncId());
            t.apply();
            mTransactionPool.release(t);
        });
        mAnimation.setInterpolator(DisplayImeController.INTERPOLATOR);
        mAnimation.addListener(new AnimatorListenerAdapter() {
            private boolean mCancel = false;

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancel = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                SurfaceControl.Transaction t = mTransactionPool.acquire();
                onEnd(mCancel, t);
                t.apply();
                mTransactionPool.release(t);
                mAnimation = null;
            }
        });
        mAnimation.start();
    }

    private String dumpState() {
        return "top:" + mHiddenTop + "->" + mShownTop
                + " adj:" + mAdjusted + "->" + mTargetAdjusted + "(" + mLastAdjustTop + ")"
                + " shw:" + mImeWasShown + "->" + mTargetShown
                + " dims:" + mLastPrimaryDim + "," + mLastSecondaryDim
                + "->" + mTargetPrimaryDim + "," + mTargetSecondaryDim
                + " shf:" + mSecondaryHasFocus + " desync:" + (mAnimation != null)
                + " paus:" + mPaused + "[" + mPausedTargetAdjusted + "]";
    }

    /** Completely aborts/resets adjustment state */
    public void pause(int displayId) {
        if (DEBUG) Slog.d(TAG, "ime pause posting " + dumpState());
        mMainExecutor.execute(() -> {
            if (DEBUG) Slog.d(TAG, "ime pause run posted " + dumpState());
            if (mPaused) {
                return;
            }
            mPaused = true;
            mPausedTargetAdjusted = mTargetAdjusted;
            mTargetAdjusted = false;
            mTargetPrimaryDim = mTargetSecondaryDim = 0.f;
            updateImeAdjustState();
            startAsyncAnimation();
            if (mAnimation != null) {
                mAnimation.end();
            }
        });
    }

    public void resume(int displayId) {
        if (DEBUG) Slog.d(TAG, "ime resume posting " + dumpState());
        mMainExecutor.execute(() -> {
            if (DEBUG) Slog.d(TAG, "ime resume run posted " + dumpState());
            if (!mPaused) {
                return;
            }
            mPaused = false;
            mTargetAdjusted = mPausedTargetAdjusted;
            updateDimTargets();
            final DividerView view = getView();
            if ((mTargetAdjusted != mAdjusted) && !mSplits.mSplitScreenController.isMinimized()
                    && view != null) {
                // End unminimize animations since they conflict with adjustment animations.
                view.finishAnimations();
            }
            updateImeAdjustState();
            startAsyncAnimation();
        });
    }
}
