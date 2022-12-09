/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.util.TimeUtils.NANOS_PER_MS;
import static android.view.Choreographer.CALLBACK_TRAVERSAL;
import static android.view.Choreographer.getSfInstance;

import android.animation.AnimationHandler;
import android.animation.AnimationHandler.AnimationFrameCallbackProvider;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.power.Boost;
import android.os.Handler;
import android.os.PowerManagerInternal;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.Log;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.animation.Animation;
import android.view.animation.Transformation;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.server.AnimationThread;
import com.android.server.wm.LocalAnimationAdapter.AnimationSpec;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Class to run animations without holding the window manager lock.
 */
class SurfaceAnimationRunner {

    private final Object mLock = new Object();

    /**
     * Lock for cancelling animations. Must be acquired on it's own, or after acquiring
     * {@link #mLock}
     */
    private final Object mCancelLock = new Object();

    /**
     * Lock for synchronizing {@link #mEdgeExtensions} to prevent race conditions when managing
     * created edge extension surfaces.
     */
    private final Object mEdgeExtensionLock = new Object();

    @VisibleForTesting
    Choreographer mChoreographer;

    private final Handler mAnimationThreadHandler = AnimationThread.getHandler();
    private final Handler mSurfaceAnimationHandler = SurfaceAnimationThread.getHandler();
    private final Runnable mApplyTransactionRunnable = this::applyTransaction;
    private final AnimationHandler mAnimationHandler;
    private final Transaction mFrameTransaction;
    private final AnimatorFactory mAnimatorFactory;
    private final PowerManagerInternal mPowerManagerInternal;
    private boolean mApplyScheduled;

    // Executor to perform the edge extension.
    // With two threads because in practice we will want to extend two surfaces in one animation,
    // in which case we want to be able to parallelize those two extensions to cut down latency in
    // starting the animation.
    private final ExecutorService mEdgeExtensionExecutor = Executors.newFixedThreadPool(2);

    @GuardedBy("mLock")
    @VisibleForTesting
    final ArrayMap<SurfaceControl, RunningAnimation> mPendingAnimations = new ArrayMap<>();

    @GuardedBy("mLock")
    @VisibleForTesting
    final ArrayMap<SurfaceControl, RunningAnimation> mPreProcessingAnimations = new ArrayMap<>();

    @GuardedBy("mLock")
    @VisibleForTesting
    final ArrayMap<SurfaceControl, RunningAnimation> mRunningAnimations = new ArrayMap<>();

    @GuardedBy("mLock")
    private boolean mAnimationStartDeferred;

    // Mapping animation leashes to a list of edge extension surfaces associated with them
    @GuardedBy("mEdgeExtensionLock")
    private final ArrayMap<SurfaceControl, ArrayList<SurfaceControl>> mEdgeExtensions =
            new ArrayMap<>();

    /**
     * There should only ever be one instance of this class. Usual spot for it is with
     * {@link WindowManagerService}
     */
    SurfaceAnimationRunner(Supplier<Transaction> transactionFactory,
            PowerManagerInternal powerManagerInternal) {
        this(null /* callbackProvider */, null /* animatorFactory */,
                transactionFactory.get(), powerManagerInternal);
    }

    @VisibleForTesting
    SurfaceAnimationRunner(@Nullable AnimationFrameCallbackProvider callbackProvider,
            AnimatorFactory animatorFactory, Transaction frameTransaction,
            PowerManagerInternal powerManagerInternal) {
        mSurfaceAnimationHandler.runWithScissors(() -> mChoreographer = getSfInstance(),
                0 /* timeout */);
        mFrameTransaction = frameTransaction;
        mAnimationHandler = new AnimationHandler();
        mAnimationHandler.setProvider(callbackProvider != null
                ? callbackProvider
                : new SfVsyncFrameCallbackProvider(mChoreographer));
        mAnimatorFactory = animatorFactory != null
                ? animatorFactory
                : SfValueAnimator::new;
        mPowerManagerInternal = powerManagerInternal;
    }

    /**
     * Defers starting of animations until {@link #continueStartingAnimations} is called. This
     * method is NOT nestable.
     *
     * @see #continueStartingAnimations
     */
    void deferStartingAnimations() {
        synchronized (mLock) {
            mAnimationStartDeferred = true;
        }
    }

    /**
     * Continues starting of animations.
     *
     * @see #deferStartingAnimations
     */
    void continueStartingAnimations() {
        synchronized (mLock) {
            mAnimationStartDeferred = false;
            if (!mPendingAnimations.isEmpty() && mPreProcessingAnimations.isEmpty()) {
                mChoreographer.postFrameCallback(this::startAnimations);
            }
        }
    }

    void startAnimation(AnimationSpec a, SurfaceControl animationLeash, Transaction t,
            Runnable finishCallback) {
        synchronized (mLock) {
            final RunningAnimation runningAnim = new RunningAnimation(a, animationLeash,
                    finishCallback);
            boolean requiresEdgeExtension = requiresEdgeExtension(a);

            if (requiresEdgeExtension) {
                final ArrayList<SurfaceControl> extensionSurfaces = new ArrayList<>();
                synchronized (mEdgeExtensionLock) {
                    mEdgeExtensions.put(animationLeash, extensionSurfaces);
                }

                mPreProcessingAnimations.put(animationLeash, runningAnim);

                // We must wait for t to be committed since otherwise the leash doesn't have the
                // windows we want to screenshot and extend as children.
                t.addTransactionCommittedListener(mEdgeExtensionExecutor, () -> {
                    final WindowAnimationSpec animationSpec = a.asWindowAnimationSpec();

                    final Transaction edgeExtensionCreationTransaction = new Transaction();
                    edgeExtendWindow(animationLeash,
                            animationSpec.getRootTaskBounds(), animationSpec.getAnimation(),
                            edgeExtensionCreationTransaction);

                    synchronized (mLock) {
                        // only run if animation is not yet canceled by this point
                        if (mPreProcessingAnimations.get(animationLeash) == runningAnim) {
                            // In the case the animation is cancelled, edge extensions are removed
                            // onAnimationLeashLost which is called before onAnimationCancelled.
                            // So we need to check if the edge extensions have already been removed
                            // or not, and if so we don't want to apply the transaction.
                            synchronized (mEdgeExtensionLock) {
                                if (!mEdgeExtensions.isEmpty()) {
                                    edgeExtensionCreationTransaction.apply();
                                }
                            }

                            mPreProcessingAnimations.remove(animationLeash);
                            mPendingAnimations.put(animationLeash, runningAnim);
                            if (!mAnimationStartDeferred && mPreProcessingAnimations.isEmpty()) {
                                mChoreographer.postFrameCallback(this::startAnimations);
                            }
                        }
                    }
                });
            }

            if (!requiresEdgeExtension) {
                mPendingAnimations.put(animationLeash, runningAnim);
                if (!mAnimationStartDeferred && mPreProcessingAnimations.isEmpty()) {
                    mChoreographer.postFrameCallback(this::startAnimations);
                }

                // Some animations (e.g. move animations) require the initial transform to be
                // applied immediately.
                applyTransformation(runningAnim, t, 0 /* currentPlayTime */);
            }
        }
    }

    private boolean requiresEdgeExtension(AnimationSpec a) {
        return a.asWindowAnimationSpec() != null && a.asWindowAnimationSpec().hasExtension();
    }

    void onAnimationCancelled(SurfaceControl leash) {
        synchronized (mLock) {
            if (mPendingAnimations.containsKey(leash)) {
                mPendingAnimations.remove(leash);
                return;
            }
            if (mPreProcessingAnimations.containsKey(leash)) {
                mPreProcessingAnimations.remove(leash);
                return;
            }
            final RunningAnimation anim = mRunningAnimations.get(leash);
            if (anim != null) {
                mRunningAnimations.remove(leash);
                synchronized (mCancelLock) {
                    anim.mCancelled = true;
                }
                mSurfaceAnimationHandler.post(() -> {
                    anim.mAnim.cancel();
                    applyTransaction();
                });
            }
        }
    }

    @GuardedBy("mLock")
    private void startPendingAnimationsLocked() {
        for (int i = mPendingAnimations.size() - 1; i >= 0; i--) {
            startAnimationLocked(mPendingAnimations.valueAt(i));
        }
        mPendingAnimations.clear();
    }

    @GuardedBy("mLock")
    private void startAnimationLocked(RunningAnimation a) {
        final ValueAnimator anim = mAnimatorFactory.makeAnimator();

        // Animation length is already expected to be scaled.
        anim.overrideDurationScale(1.0f);
        anim.setDuration(a.mAnimSpec.getDuration());
        anim.addUpdateListener(animation -> {
            synchronized (mCancelLock) {
                if (!a.mCancelled) {
                    final long duration = anim.getDuration();
                    long currentPlayTime = anim.getCurrentPlayTime();
                    if (currentPlayTime > duration) {
                        currentPlayTime = duration;
                    }
                    applyTransformation(a, mFrameTransaction, currentPlayTime);
                }
            }

            // Transaction will be applied in the commit phase.
            scheduleApplyTransaction();
        });

        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                synchronized (mCancelLock) {
                    if (!a.mCancelled) {
                        // TODO: change this back to use show instead of alpha when b/138459974 is
                        // fixed.
                        mFrameTransaction.setAlpha(a.mLeash, 1);
                    }
                }
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                synchronized (mLock) {
                    mRunningAnimations.remove(a.mLeash);
                    synchronized (mCancelLock) {
                        if (!a.mCancelled) {

                            // Post on other thread that we can push final state without jank.
                            mAnimationThreadHandler.post(a.mFinishCallback);
                        }
                    }
                }
            }
        });
        a.mAnim = anim;
        mRunningAnimations.put(a.mLeash, a);

        anim.start();
        if (a.mAnimSpec.canSkipFirstFrame()) {
            // If we can skip the first frame, we start one frame later.
            anim.setCurrentPlayTime(mChoreographer.getFrameIntervalNanos() / NANOS_PER_MS);
        }

        // Immediately start the animation by manually applying an animation frame. Otherwise, the
        // start time would only be set in the next frame, leading to a delay.
        anim.doAnimationFrame(mChoreographer.getFrameTime());
    }

    private void applyTransformation(RunningAnimation a, Transaction t, long currentPlayTime) {
        a.mAnimSpec.apply(t, a.mLeash, currentPlayTime);
    }

    private void startAnimations(long frameTimeNanos) {
        synchronized (mLock) {
            if (!mPreProcessingAnimations.isEmpty()) {
                // We only want to start running animations once all mPreProcessingAnimations have
                // been processed to ensure preprocessed animations start in sync.
                // NOTE: This means we might delay running animations that require preprocessing if
                // new animations that also require preprocessing are requested before the previous
                // ones have finished (see b/227449117).
                return;
            }
            startPendingAnimationsLocked();
        }
        mPowerManagerInternal.setPowerBoost(Boost.INTERACTION, 0);
    }

    private void scheduleApplyTransaction() {
        if (!mApplyScheduled) {
            mChoreographer.postCallback(CALLBACK_TRAVERSAL, mApplyTransactionRunnable,
                    null /* token */);
            mApplyScheduled = true;
        }
    }

    private void applyTransaction() {
        mFrameTransaction.setAnimationTransaction();
        mFrameTransaction.setFrameTimelineVsync(mChoreographer.getVsyncId());
        mFrameTransaction.apply();
        mApplyScheduled = false;
    }

    private void edgeExtendWindow(SurfaceControl leash, Rect bounds, Animation a,
            Transaction transaction) {
        final Transformation transformationAtStart = new Transformation();
        a.getTransformationAt(0, transformationAtStart);
        final Transformation transformationAtEnd = new Transformation();
        a.getTransformationAt(1, transformationAtEnd);

        // We want to create an extension surface that is the maximal size and the animation will
        // take care of cropping any part that overflows.
        final Insets maxExtensionInsets = Insets.min(
                transformationAtStart.getInsets(), transformationAtEnd.getInsets());

        final int targetSurfaceHeight = bounds.height();
        final int targetSurfaceWidth = bounds.width();

        if (maxExtensionInsets.left < 0) {
            final Rect edgeBounds = new Rect(bounds.left, bounds.top, bounds.left + 1,
                    bounds.bottom);
            final Rect extensionRect = new Rect(0, 0,
                    -maxExtensionInsets.left, targetSurfaceHeight);
            final int xPos = bounds.left + maxExtensionInsets.left;
            final int yPos = bounds.top;
            createExtensionSurface(leash, edgeBounds,
                    extensionRect, xPos, yPos, "Left Edge Extension", transaction);
        }

        if (maxExtensionInsets.top < 0) {
            final Rect edgeBounds = new Rect(bounds.left, bounds.top, targetSurfaceWidth,
                    bounds.top + 1);
            final Rect extensionRect = new Rect(0, 0,
                    targetSurfaceWidth, -maxExtensionInsets.top);
            final int xPos = bounds.left;
            final int yPos = bounds.top + maxExtensionInsets.top;
            createExtensionSurface(leash, edgeBounds,
                    extensionRect, xPos, yPos, "Top Edge Extension", transaction);
        }

        if (maxExtensionInsets.right < 0) {
            final Rect edgeBounds = new Rect(bounds.right - 1, bounds.top, bounds.right,
                    bounds.bottom);
            final Rect extensionRect = new Rect(0, 0,
                    -maxExtensionInsets.right, targetSurfaceHeight);
            final int xPos = bounds.right;
            final int yPos = bounds.top;
            createExtensionSurface(leash, edgeBounds,
                    extensionRect, xPos, yPos, "Right Edge Extension", transaction);
        }

        if (maxExtensionInsets.bottom < 0) {
            final Rect edgeBounds = new Rect(bounds.left, bounds.bottom - 1,
                    bounds.right, bounds.bottom);
            final Rect extensionRect = new Rect(0, 0,
                    targetSurfaceWidth, -maxExtensionInsets.bottom);
            final int xPos = bounds.left;
            final int yPos = bounds.bottom;
            createExtensionSurface(leash, edgeBounds,
                    extensionRect, xPos, yPos, "Bottom Edge Extension", transaction);
        }
    }

    private void createExtensionSurface(SurfaceControl leash, Rect edgeBounds,
            Rect extensionRect, int xPos, int yPos, String layerName,
            Transaction startTransaction) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "createExtensionSurface");
        doCreateExtensionSurface(leash, edgeBounds, extensionRect, xPos, yPos, layerName,
                startTransaction);
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }

    private void doCreateExtensionSurface(SurfaceControl leash, Rect edgeBounds,
            Rect extensionRect, int xPos, int yPos, String layerName,
            Transaction startTransaction) {
        SurfaceControl.LayerCaptureArgs captureArgs =
                new SurfaceControl.LayerCaptureArgs.Builder(leash /* surfaceToExtend */)
                        .setSourceCrop(edgeBounds)
                        .setFrameScale(1)
                        .setPixelFormat(PixelFormat.RGBA_8888)
                        .setChildrenOnly(true)
                        .setAllowProtected(true)
                        .build();
        final SurfaceControl.ScreenshotHardwareBuffer edgeBuffer =
                SurfaceControl.captureLayers(captureArgs);

        if (edgeBuffer == null) {
            // The leash we are trying to screenshot may have been removed by this point, which is
            // likely the reason for ending up with a null edgeBuffer, in which case we just want to
            // return and do nothing.
            Log.e("SurfaceAnimationRunner", "Failed to create edge extension - "
                    + "edge buffer is null");
            return;
        }

        final SurfaceControl edgeExtensionLayer = new SurfaceControl.Builder()
                .setName(layerName)
                .setHidden(true)
                .setCallsite("DefaultTransitionHandler#startAnimation")
                .setOpaque(true)
                .setBufferSize(extensionRect.width(), extensionRect.height())
                .build();

        BitmapShader shader = new BitmapShader(edgeBuffer.asBitmap(),
                android.graphics.Shader.TileMode.CLAMP,
                android.graphics.Shader.TileMode.CLAMP);
        final Paint paint = new Paint();
        paint.setShader(shader);

        final Surface surface = new Surface(edgeExtensionLayer);
        Canvas c = surface.lockHardwareCanvas();
        c.drawRect(extensionRect, paint);
        surface.unlockCanvasAndPost(c);
        surface.release();

        synchronized (mEdgeExtensionLock) {
            if (!mEdgeExtensions.containsKey(leash)) {
                // The animation leash has already been removed, so we don't want to attach the
                // edgeExtension layer and should immediately remove it instead.
                startTransaction.remove(edgeExtensionLayer);
                return;
            }

            startTransaction.reparent(edgeExtensionLayer, leash);
            startTransaction.setLayer(edgeExtensionLayer, Integer.MIN_VALUE);
            startTransaction.setPosition(edgeExtensionLayer, xPos, yPos);
            startTransaction.setVisibility(edgeExtensionLayer, true);

            mEdgeExtensions.get(leash).add(edgeExtensionLayer);
        }
    }

    private float getScaleXForExtensionSurface(Rect edgeBounds, Rect extensionRect) {
        if (edgeBounds.width() == extensionRect.width()) {
            // Top or bottom edge extension, no need to scale the X axis of the extension surface.
            return 1;
        }
        if (edgeBounds.width() == 1) {
            // Left or right edge extension, scale the surface to be the extensionRect's width.
            return extensionRect.width();
        }

        throw new RuntimeException("Unexpected edgeBounds and extensionRect widths");
    }

    private float getScaleYForExtensionSurface(Rect edgeBounds, Rect extensionRect) {
        if (edgeBounds.height() == extensionRect.height()) {
            // Left or right edge extension, no need to scale the Y axis of the extension surface.
            return 1;
        }
        if (edgeBounds.height() == 1) {
            // Top or bottom edge extension, scale the surface to be the extensionRect's height.
            return extensionRect.height();
        }

        throw new RuntimeException("Unexpected edgeBounds and extensionRect heights");
    }

    private static final class RunningAnimation {
        final AnimationSpec mAnimSpec;
        final SurfaceControl mLeash;
        final Runnable mFinishCallback;
        ValueAnimator mAnim;

        @GuardedBy("mCancelLock")
        private boolean mCancelled;

        RunningAnimation(AnimationSpec animSpec, SurfaceControl leash, Runnable finishCallback) {
            mAnimSpec = animSpec;
            mLeash = leash;
            mFinishCallback = finishCallback;
        }
    }

    protected void onAnimationLeashLost(SurfaceControl animationLeash,
            Transaction t) {
        synchronized (mEdgeExtensionLock) {
            if (!mEdgeExtensions.containsKey(animationLeash)) {
                return;
            }

            final ArrayList<SurfaceControl> edgeExtensions = mEdgeExtensions.get(animationLeash);
            for (int i = 0; i < edgeExtensions.size(); i++) {
                final SurfaceControl extension = edgeExtensions.get(i);
                t.remove(extension);
            }
            mEdgeExtensions.remove(animationLeash);
        }
    }

    @VisibleForTesting
    interface AnimatorFactory {
        ValueAnimator makeAnimator();
    }

    /**
     * Value animator that uses sf-vsync signal to tick.
     */
    private class SfValueAnimator extends ValueAnimator {

        SfValueAnimator() {
            setFloatValues(0f, 1f);
        }

        @Override
        public AnimationHandler getAnimationHandler() {
            return mAnimationHandler;
        }
    }
}