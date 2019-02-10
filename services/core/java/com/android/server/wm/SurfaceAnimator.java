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

import static com.android.server.wm.SurfaceAnimatorProto.ANIMATION_ADAPTER;
import static com.android.server.wm.SurfaceAnimatorProto.ANIMATION_START_DELAYED;
import static com.android.server.wm.SurfaceAnimatorProto.LEASH;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ANIM;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;

/**
 * A class that can run animations on objects that have a set of child surfaces. We do this by
 * reparenting all child surfaces of an object onto a new surface, called the "Leash". The Leash
 * gets attached in the surface hierarchy where the the children were attached to. We then hand off
 * the Leash to the component handling the animation, which is specified by the
 * {@link AnimationAdapter}. When the animation is done animating, our callback to finish the
 * animation will be invoked, at which we reparent the children back to the original parent.
 */
class SurfaceAnimator {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "SurfaceAnimator" : TAG_WM;
    private final WindowManagerService mService;
    private AnimationAdapter mAnimation;

    @VisibleForTesting
    SurfaceControl mLeash;
    private final Animatable mAnimatable;
    private final OnAnimationFinishedCallback mInnerAnimationFinishedCallback;
    @VisibleForTesting
    final Runnable mAnimationFinishedCallback;
    private boolean mAnimationStartDelayed;

    /**
     * @param animatable The object to animate.
     * @param animationFinishedCallback Callback to invoke when an animation has finished running.
     */
    SurfaceAnimator(Animatable animatable, @Nullable Runnable animationFinishedCallback,
            WindowManagerService service) {
        mAnimatable = animatable;
        mService = service;
        mAnimationFinishedCallback = animationFinishedCallback;
        mInnerAnimationFinishedCallback = getFinishedCallback(animationFinishedCallback);
    }

    private OnAnimationFinishedCallback getFinishedCallback(
            @Nullable Runnable animationFinishedCallback) {
        return anim -> {
            synchronized (mService.mGlobalLock) {
                final SurfaceAnimator target = mService.mAnimationTransferMap.remove(anim);
                if (target != null) {
                    target.mInnerAnimationFinishedCallback.onAnimationFinished(anim);
                    return;
                }

                if (anim != mAnimation) {
                    return;
                }
                final Runnable resetAndInvokeFinish = () -> {
                    reset(mAnimatable.getPendingTransaction(), true /* destroyLeash */);
                    if (animationFinishedCallback != null) {
                        animationFinishedCallback.run();
                    }
                };
                if (!mAnimatable.shouldDeferAnimationFinish(resetAndInvokeFinish)) {
                    resetAndInvokeFinish.run();
                }
            }
        };
    }

    /**
     * Starts an animation.
     *
     * @param anim The object that bridges the controller, {@link SurfaceAnimator}, with the
     *             component responsible for running the animation. It runs the animation with
     *             {@link AnimationAdapter#startAnimation} once the hierarchy with
     *             the Leash has been set up.
     * @param hidden Whether the container holding the child surfaces is currently visible or not.
     *               This is important as it will start with the leash hidden or visible before
     *               handing it to the component that is responsible to run the animation.
     */
    void startAnimation(Transaction t, AnimationAdapter anim, boolean hidden) {
        cancelAnimation(t, true /* restarting */, true /* forwardCancel */);
        mAnimation = anim;
        final SurfaceControl surface = mAnimatable.getSurfaceControl();
        if (surface == null) {
            Slog.w(TAG, "Unable to start animation, surface is null or no children.");
            cancelAnimation();
            return;
        }
        mLeash = createAnimationLeash(surface, t,
                mAnimatable.getSurfaceWidth(), mAnimatable.getSurfaceHeight(), hidden);
        mAnimatable.onAnimationLeashCreated(t, mLeash);
        if (mAnimationStartDelayed) {
            if (DEBUG_ANIM) Slog.i(TAG, "Animation start delayed");
            return;
        }
        mAnimation.startAnimation(mLeash, t, mInnerAnimationFinishedCallback);
    }

    /**
     * Begins with delaying all animations to start. Any subsequent call to {@link #startAnimation}
     * will not start the animation until {@link #endDelayingAnimationStart} is called. When an
     * animation start is being delayed, the animator is considered animating already.
     */
    void startDelayingAnimationStart() {

        // We only allow delaying animation start we are not currently animating
        if (!isAnimating()) {
            mAnimationStartDelayed = true;
        }
    }

    /**
     * See {@link #startDelayingAnimationStart}.
     */
    void endDelayingAnimationStart() {
        final boolean delayed = mAnimationStartDelayed;
        mAnimationStartDelayed = false;
        if (delayed && mAnimation != null) {
            mAnimation.startAnimation(mLeash, mAnimatable.getPendingTransaction(),
                    mInnerAnimationFinishedCallback);
            mAnimatable.commitPendingTransaction();
        }
    }

    /**
     * @return Whether we are currently running an animation, or we have a pending animation that
     *         is waiting to be started with {@link #endDelayingAnimationStart}
     */
    boolean isAnimating() {
        return mAnimation != null;
    }

    /**
     * @return The current animation spec if we are running an animation, or {@code null} otherwise.
     */
    AnimationAdapter getAnimation() {
        return mAnimation;
    }

    /**
     * Cancels any currently running animation.
     */
    void cancelAnimation() {
        cancelAnimation(mAnimatable.getPendingTransaction(), false /* restarting */,
                true /* forwardCancel */);
        mAnimatable.commitPendingTransaction();
    }

    /**
     * Sets the layer of the surface.
     * <p>
     * When the layer of the surface needs to be adjusted, we need to set it on the leash if the
     * surface is reparented to the leash. This method takes care of that.
     */
    void setLayer(Transaction t, int layer) {
        t.setLayer(mLeash != null ? mLeash : mAnimatable.getSurfaceControl(), layer);
    }

    /**
     * Sets the surface to be relatively layered.
     *
     * @see #setLayer
     */
    void setRelativeLayer(Transaction t, SurfaceControl relativeTo, int layer) {
        t.setRelativeLayer(mLeash != null ? mLeash : mAnimatable.getSurfaceControl(), relativeTo, layer);
    }

    /**
     * Reparents the surface.
     *
     * @see #setLayer
     */
    void reparent(Transaction t, SurfaceControl newParent) {
        t.reparent(mLeash != null ? mLeash : mAnimatable.getSurfaceControl(), newParent);
    }

    /**
     * @return True if the surface is attached to the leash; false otherwise.
     */
    boolean hasLeash() {
        return mLeash != null;
    }

    void transferAnimation(SurfaceAnimator from) {
        if (from.mLeash == null) {
            return;
        }
        final SurfaceControl surface = mAnimatable.getSurfaceControl();
        final SurfaceControl parent = mAnimatable.getAnimationLeashParent();
        if (surface == null || parent == null) {
            Slog.w(TAG, "Unable to transfer animation, surface or parent is null");
            cancelAnimation();
            return;
        }
        endDelayingAnimationStart();
        final Transaction t = mAnimatable.getPendingTransaction();
        cancelAnimation(t, true /* restarting */, true /* forwardCancel */);
        mLeash = from.mLeash;
        mAnimation = from.mAnimation;

        // Cancel source animation, but don't let animation runner cancel the animation.
        from.cancelAnimation(t, false /* restarting */, false /* forwardCancel */);
        t.reparent(surface, mLeash);
        t.reparent(mLeash, parent);
        mAnimatable.onAnimationLeashCreated(t, mLeash);
        mService.mAnimationTransferMap.put(mAnimation, this);
    }

    boolean isAnimationStartDelayed() {
        return mAnimationStartDelayed;
    }

    /**
     * Cancels the animation, and resets the leash.
     *
     * @param t The transaction to use for all cancelling surface operations.
     * @param restarting Whether we are restarting the animation.
     * @param forwardCancel Whether to forward the cancel signal to the adapter executing the
     *                      animation. This will be set to false when just transferring an animation
     *                      to another animator.
     */
    private void cancelAnimation(Transaction t, boolean restarting, boolean forwardCancel) {
        if (DEBUG_ANIM) Slog.i(TAG, "Cancelling animation restarting=" + restarting);
        final SurfaceControl leash = mLeash;
        final AnimationAdapter animation = mAnimation;
        reset(t, forwardCancel);
        if (animation != null) {
            if (!mAnimationStartDelayed && forwardCancel) {
                animation.onAnimationCancelled(leash);
            }
            if (!restarting) {
                mAnimationFinishedCallback.run();
            }
        }
        if (!restarting) {
            mAnimationStartDelayed = false;
        }
    }

    private void reset(Transaction t, boolean destroyLeash) {
        final SurfaceControl surface = mAnimatable.getSurfaceControl();
        final SurfaceControl parent = mAnimatable.getParentSurfaceControl();

        boolean scheduleAnim = false;

        // If the surface was destroyed, we don't care to reparent it back.
        final boolean destroy = mLeash != null && surface != null && parent != null;
        if (destroy) {
            if (DEBUG_ANIM) Slog.i(TAG, "Reparenting to original parent");
            t.reparent(surface, parent);
            scheduleAnim = true;
        }
        mService.mAnimationTransferMap.remove(mAnimation);
        if (mLeash != null && destroyLeash) {
            t.remove(mLeash);
            scheduleAnim = true;
        }
        mLeash = null;
        mAnimation = null;

        // Make sure to inform the animatable after the leash was destroyed.
        if (destroy) {
            mAnimatable.onAnimationLeashDestroyed(t);
            scheduleAnim = true;
        }

        if (scheduleAnim) {
            mService.scheduleAnimationLocked();
        }
    }

    private SurfaceControl createAnimationLeash(SurfaceControl surface, Transaction t, int width,
            int height, boolean hidden) {
        if (DEBUG_ANIM) Slog.i(TAG, "Reparenting to leash");
        final SurfaceControl.Builder builder = mAnimatable.makeAnimationLeash()
                .setParent(mAnimatable.getAnimationLeashParent())
                .setName(surface + " - animation-leash");
        final SurfaceControl leash = builder.build();
        t.setWindowCrop(leash, width, height);
        if (!hidden) {
            t.show(leash);
        }
        t.reparent(surface, leash);
        return leash;
    }

    /**
     * Write to a protocol buffer output stream. Protocol buffer message definition is at {@link
     * com.android.server.wm.SurfaceAnimatorProto}.
     *
     * @param proto Stream to write the SurfaceAnimator object to.
     * @param fieldId Field Id of the SurfaceAnimator as defined in the parent message.
     * @hide
     */
    void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        if (mAnimation != null) {
            mAnimation.writeToProto(proto, ANIMATION_ADAPTER);
        }
        if (mLeash != null) {
            mLeash.writeToProto(proto, LEASH);
        }
        proto.write(ANIMATION_START_DELAYED, mAnimationStartDelayed);
        proto.end(token);
    }

    void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mLeash="); pw.print(mLeash);
        if (mAnimationStartDelayed) {
            pw.print(" mAnimationStartDelayed="); pw.println(mAnimationStartDelayed);
        } else {
            pw.println();
        }
        pw.print(prefix); pw.println("Animation:");
        if (mAnimation != null) {
            mAnimation.dump(pw, prefix + "  ");
        } else {
            pw.print(prefix); pw.println("null");
        }
    }

    /**
     * Callback to be passed into {@link AnimationAdapter#startAnimation} to be invoked by the
     * component that is running the animation when the animation is finished.
     */
    interface OnAnimationFinishedCallback {
        void onAnimationFinished(AnimationAdapter anim);
    }

    /**
     * Interface to be animated by {@link SurfaceAnimator}.
     */
    interface Animatable {

        /**
         * @return The pending transaction that will be committed in the next frame.
         */
        @NonNull Transaction getPendingTransaction();

        /**
         * Schedules a commit of the pending transaction.
         */
        void commitPendingTransaction();

        /**
         * Called when the was created.
         *
         * @param t The transaction to use to apply any necessary changes.
         * @param leash The leash that was created.
         */
        void onAnimationLeashCreated(Transaction t, SurfaceControl leash);

        /**
         * Called when the leash is being destroyed, and the surface was reparented back to the
         * original parent.
         *
         * @param t The transaction to use to apply any necessary changes.
         */
        void onAnimationLeashDestroyed(Transaction t);

        /**
         * @return A new surface to be used for the animation leash, inserted at the correct
         *         position in the hierarchy.
         */
        SurfaceControl.Builder makeAnimationLeash();

        /**
         * @return The parent that should be used for the animation leash.
         */
        @Nullable SurfaceControl getAnimationLeashParent();

        /**
         * @return The surface of the object to be animated.
         */
        @Nullable SurfaceControl getSurfaceControl();

        /**
         * @return The parent of the surface object to be animated.
         */
        @Nullable SurfaceControl getParentSurfaceControl();

        /**
         * @return The width of the surface to be animated.
         */
        int getSurfaceWidth();

        /**
         * @return The height of the surface to be animated.
         */
        int getSurfaceHeight();

        /**
         * Gets called when the animation is about to finish and gives the client the opportunity to
         * defer finishing the animation, i.e. it keeps the leash around until the client calls
         * {@link #cancelAnimation}.
         *
         * @param endDeferFinishCallback The callback to call when defer finishing should be ended.
         * @return Whether the client would like to defer the animation finish.
         */
        default boolean shouldDeferAnimationFinish(Runnable endDeferFinishCallback) {
            return false;
        }
    }
}
