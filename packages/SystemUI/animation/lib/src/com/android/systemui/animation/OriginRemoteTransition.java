/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.animation;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.SurfaceControl;
import android.window.IRemoteTransition;
import android.window.IRemoteTransitionFinishedCallback;
import android.window.TransitionInfo;
import android.window.TransitionInfo.Change;
import android.window.WindowAnimationState;

import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.wm.shell.shared.TransitionUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * An implementation of {@link IRemoteTransition} that accepts a {@link UIComponent} as the origin
 * and automatically attaches it to the transition leash before the transition starts.
 *
 * @hide
 */
public class OriginRemoteTransition extends IRemoteTransition.Stub {
    private static final String TAG = "OriginRemoteTransition";

    private final Context mContext;
    private final boolean mIsEntry;
    private final UIComponent mOrigin;
    private final TransitionPlayer mPlayer;
    private final long mDuration;
    private final Handler mHandler;

    @Nullable private SurfaceControl.Transaction mStartTransaction;
    @Nullable private IRemoteTransitionFinishedCallback mFinishCallback;
    @Nullable private UIComponent.Transaction mOriginTransaction;
    @Nullable private ValueAnimator mAnimator;
    @Nullable private SurfaceControl mOriginLeash;
    private boolean mCancelled;

    OriginRemoteTransition(
            Context context,
            boolean isEntry,
            UIComponent origin,
            TransitionPlayer player,
            long duration,
            Handler handler) {
        mContext = context;
        mIsEntry = isEntry;
        mOrigin = origin;
        mPlayer = player;
        mDuration = duration;
        mHandler = handler;
    }

    @Override
    public void startAnimation(
            IBinder token,
            TransitionInfo info,
            SurfaceControl.Transaction t,
            IRemoteTransitionFinishedCallback finishCallback) {
        logD("startAnimation - " + info);
        mHandler.post(
                () -> {
                    mStartTransaction = t;
                    mFinishCallback = finishCallback;
                    startAnimationInternal(info);
                });
    }

    @Override
    public void mergeAnimation(
            IBinder transition,
            TransitionInfo info,
            SurfaceControl.Transaction t,
            IBinder mergeTarget,
            IRemoteTransitionFinishedCallback finishCallback) {
        logD("mergeAnimation - " + info);
        mHandler.post(this::cancel);
    }

    @Override
    public void takeOverAnimation(
            IBinder transition,
            TransitionInfo info,
            SurfaceControl.Transaction t,
            IRemoteTransitionFinishedCallback finishCallback,
            WindowAnimationState[] states) {
        logD("takeOverAnimation - " + info);
    }

    @Override
    public void onTransitionConsumed(IBinder transition, boolean aborted) {
        logD("onTransitionConsumed - aborted: " + aborted);
        mHandler.post(this::cancel);
    }

    private void startAnimationInternal(TransitionInfo info) {
        if (!prepareUIs(info)) {
            logE("Unable to prepare UI!");
            finishAnimation(/* finished= */ false);
            return;
        }
        // Notify player that we are starting.
        mPlayer.onStart(info, mStartTransaction, mOrigin, mOriginTransaction);

        // Start the animator.
        mAnimator = ValueAnimator.ofFloat(0.0f, 1.0f);
        mAnimator.setDuration(mDuration);
        mAnimator.addListener(
                new AnimatorListener() {
                    @Override
                    public void onAnimationStart(Animator a) {}

                    @Override
                    public void onAnimationEnd(Animator a) {
                        finishAnimation(/* finished= */ !mCancelled);
                    }

                    @Override
                    public void onAnimationCancel(Animator a) {
                        mCancelled = true;
                    }

                    @Override
                    public void onAnimationRepeat(Animator a) {}
                });
        mAnimator.addUpdateListener(
                a -> {
                    mPlayer.onProgress((float) a.getAnimatedValue());
                });
        mAnimator.start();
    }

    private boolean prepareUIs(TransitionInfo info) {
        if (info.getRootCount() == 0) {
            logE("prepareUIs: no root leash!");
            return false;
        }
        if (info.getRootCount() > 1) {
            logE("prepareUIs: multi-display transition is not supported yet!");
            return false;
        }
        if (info.getChanges().isEmpty()) {
            logE("prepareUIs: no changes!");
            return false;
        }

        SurfaceControl rootLeash = info.getRoot(0).getLeash();
        int displayId = info.getChanges().get(0).getEndDisplayId();
        Rect displayBounds = getDisplayBounds(displayId);
        float windowRadius = ScreenDecorationsUtils.getWindowCornerRadius(mContext);
        logD("prepareUIs: windowRadius=" + windowRadius + ", displayBounds=" + displayBounds);

        // Create the origin leash and add to the transition root leash.
        mOriginLeash =
                new SurfaceControl.Builder().setName("OriginTransition-origin-leash").build();
        mStartTransaction
                .reparent(mOriginLeash, rootLeash)
                .show(mOriginLeash)
                .setCornerRadius(mOriginLeash, windowRadius)
                .setWindowCrop(mOriginLeash, displayBounds.width(), displayBounds.height());

        // Process surfaces
        List<SurfaceControl> openingSurfaces = new ArrayList<>();
        List<SurfaceControl> closingSurfaces = new ArrayList<>();
        for (Change change : info.getChanges()) {
            int mode = change.getMode();
            SurfaceControl leash = change.getLeash();
            // Reparent leash to the transition root.
            mStartTransaction.reparent(leash, rootLeash);
            if (TransitionUtil.isOpeningMode(mode)) {
                openingSurfaces.add(change.getLeash());
                // For opening surfaces, ending bounds are base bound. Apply corner radius if
                // it's full screen.
                Rect bounds = change.getEndAbsBounds();
                if (displayBounds.equals(bounds)) {
                    mStartTransaction
                            .setCornerRadius(leash, windowRadius)
                            .setWindowCrop(leash, bounds.width(), bounds.height());
                }
            } else if (TransitionUtil.isClosingMode(mode)) {
                closingSurfaces.add(change.getLeash());
                // For closing surfaces, starting bounds are base bounds. Apply corner radius if
                // it's full screen.
                Rect bounds = change.getStartAbsBounds();
                if (displayBounds.equals(bounds)) {
                    mStartTransaction
                            .setCornerRadius(leash, windowRadius)
                            .setWindowCrop(leash, bounds.width(), bounds.height());
                }
            }
        }

        // Set relative order:
        // ----  App1  ----
        // ---- origin ----
        // ----  App2  ----
        if (mIsEntry) {
            mStartTransaction
                    .setRelativeLayer(mOriginLeash, closingSurfaces.get(0), 1)
                    .setRelativeLayer(
                            openingSurfaces.get(openingSurfaces.size() - 1), mOriginLeash, 1);
        } else {
            mStartTransaction
                    .setRelativeLayer(mOriginLeash, openingSurfaces.get(0), 1)
                    .setRelativeLayer(
                            closingSurfaces.get(closingSurfaces.size() - 1), mOriginLeash, 1);
        }

        // Attach origin UIComponent to origin leash.
        mOriginTransaction = mOrigin.newTransaction();
        mOriginTransaction
                .attachToTransitionLeash(
                        mOrigin, mOriginLeash, displayBounds.width(), displayBounds.height())
                .commit();

        // Apply all surface changes.
        mStartTransaction.apply();
        return true;
    }

    private Rect getDisplayBounds(int displayId) {
        DisplayManager dm = mContext.getSystemService(DisplayManager.class);
        DisplayMetrics metrics = new DisplayMetrics();
        dm.getDisplay(displayId).getMetrics(metrics);
        return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
    }

    private void finishAnimation(boolean finished) {
        logD("finishAnimation: finished=" + finished);
        if (mAnimator == null) {
            // The transition didn't start. Ensure we apply the start transaction and report
            // finish afterwards.
            mStartTransaction
                    .addTransactionCommittedListener(mHandler::post, this::finishInternal)
                    .apply();
            return;
        }
        mAnimator = null;
        // Notify client that we have ended.
        mPlayer.onEnd(finished);
        // Detach the origin from the transition leash and report finish after it's done.
        mOriginTransaction
                .detachFromTransitionLeash(mOrigin, mHandler::post, this::finishInternal)
                .commit();
    }

    private void finishInternal() {
        logD("finishInternal");
        if (mOriginLeash != null) {
            // Release origin leash.
            mOriginLeash.release();
            mOriginLeash = null;
        }
        try {
            mFinishCallback.onTransitionFinished(null, null);
        } catch (RemoteException e) {
            logE("Unable to report transition finish!", e);
        }
        mStartTransaction = null;
        mOriginTransaction = null;
        mFinishCallback = null;
    }

    private void cancel() {
        if (mAnimator != null) {
            mAnimator.cancel();
        }
    }

    private static void logD(String msg) {
        if (OriginTransitionSession.DEBUG) {
            Log.d(TAG, msg);
        }
    }

    private static void logE(String msg) {
        Log.e(TAG, msg);
    }

    private static void logE(String msg, Throwable e) {
        Log.e(TAG, msg, e);
    }

    private static UIComponent wrapSurfaces(TransitionInfo info, boolean isOpening) {
        List<SurfaceControl> surfaces = new ArrayList<>();
        Rect maxBounds = new Rect();
        for (Change change : info.getChanges()) {
            int mode = change.getMode();
            if (TransitionUtil.isOpeningMode(mode) == isOpening) {
                surfaces.add(change.getLeash());
                Rect bounds = isOpening ? change.getEndAbsBounds() : change.getStartAbsBounds();
                maxBounds.union(bounds);
            }
        }
        return new SurfaceUIComponent(
                surfaces,
                /* alpha= */ 1.0f,
                /* visible= */ true,
                /* bounds= */ maxBounds,
                /* baseBounds= */ maxBounds);
    }

    /**
     * An interface that represents an origin transitions.
     *
     * @hide
     */
    public interface TransitionPlayer {

        /**
         * Called when an origin transition starts. This method exposes the raw {@link
         * TransitionInfo} so that clients can extract more information from it.
         */
        default void onStart(
                TransitionInfo transitionInfo,
                SurfaceControl.Transaction sfTransaction,
                UIComponent origin,
                UIComponent.Transaction uiTransaction) {
            // Wrap transactions.
            Transactions transactions =
                    new Transactions()
                            .registerTransactionForClass(origin.getClass(), uiTransaction)
                            .registerTransactionForClass(
                                    SurfaceUIComponent.class,
                                    new SurfaceUIComponent.Transaction(sfTransaction));
            // Wrap surfaces and start.
            onStart(
                    transactions,
                    origin,
                    wrapSurfaces(transitionInfo, /* isOpening= */ false),
                    wrapSurfaces(transitionInfo, /* isOpening= */ true));
        }

        /**
         * Called when an origin transition starts. This method exposes the opening and closing
         * windows as wrapped {@link UIComponent} to provide simplified interface to clients.
         */
        void onStart(
                UIComponent.Transaction transaction,
                UIComponent origin,
                UIComponent closingApp,
                UIComponent openingApp);

        /** Called to update the transition frame. */
        void onProgress(float progress);

        /** Called when the transition ended. */
        void onEnd(boolean finished);
    }
}
