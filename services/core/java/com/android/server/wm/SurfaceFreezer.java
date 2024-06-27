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
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_SCREEN_ROTATION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.GraphicBuffer;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.util.Slog;
import android.view.SurfaceControl;
import android.window.ScreenCapture;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.ProtoLog;

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

    private static final String TAG = "SurfaceFreezer";

    private final @NonNull Freezable mAnimatable;
    private final @NonNull WindowManagerService mWmService;
    @VisibleForTesting
    SurfaceControl mLeash;
    Snapshot mSnapshot = null;
    final Rect mFreezeBounds = new Rect();

    /**
     * @param animatable The object to animate.
     */
    SurfaceFreezer(@NonNull Freezable animatable, @NonNull WindowManagerService service) {
        mAnimatable = animatable;
        mWmService = service;
    }

    /**
     * Freeze the target surface. This is done by creating a leash (inserting a parent surface
     * above the target surface) and then taking a snapshot and placing it over the target surface.
     *
     * @param startBounds The original bounds (on screen) of the surface we are snapshotting.
     * @param relativePosition The related position of the snapshot surface to its parent.
     * @param freezeTarget The surface to take snapshot from. If {@code null}, we will take a
     *                     snapshot from the {@link #mAnimatable} surface.
     */
    void freeze(SurfaceControl.Transaction t, Rect startBounds, Point relativePosition,
            @Nullable SurfaceControl freezeTarget) {
        reset(t);
        mFreezeBounds.set(startBounds);

        mLeash = SurfaceAnimator.createAnimationLeash(mAnimatable, mAnimatable.getSurfaceControl(),
                t, ANIMATION_TYPE_SCREEN_ROTATION, startBounds.width(), startBounds.height(),
                relativePosition.x, relativePosition.y, false /* hidden */,
                mWmService.mTransactionFactory);
        mAnimatable.onAnimationLeashCreated(t, mLeash);

        freezeTarget = freezeTarget != null ? freezeTarget : mAnimatable.getFreezeSnapshotTarget();
        if (freezeTarget != null) {
            ScreenCapture.ScreenshotHardwareBuffer screenshotBuffer = createSnapshotBufferInner(
                    freezeTarget, startBounds);
            final HardwareBuffer buffer = screenshotBuffer == null ? null
                    : screenshotBuffer.getHardwareBuffer();
            if (buffer == null || buffer.getWidth() <= 1 || buffer.getHeight() <= 1) {
                // This can happen when display is not ready.
                Slog.w(TAG, "Failed to capture screenshot for " + mAnimatable);
                unfreeze(t);
                return;
            }
            mSnapshot = new Snapshot(t, screenshotBuffer, mLeash);
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
     * Used by {@link SurfaceAnimator}. This "transfers" the snapshot leash to be used for
     * animation. By transferring the leash, this will no longer try to clean-up the leash when
     * finished.
     */
    @Nullable
    Snapshot takeSnapshotForAnimation() {
        final Snapshot out = mSnapshot;
        mSnapshot = null;
        return out;
    }

    /**
     * Clean-up the snapshot and remove leash. If the leash was taken, this just cleans-up the
     * snapshot.
     */
    void unfreeze(SurfaceControl.Transaction t) {
        unfreezeInner(t);
        mAnimatable.onUnfrozen();
    }

    private void unfreezeInner(SurfaceControl.Transaction t) {
        if (mSnapshot != null) {
            mSnapshot.cancelAnimation(t, false /* restarting */);
            mSnapshot = null;
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

    /** Resets the snapshot before taking another one if the animation hasn't been started yet. */
    private void reset(SurfaceControl.Transaction t) {
        // Those would have been taken by the SurfaceAnimator if the animation has been started, so
        // we can remove the leash directly.
        // No need to reset the mAnimatable leash, as this is called before a new animation leash is
        // created, so another #onAnimationLeashCreated will be called.
        if (mSnapshot != null) {
            mSnapshot.destroy(t);
            mSnapshot = null;
        }
        if (mLeash != null) {
            t.remove(mLeash);
            mLeash = null;
        }
    }

    void setLayer(SurfaceControl.Transaction t, int layer) {
        if (mLeash != null) {
            t.setLayer(mLeash, layer);
        }
    }

    void setRelativeLayer(SurfaceControl.Transaction t, SurfaceControl relativeTo, int layer) {
        if (mLeash != null) {
            t.setRelativeLayer(mLeash, relativeTo, layer);
        }
    }

    boolean hasLeash() {
        return mLeash != null;
    }

    private static ScreenCapture.ScreenshotHardwareBuffer createSnapshotBuffer(
            @NonNull SurfaceControl target, @Nullable Rect bounds) {
        Rect cropBounds = null;
        if (bounds != null) {
            cropBounds = new Rect(bounds);
            cropBounds.offsetTo(0, 0);
        }
        ScreenCapture.LayerCaptureArgs captureArgs =
                new ScreenCapture.LayerCaptureArgs.Builder(target)
                        .setSourceCrop(cropBounds)
                        .setCaptureSecureLayers(true)
                        .setAllowProtected(true)
                        .build();
        return ScreenCapture.captureLayers(captureArgs);
    }

    @VisibleForTesting
    ScreenCapture.ScreenshotHardwareBuffer createSnapshotBufferInner(
            SurfaceControl target, Rect bounds) {
        return createSnapshotBuffer(target, bounds);
    }

    @VisibleForTesting
    GraphicBuffer createFromHardwareBufferInner(
            ScreenCapture.ScreenshotHardwareBuffer screenshotBuffer) {
        return GraphicBuffer.createFromHardwareBuffer(screenshotBuffer.getHardwareBuffer());
    }

    class Snapshot {
        private SurfaceControl mSurfaceControl;
        private AnimationAdapter mAnimation;

        /**
         * @param t Transaction to create the thumbnail in.
         * @param screenshotBuffer A thumbnail or placeholder for thumbnail to initialize with.
         */
        Snapshot(SurfaceControl.Transaction t,
                ScreenCapture.ScreenshotHardwareBuffer screenshotBuffer, SurfaceControl parent) {
            GraphicBuffer graphicBuffer = createFromHardwareBufferInner(screenshotBuffer);

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
         */
        void startAnimation(SurfaceControl.Transaction t, AnimationAdapter anim, int type) {
            cancelAnimation(t, true /* restarting */);
            mAnimation = anim;
            if (mSurfaceControl == null) {
                cancelAnimation(t, false /* restarting */);
                return;
            }
            mAnimation.startAnimation(mSurfaceControl, t, type, (typ, ani) -> { });
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
            mAnimation = null;
            if (animation != null) {
                animation.onAnimationCancelled(leash);
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

        /** Called when the {@link #unfreeze(SurfaceControl.Transaction)} is called. */
        void onUnfrozen();
    }
}
