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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.function.Supplier;

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
    private @AnimationType int mAnimationType;

    @VisibleForTesting
    SurfaceControl mLeash;
    @VisibleForTesting
    final Animatable mAnimatable;
    private final OnAnimationFinishedCallback mInnerAnimationFinishedCallback;
    @VisibleForTesting
    @Nullable
    final OnAnimationFinishedCallback mStaticAnimationFinishedCallback;
    @Nullable
    private OnAnimationFinishedCallback mAnimationFinishedCallback;
    private boolean mAnimationStartDelayed;

    /**
     * @param animatable The object to animate.
     * @param staticAnimationFinishedCallback Callback to invoke when an animation has finished
     *                                         running.
     */
    SurfaceAnimator(Animatable animatable,
            @Nullable OnAnimationFinishedCallback staticAnimationFinishedCallback,
            WindowManagerService service) {
        mAnimatable = animatable;
        mService = service;
        mStaticAnimationFinishedCallback = staticAnimationFinishedCallback;
        mInnerAnimationFinishedCallback = getFinishedCallback(staticAnimationFinishedCallback);
    }

    private OnAnimationFinishedCallback getFinishedCallback(
            @Nullable OnAnimationFinishedCallback staticAnimationFinishedCallback) {
        return (type, anim) -> {
            synchronized (mService.mGlobalLock) {
                final SurfaceAnimator target = mService.mAnimationTransferMap.remove(anim);
                if (target != null) {
                    target.mInnerAnimationFinishedCallback.onAnimationFinished(type, anim);
                    return;
                }

                if (anim != mAnimation) {
                    return;
                }
                final Runnable resetAndInvokeFinish = () -> {
                    // We need to check again if the animation has been replaced with a new
                    // animation because the animatable may defer to finish.
                    if (anim != mAnimation) {
                        return;
                    }
                    final OnAnimationFinishedCallback animationFinishCallback =
                            mAnimationFinishedCallback;
                    reset(mAnimatable.getPendingTransaction(), true /* destroyLeash */);
                    if (staticAnimationFinishedCallback != null) {
                        staticAnimationFinishedCallback.onAnimationFinished(type, anim);
                    }
                    if (animationFinishCallback != null) {
                        animationFinishCallback.onAnimationFinished(type, anim);
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
     * @param animationFinishedCallback The callback being triggered when the animation finishes.
     */
    void startAnimation(Transaction t, AnimationAdapter anim, boolean hidden,
            @AnimationType int type,
            @Nullable OnAnimationFinishedCallback animationFinishedCallback,
            @Nullable SurfaceFreezer freezer) {
        cancelAnimation(t, true /* restarting */, true /* forwardCancel */);
        mAnimation = anim;
        mAnimationType = type;
        mAnimationFinishedCallback = animationFinishedCallback;
        final SurfaceControl surface = mAnimatable.getSurfaceControl();
        if (surface == null) {
            Slog.w(TAG, "Unable to start animation, surface is null or no children.");
            cancelAnimation();
            return;
        }
        mLeash = freezer != null ? freezer.takeLeashForAnimation() : null;
        if (mLeash == null) {
            mLeash = createAnimationLeash(mAnimatable, surface, t, type,
                    mAnimatable.getSurfaceWidth(), mAnimatable.getSurfaceHeight(), 0 /* x */,
                    0 /* y */, hidden, mService.mTransactionFactory);
            mAnimatable.onAnimationLeashCreated(t, mLeash);
        }
        mAnimatable.onLeashAnimationStarting(t, mLeash);
        if (mAnimationStartDelayed) {
            if (DEBUG_ANIM) Slog.i(TAG, "Animation start delayed");
            return;
        }
        mAnimation.startAnimation(mLeash, t, type, mInnerAnimationFinishedCallback);
    }

    void startAnimation(Transaction t, AnimationAdapter anim, boolean hidden,
            @AnimationType int type,
            @Nullable OnAnimationFinishedCallback animationFinishedCallback) {
        startAnimation(t, anim, hidden, type, animationFinishedCallback, null /* freezer */);
    }

    void startAnimation(Transaction t, AnimationAdapter anim, boolean hidden,
            @AnimationType int type) {
        startAnimation(t, anim, hidden, type, null /* animationFinishedCallback */);
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
                    mAnimationType, mInnerAnimationFinishedCallback);
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

    @AnimationType
    int getAnimationType() {
        return mAnimationType;
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
        mAnimationType = from.mAnimationType;
        mAnimationFinishedCallback = from.mAnimationFinishedCallback;

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
        final @AnimationType int animationType = mAnimationType;
        final OnAnimationFinishedCallback animationFinishedCallback = mAnimationFinishedCallback;
        reset(t, false);
        if (animation != null) {
            if (!mAnimationStartDelayed && forwardCancel) {
                animation.onAnimationCancelled(leash);
            }
            if (!restarting) {
                if (mStaticAnimationFinishedCallback != null) {
                    mStaticAnimationFinishedCallback.onAnimationFinished(animationType, animation);
                }
                if (animationFinishedCallback != null) {
                    animationFinishedCallback.onAnimationFinished(animationType, animation);
                }
            }
        }

        if (forwardCancel && leash != null) {
            t.remove(leash);
            mService.scheduleAnimationLocked();
        }

        if (!restarting) {
            mAnimationStartDelayed = false;
        }
    }

    private void reset(Transaction t, boolean destroyLeash) {
        mService.mAnimationTransferMap.remove(mAnimation);
        mAnimation = null;
        mAnimationFinishedCallback = null;
        mAnimationType = ANIMATION_TYPE_NONE;
        if (mLeash == null) {
            return;
        }
        SurfaceControl leash = mLeash;
        mLeash = null;
        final boolean scheduleAnim = removeLeash(t, mAnimatable, leash, destroyLeash);
        if (scheduleAnim) {
            mService.scheduleAnimationLocked();
        }
    }

    static boolean removeLeash(Transaction t, Animatable animatable, @NonNull SurfaceControl leash,
            boolean destroy) {
        boolean scheduleAnim = false;
        final SurfaceControl surface = animatable.getSurfaceControl();
        final SurfaceControl parent = animatable.getParentSurfaceControl();

        // If the surface was destroyed or the leash is invalid, we don't care to reparent it back.
        // Note that we also set this variable to true even if the parent isn't valid anymore, in
        // order to ensure onAnimationLeashLost still gets called in this case.
        final boolean reparent = surface != null;
        if (reparent) {
            if (DEBUG_ANIM) Slog.i(TAG, "Reparenting to original parent: " + parent);
            // We shouldn't really need these isValid checks but we do
            // b/130364451
            if (surface.isValid() && parent != null && parent.isValid()) {
                t.reparent(surface, parent);
                scheduleAnim = true;
            }
        }
        if (destroy) {
            t.remove(leash);
            scheduleAnim = true;
        }

        if (reparent) {
            // Make sure to inform the animatable after the surface was reparented (or reparent
            // wasn't possible, but we still need to invoke the callback)
            animatable.onAnimationLeashLost(t);
            scheduleAnim = true;
        }
        return scheduleAnim;
    }

    static SurfaceControl createAnimationLeash(Animatable animatable, SurfaceControl surface,
            Transaction t, @AnimationType int type, int width, int height, int x, int y,
            boolean hidden, Supplier<Transaction> transactionFactory) {
        if (DEBUG_ANIM) Slog.i(TAG, "Reparenting to leash");
        final SurfaceControl.Builder builder = animatable.makeAnimationLeash()
                .setParent(animatable.getAnimationLeashParent())
                .setName(surface + " - animation-leash")
                .setColorLayer();
        final SurfaceControl leash = builder.build();
        if (!hidden) {
            // TODO(b/151665759) Defer reparent calls
            // We want the leash to be visible immediately but we want to set the effects on
            // the layer. Since the transaction used in this function may be deferred, we apply
            // another transaction immediately with the correct visibility and effects.
            // If this doesn't work, you will can see the 2/3 button nav bar flicker during
            // seamless rotation.
            transactionFactory.get().unsetColor(leash).show(leash).apply();
        }
        t.unsetColor(leash);
        t.setWindowCrop(leash, width, height);
        t.setPosition(leash, x, y);
        t.show(leash);
        t.setAlpha(leash, hidden ? 0 : 1);

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
    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        if (mAnimation != null) {
            mAnimation.dumpDebug(proto, ANIMATION_ADAPTER);
        }
        if (mLeash != null) {
            mLeash.dumpDebug(proto, LEASH);
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
     * No animation is specified.
     * @hide
     */
    static final int ANIMATION_TYPE_NONE = 0;

    /**
     * Animation for an app transition.
     * @hide
     */
    static final int ANIMATION_TYPE_APP_TRANSITION = 1;

    /**
     * Animation for screen rotation.
     * @hide
     */
    static final int ANIMATION_TYPE_SCREEN_ROTATION = 1 << 1;

    /**
     * Animation for dimming.
     * @hide
     */
    static final int ANIMATION_TYPE_DIMMER = 1 << 2;

    /**
     * Animation for recent apps.
     * @hide
     */
    static final int ANIMATION_TYPE_RECENTS = 1 << 3;

    /**
     * Animation for a {@link WindowState} without animating the activity.
     * @hide
     */
    static final int ANIMATION_TYPE_WINDOW_ANIMATION = 1 << 4;

    /**
     * Animation to control insets. This is actually not an animation, but is used to give the
     * client a leash over the system window causing insets.
     * @hide
     */
    static final int ANIMATION_TYPE_INSETS_CONTROL = 1 << 5;

    /**
     * Bitmask to include all animation types. This is NOT an {@link AnimationType}
     * @hide
     */
    static final int ANIMATION_TYPE_ALL = -1;

    /**
     * The type of the animation.
     * @hide
     */
    @IntDef(flag = true, prefix = { "ANIMATION_TYPE_" }, value = {
            ANIMATION_TYPE_NONE,
            ANIMATION_TYPE_APP_TRANSITION,
            ANIMATION_TYPE_SCREEN_ROTATION,
            ANIMATION_TYPE_DIMMER,
            ANIMATION_TYPE_RECENTS,
            ANIMATION_TYPE_WINDOW_ANIMATION,
            ANIMATION_TYPE_INSETS_CONTROL
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface AnimationType {}

    /**
     * Callback to be passed into {@link AnimationAdapter#startAnimation} to be invoked by the
     * component that is running the animation when the animation is finished.
     */
    interface OnAnimationFinishedCallback {
        void onAnimationFinished(@AnimationType int type, AnimationAdapter anim);
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
         * Called when the animation leash is created. Note that this is also called by
         * {@link SurfaceFreezer}, so this doesn't mean we're about to start animating.
         *
         * @param t The transaction to use to apply any necessary changes.
         * @param leash The leash that was created.
         */
        void onAnimationLeashCreated(Transaction t, SurfaceControl leash);

        /**
         * Called when the animator is about to start animating the leash.
         *
         * @param t The transaction to use to apply any necessary changes.
         * @param leash The leash that was created.
         */
        default void onLeashAnimationStarting(Transaction t, SurfaceControl leash) { }

        /**
         * Called when the leash is being destroyed, or when the leash is being transferred to
         * another SurfaceAnimator.
         *
         * @param t The transaction to use to apply any necessary changes.
         */
        void onAnimationLeashLost(Transaction t);

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
         *         This SurfaceControl must be valid if non-null.
         */
        @Nullable SurfaceControl getSurfaceControl();

        /**
         * @return The parent of the surface object to be animated.
         *         This SurfaceControl must be valid if non-null.
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
