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

import static com.android.internal.protolog.ProtoLogGroup.WM_SHOW_TRANSACTIONS;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_APP_TRANSITION;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_SCREEN_ROTATION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.GraphicBuffer;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.view.Surface;
import android.view.SurfaceControl;

import com.android.internal.protolog.common.ProtoLog;

import java.util.function.Supplier;

/**
 * This class handles "freezing" of an Animatable. The Animatable in question should implement
 * Freezable.
 *
 * The point of this is to enable WindowContainers to each be capable of freezing themselves.
 * Freezing means taking a snapshot and placing it above everything in the sub-hierarchy.
 * The "placing above" requires that a parent surface be inserted above the target surface so that
 * the target surface and the snapshot are siblings.
 *
 * The overall flow for a transition using this would be:
 * 1. Set transition and record animatable in mChangingApps
 * 2. Call {@link #freeze} to set-up the leashes and cover with a snapshot.
 * 3. When transition participants are ready, start SurfaceAnimator with this as a parameter
 * 4. SurfaceAnimator will then {@link #takeLeashForAnimation} instead of creating another leash.
 * 5. The animation system should eventually clean this up via {@link #unfreeze}.
 */
class SurfaceFreezer {

    private final Freezable mAnimatable;
    private final WindowManagerService mWmService;
    private SurfaceControl mLeash;
    Snapshot mSnapshot = null;
    final Rect mFreezeBounds = new Rect();

    /**
     * @param animatable The object to animate.
     */
    SurfaceFreezer(Freezable animatable, WindowManagerService service) {
        mAnimatable = animatable;
        mWmService = service;
    }

    /**
     * Freeze the target surface. This is done by creating a leash (inserting a parent surface
     * above the target surface) and then taking a snapshot and placing it over the target surface.
     *
     * @param startBounds The original bounds (on screen) of the surface we are snapshotting.
     */
    void freeze(SurfaceControl.Transaction t, Rect startBounds) {
        mFreezeBounds.set(startBounds);

        mLeash = SurfaceAnimator.createAnimationLeash(mAnimatable, mAnimatable.getSurfaceControl(),
                t, ANIMATION_TYPE_SCREEN_ROTATION, startBounds.width(), startBounds.height(),
                startBounds.left, startBounds.top, false /* hidden */,
                mWmService.mTransactionFactory);
        mAnimatable.onAnimationLeashCreated(t, mLeash);

        SurfaceControl freezeTarget = mAnimatable.getFreezeSnapshotTarget();
        if (freezeTarget != null) {
            SurfaceControl.ScreenshotHardwareBuffer screenshotBuffer = createSnapshotBuffer(
                    freezeTarget, startBounds);
            final HardwareBuffer buffer = screenshotBuffer == null ? null
                    : screenshotBuffer.getHardwareBuffer();
            if (buffer == null || buffer.getWidth() <= 1 || buffer.getHeight() <= 1) {
                return;
            }
            mSnapshot = new Snapshot(mWmService.mSurfaceFactory, t, screenshotBuffer, mLeash);
        }
    }

    /**
     * Used by {@link SurfaceAnimator}. This "transfers" the leash to be used for animation.
     * By transferring the leash, this will no longer try to clean-up the leash when finished.
     */
    SurfaceControl takeLeashForAnimation() {
        SurfaceControl out = mLeash;
        mLeash = null;
        return out;
    }

    /**
     * Clean-up the snapshot and remove leash. If the leash was taken, this just cleans-up the
     * snapshot.
     */
    void unfreeze(SurfaceControl.Transaction t) {
        if (mSnapshot != null) {
            mSnapshot.cancelAnimation(t, false /* restarting */);
        }
        if (mLeash == null) {
            return;
        }
        SurfaceControl leash = mLeash;
        mLeash = null;
        final boolean scheduleAnim = SurfaceAnimator.removeLeash(t, mAnimatable, leash,
                true /* destroy */);
        if (scheduleAnim) {
            mWmService.scheduleAnimationLocked();
        }
    }

    boolean hasLeash() {
        return mLeash != null;
    }

    private static SurfaceControl.ScreenshotHardwareBuffer createSnapshotBuffer(
            @NonNull SurfaceControl target, @Nullable Rect bounds) {
        Rect cropBounds = null;
        if (bounds != null) {
            cropBounds = new Rect(bounds);
            cropBounds.offsetTo(0, 0);
        }
        SurfaceControl.LayerCaptureArgs captureArgs =
                new SurfaceControl.LayerCaptureArgs.Builder(target)
                        .setSourceCrop(cropBounds)
                        .setCaptureSecureLayers(true)
                        .setAllowProtected(true)
                        .build();
        return SurfaceControl.captureLayers(captureArgs);
    }

    class Snapshot {
        private SurfaceControl mSurfaceControl;
        private AnimationAdapter mAnimation;
        private SurfaceAnimator.OnAnimationFinishedCallback mFinishedCallback;

        /**
         * @param t Transaction to create the thumbnail in.
         * @param screenshotBuffer A thumbnail or placeholder for thumbnail to initialize with.
         */
        Snapshot(Supplier<Surface> surfaceFactory, SurfaceControl.Transaction t,
                SurfaceControl.ScreenshotHardwareBuffer screenshotBuffer, SurfaceControl parent) {
            // We can't use a delegating constructor since we need to
            // reference this::onAnimationFinished
            GraphicBuffer graphicBuffer = GraphicBuffer.createFromHardwareBuffer(
                    screenshotBuffer.getHardwareBuffer());

            mSurfaceControl = mAnimatable.makeAnimationLeash()
                    .setName("snapshot anim: " + mAnimatable.toString())
                    .setFormat(PixelFormat.TRANSLUCENT)
                    .setParent(parent)
                    .setSecure(screenshotBuffer.containsSecureLayers())
                    .setCallsite("SurfaceFreezer.Snapshot")
                    .setBLASTLayer()
                    .build();

            ProtoLog.i(WM_SHOW_TRANSACTIONS, "  THUMBNAIL %s: CREATE", mSurfaceControl);

            t.setBuffer(mSurfaceControl, graphicBuffer);
            t.setColorSpace(mSurfaceControl, screenshotBuffer.getColorSpace());
            t.show(mSurfaceControl);

            // We parent the thumbnail to the container, and just place it on top of anything else
            // in the container.
            t.setLayer(mSurfaceControl, Integer.MAX_VALUE);
        }

        void destroy(SurfaceControl.Transaction t) {
            if (mSurfaceControl == null) {
                return;
            }
            t.remove(mSurfaceControl);
            mSurfaceControl = null;
        }

        /**
         * Starts an animation.
         *
         * @param anim The object that bridges the controller, {@link SurfaceAnimator}, with the
         *             component responsible for running the animation. It runs the animation with
         *             {@link AnimationAdapter#startAnimation} once the hierarchy with
         *             the Leash has been set up.
         * @param animationFinishedCallback The callback being triggered when the animation
         *                                  finishes.
         */
        void startAnimation(SurfaceControl.Transaction t, AnimationAdapter anim, int type,
                @Nullable SurfaceAnimator.OnAnimationFinishedCallback animationFinishedCallback) {
            cancelAnimation(t, true /* restarting */);
            mAnimation = anim;
            mFinishedCallback = animationFinishedCallback;
            if (mSurfaceControl == null) {
                cancelAnimation(t, false /* restarting */);
                return;
            }
            mAnimation.startAnimation(mSurfaceControl, t, type, animationFinishedCallback);
        }

        /**
         * Cancels the animation, and resets the leash.
         *
         * @param t The transaction to use for all cancelling surface operations.
         * @param restarting Whether we are restarting the animation.
         */
        void cancelAnimation(SurfaceControl.Transaction t, boolean restarting) {
            final SurfaceControl leash = mSurfaceControl;
            final AnimationAdapter animation = mAnimation;
            final SurfaceAnimator.OnAnimationFinishedCallback animationFinishedCallback =
                    mFinishedCallback;
            mAnimation = null;
            mFinishedCallback = null;
            if (animation != null) {
                animation.onAnimationCancelled(leash);
                if (!restarting) {
                    if (animationFinishedCallback != null) {
                        animationFinishedCallback.onAnimationFinished(
                                ANIMATION_TYPE_APP_TRANSITION, animation);
                    }
                }
            }
            if (!restarting) {
                destroy(t);
            }
        }
    }

    /** freezable */
    public interface Freezable extends SurfaceAnimator.Animatable {
        /**
         * @return The surface to take a snapshot of. If this returns {@code null}, no snapshot
         *         will be generated (but the rest of the freezing logic will still happen).
         */
        @Nullable SurfaceControl getFreezeSnapshotTarget();
    }
}
