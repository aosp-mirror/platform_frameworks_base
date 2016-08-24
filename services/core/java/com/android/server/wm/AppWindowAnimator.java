/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ANIM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_LAYERS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.SHOW_TRANSACTIONS;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.TYPE_LAYER_OFFSET;
import static com.android.server.wm.WindowStateAnimator.STACK_CLIP_BEFORE_ANIM;

import android.graphics.Matrix;
import android.util.Slog;
import android.util.TimeUtils;
import android.view.Choreographer;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.WindowManagerPolicy;
import android.view.animation.Animation;
import android.view.animation.Transformation;

import java.io.PrintWriter;
import java.util.ArrayList;

public class AppWindowAnimator {
    static final String TAG = TAG_WITH_CLASS_NAME ? "AppWindowAnimator" : TAG_WM;

    private static final int PROLONG_ANIMATION_DISABLED = 0;
    static final int PROLONG_ANIMATION_AT_END = 1;
    static final int PROLONG_ANIMATION_AT_START = 2;

    final AppWindowToken mAppToken;
    final WindowManagerService mService;
    final WindowAnimator mAnimator;

    boolean animating;
    boolean wasAnimating;
    Animation animation;
    boolean hasTransformation;
    final Transformation transformation = new Transformation();

    // Have we been asked to have this token keep the screen frozen?
    // Protect with mAnimator.
    boolean freezingScreen;

    /**
     * How long we last kept the screen frozen.
     */
    int lastFreezeDuration;

    // Offset to the window of all layers in the token, for use by
    // AppWindowToken animations.
    int animLayerAdjustment;

    // Propagated from AppWindowToken.allDrawn, to determine when
    // the state changes.
    boolean allDrawn;

    // Special surface for thumbnail animation.  If deferThumbnailDestruction is enabled, then we
    // will make sure that the thumbnail is destroyed after the other surface is completed.  This
    // requires that the duration of the two animations are the same.
    SurfaceControl thumbnail;
    int thumbnailTransactionSeq;
    int thumbnailLayer;
    int thumbnailForceAboveLayer;
    Animation thumbnailAnimation;
    final Transformation thumbnailTransformation = new Transformation();
    // This flag indicates that the destruction of the thumbnail surface is synchronized with
    // another animation, so defer the destruction of this thumbnail surface for a single frame
    // after the secondary animation completes.
    boolean deferThumbnailDestruction;
    // This flag is set if the animator has deferThumbnailDestruction set and has reached the final
    // frame of animation.  It will extend the animation by one frame and then clean up afterwards.
    boolean deferFinalFrameCleanup;
    // If true when the animation hits the last frame, it will keep running on that last frame.
    // This is used to synchronize animation with Recents and we wait for Recents to tell us to
    // finish or for a new animation be set as fail-safe mechanism.
    private int mProlongAnimation;
    // Whether the prolong animation can be removed when animation is set. The purpose of this is
    // that if recents doesn't tell us to remove the prolonged animation, we will get rid of it
    // when new animation is set.
    private boolean mClearProlongedAnimation;

    /** WindowStateAnimator from mAppAnimator.allAppWindows as of last performLayout */
    ArrayList<WindowStateAnimator> mAllAppWinAnimators = new ArrayList<>();

    /** True if the current animation was transferred from another AppWindowAnimator.
     *  See {@link #transferCurrentAnimation}*/
    boolean usingTransferredAnimation = false;

    private boolean mSkipFirstFrame = false;
    private int mStackClip = STACK_CLIP_BEFORE_ANIM;

    static final Animation sDummyAnimation = new DummyAnimation();

    public AppWindowAnimator(final AppWindowToken atoken) {
        mAppToken = atoken;
        mService = atoken.service;
        mAnimator = mService.mAnimator;
    }

    public void setAnimation(Animation anim, int width, int height, boolean skipFirstFrame,
            int stackClip) {
        if (WindowManagerService.localLOGV) Slog.v(TAG, "Setting animation in " + mAppToken
                + ": " + anim + " wxh=" + width + "x" + height
                + " isVisible=" + mAppToken.isVisible());
        animation = anim;
        animating = false;
        if (!anim.isInitialized()) {
            anim.initialize(width, height, width, height);
        }
        anim.restrictDuration(WindowManagerService.MAX_ANIMATION_DURATION);
        anim.scaleCurrentDuration(mService.getTransitionAnimationScaleLocked());
        int zorder = anim.getZAdjustment();
        int adj = 0;
        if (zorder == Animation.ZORDER_TOP) {
            adj = TYPE_LAYER_OFFSET;
        } else if (zorder == Animation.ZORDER_BOTTOM) {
            adj = -TYPE_LAYER_OFFSET;
        }

        if (animLayerAdjustment != adj) {
            animLayerAdjustment = adj;
            updateLayers();
        }
        // Start out animation gone if window is gone, or visible if window is visible.
        transformation.clear();
        transformation.setAlpha(mAppToken.isVisible() ? 1 : 0);
        hasTransformation = true;
        mStackClip = stackClip;

        this.mSkipFirstFrame = skipFirstFrame;

        if (!mAppToken.appFullscreen) {
            anim.setBackgroundColor(0);
        }
        if (mClearProlongedAnimation) {
            mProlongAnimation = PROLONG_ANIMATION_DISABLED;
        } else {
            mClearProlongedAnimation = true;
        }

        // Since we are finally starting our animation, we don't need the logic anymore to prevent
        // the app from showing again if we just moved between stacks. See
        // {@link WindowState#notifyMovedInStack}.
        for (int i = mAppToken.allAppWindows.size() - 1; i >= 0; i--) {
            mAppToken.allAppWindows.get(i).resetJustMovedInStack();
        }
    }

    public void setDummyAnimation() {
        if (WindowManagerService.localLOGV) Slog.v(TAG, "Setting dummy animation in " + mAppToken
                + " isVisible=" + mAppToken.isVisible());
        animation = sDummyAnimation;
        hasTransformation = true;
        transformation.clear();
        transformation.setAlpha(mAppToken.isVisible() ? 1 : 0);
    }

    void setNullAnimation() {
        animation = null;
        usingTransferredAnimation = false;
    }

    public void clearAnimation() {
        if (animation != null) {
            animating = true;
        }
        clearThumbnail();
        setNullAnimation();
        if (mAppToken.deferClearAllDrawn) {
            mAppToken.clearAllDrawn();
        }
        mStackClip = STACK_CLIP_BEFORE_ANIM;
    }

    public boolean isAnimating() {
        return animation != null || mAppToken.inPendingTransaction;
    }

    public void clearThumbnail() {
        if (thumbnail != null) {
            thumbnail.hide();
            mService.mWindowPlacerLocked.destroyAfterTransaction(thumbnail);
            thumbnail = null;
        }
        deferThumbnailDestruction = false;
    }

    int getStackClip() {
        return mStackClip;
    }

    void transferCurrentAnimation(
            AppWindowAnimator toAppAnimator, WindowStateAnimator transferWinAnimator) {

        if (animation != null) {
            toAppAnimator.animation = animation;
            toAppAnimator.animating = animating;
            toAppAnimator.animLayerAdjustment = animLayerAdjustment;
            setNullAnimation();
            animLayerAdjustment = 0;
            toAppAnimator.updateLayers();
            updateLayers();
            toAppAnimator.usingTransferredAnimation = true;
        }
        if (transferWinAnimator != null) {
            mAllAppWinAnimators.remove(transferWinAnimator);
            toAppAnimator.mAllAppWinAnimators.add(transferWinAnimator);
            toAppAnimator.hasTransformation = transferWinAnimator.mAppAnimator.hasTransformation;
            if (toAppAnimator.hasTransformation) {
                toAppAnimator.transformation.set(transferWinAnimator.mAppAnimator.transformation);
            } else {
                toAppAnimator.transformation.clear();
            }
            transferWinAnimator.mAppAnimator = toAppAnimator;
        }
    }

    void updateLayers() {
        final int windowCount = mAppToken.allAppWindows.size();
        final int adj = animLayerAdjustment;
        thumbnailLayer = -1;
        final WallpaperController wallpaperController = mService.mWallpaperControllerLocked;
        for (int i = 0; i < windowCount; i++) {
            final WindowState w = mAppToken.allAppWindows.get(i);
            final WindowStateAnimator winAnimator = w.mWinAnimator;
            winAnimator.mAnimLayer = w.mLayer + adj;
            if (winAnimator.mAnimLayer > thumbnailLayer) {
                thumbnailLayer = winAnimator.mAnimLayer;
            }
            if (DEBUG_LAYERS) Slog.v(TAG, "Updating layer " + w + ": " + winAnimator.mAnimLayer);
            if (w == mService.mInputMethodTarget && !mService.mInputMethodTargetWaitingAnim) {
                mService.mLayersController.setInputMethodAnimLayerAdjustment(adj);
            }
            wallpaperController.setAnimLayerAdjustment(w, adj);
        }
    }

    private void stepThumbnailAnimation(long currentTime) {
        thumbnailTransformation.clear();
        final long animationFrameTime = getAnimationFrameTime(thumbnailAnimation, currentTime);
        thumbnailAnimation.getTransformation(animationFrameTime, thumbnailTransformation);

        ScreenRotationAnimation screenRotationAnimation =
                mAnimator.getScreenRotationAnimationLocked(Display.DEFAULT_DISPLAY);
        final boolean screenAnimation = screenRotationAnimation != null
                && screenRotationAnimation.isAnimating();
        if (screenAnimation) {
            thumbnailTransformation.postCompose(screenRotationAnimation.getEnterTransformation());
        }
        // cache often used attributes locally
        final float tmpFloats[] = mService.mTmpFloats;
        thumbnailTransformation.getMatrix().getValues(tmpFloats);
        if (SHOW_TRANSACTIONS) WindowManagerService.logSurface(thumbnail,
                "thumbnail", "POS " + tmpFloats[Matrix.MTRANS_X]
                + ", " + tmpFloats[Matrix.MTRANS_Y]);
        thumbnail.setPosition(tmpFloats[Matrix.MTRANS_X], tmpFloats[Matrix.MTRANS_Y]);
        if (SHOW_TRANSACTIONS) WindowManagerService.logSurface(thumbnail,
                "thumbnail", "alpha=" + thumbnailTransformation.getAlpha()
                + " layer=" + thumbnailLayer
                + " matrix=[" + tmpFloats[Matrix.MSCALE_X]
                + "," + tmpFloats[Matrix.MSKEW_Y]
                + "][" + tmpFloats[Matrix.MSKEW_X]
                + "," + tmpFloats[Matrix.MSCALE_Y] + "]");
        thumbnail.setAlpha(thumbnailTransformation.getAlpha());
        if (thumbnailForceAboveLayer > 0) {
            thumbnail.setLayer(thumbnailForceAboveLayer + 1);
        } else {
            // The thumbnail is layered below the window immediately above this
            // token's anim layer.
            thumbnail.setLayer(thumbnailLayer + WindowManagerService.WINDOW_LAYER_MULTIPLIER
                    - WindowManagerService.LAYER_OFFSET_THUMBNAIL);
        }
        thumbnail.setMatrix(tmpFloats[Matrix.MSCALE_X], tmpFloats[Matrix.MSKEW_Y],
                tmpFloats[Matrix.MSKEW_X], tmpFloats[Matrix.MSCALE_Y]);
        thumbnail.setWindowCrop(thumbnailTransformation.getClipRect());
    }

    /**
     * Sometimes we need to synchronize the first frame of animation with some external event, e.g.
     * Recents hiding some of its content. To achieve this, we prolong the start of the animaiton
     * and keep producing the first frame of the animation.
     */
    private long getAnimationFrameTime(Animation animation, long currentTime) {
        if (mProlongAnimation == PROLONG_ANIMATION_AT_START) {
            animation.setStartTime(currentTime);
            return currentTime + 1;
        }
        return currentTime;
    }

    private boolean stepAnimation(long currentTime) {
        if (animation == null) {
            return false;
        }
        transformation.clear();
        final long animationFrameTime = getAnimationFrameTime(animation, currentTime);
        boolean hasMoreFrames = animation.getTransformation(animationFrameTime, transformation);
        if (!hasMoreFrames) {
            if (deferThumbnailDestruction && !deferFinalFrameCleanup) {
                // We are deferring the thumbnail destruction, so extend the animation for one more
                // (dummy) frame before we clean up
                deferFinalFrameCleanup = true;
                hasMoreFrames = true;
            } else {
                if (false && DEBUG_ANIM) Slog.v(TAG,
                        "Stepped animation in " + mAppToken + ": more=" + hasMoreFrames +
                        ", xform=" + transformation + ", mProlongAnimation=" + mProlongAnimation);
                deferFinalFrameCleanup = false;
                if (mProlongAnimation == PROLONG_ANIMATION_AT_END) {
                    hasMoreFrames = true;
                } else {
                    setNullAnimation();
                    clearThumbnail();
                    if (DEBUG_ANIM) Slog.v(TAG, "Finished animation in " + mAppToken + " @ "
                            + currentTime);
                }
            }
        }
        hasTransformation = hasMoreFrames;
        return hasMoreFrames;
    }

    private long getStartTimeCorrection() {
        if (mSkipFirstFrame) {

            // If the transition is an animation in which the first frame doesn't change the screen
            // contents at all, we can just skip it and start at the second frame. So we shift the
            // start time of the animation forward by minus the frame duration.
            return -Choreographer.getInstance().getFrameIntervalNanos() / TimeUtils.NANOS_PER_MS;
        } else {
            return 0;
        }
    }

    // This must be called while inside a transaction.
    boolean stepAnimationLocked(long currentTime, final int displayId) {
        if (mService.okToDisplay()) {
            // We will run animations as long as the display isn't frozen.

            if (animation == sDummyAnimation) {
                // This guy is going to animate, but not yet.  For now count
                // it as not animating for purposes of scheduling transactions;
                // when it is really time to animate, this will be set to
                // a real animation and the next call will execute normally.
                return false;
            }

            if ((mAppToken.allDrawn || animating || mAppToken.startingDisplayed)
                    && animation != null) {
                if (!animating) {
                    if (DEBUG_ANIM) Slog.v(TAG,
                        "Starting animation in " + mAppToken +
                        " @ " + currentTime + " scale="
                        + mService.getTransitionAnimationScaleLocked()
                        + " allDrawn=" + mAppToken.allDrawn + " animating=" + animating);
                    long correction = getStartTimeCorrection();
                    animation.setStartTime(currentTime + correction);
                    animating = true;
                    if (thumbnail != null) {
                        thumbnail.show();
                        thumbnailAnimation.setStartTime(currentTime + correction);
                    }
                    mSkipFirstFrame = false;
                }
                if (stepAnimation(currentTime)) {
                    // animation isn't over, step any thumbnail and that's
                    // it for now.
                    if (thumbnail != null) {
                        stepThumbnailAnimation(currentTime);
                    }
                    return true;
                }
            }
        } else if (animation != null) {
            // If the display is frozen, and there is a pending animation,
            // clear it and make sure we run the cleanup code.
            animating = true;
            animation = null;
        }

        hasTransformation = false;

        if (!animating && animation == null) {
            return false;
        }

        mAnimator.setAppLayoutChanges(this, WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM,
                "AppWindowToken", displayId);

        clearAnimation();
        animating = false;
        if (animLayerAdjustment != 0) {
            animLayerAdjustment = 0;
            updateLayers();
        }
        if (mService.mInputMethodTarget != null
                && mService.mInputMethodTarget.mAppToken == mAppToken) {
            mService.moveInputMethodWindowsIfNeededLocked(true);
        }

        if (DEBUG_ANIM) Slog.v(TAG,
                "Animation done in " + mAppToken
                + ": reportedVisible=" + mAppToken.reportedVisible);

        transformation.clear();

        final int numAllAppWinAnimators = mAllAppWinAnimators.size();
        for (int i = 0; i < numAllAppWinAnimators; i++) {
            mAllAppWinAnimators.get(i).finishExit();
        }
        mService.mAppTransition.notifyAppTransitionFinishedLocked(mAppToken.token);
        return false;
    }

    // This must be called while inside a transaction.
    boolean showAllWindowsLocked() {
        boolean isAnimating = false;
        final int NW = mAllAppWinAnimators.size();
        for (int i=0; i<NW; i++) {
            WindowStateAnimator winAnimator = mAllAppWinAnimators.get(i);
            if (DEBUG_VISIBILITY) Slog.v(TAG, "performing show on: " + winAnimator);
            winAnimator.performShowLocked();
            isAnimating |= winAnimator.isAnimationSet();
        }
        return isAnimating;
    }

    void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        pw.print(prefix); pw.print("mAppToken="); pw.println(mAppToken);
        pw.print(prefix); pw.print("mAnimator="); pw.println(mAnimator);
        pw.print(prefix); pw.print("freezingScreen="); pw.print(freezingScreen);
                pw.print(" allDrawn="); pw.print(allDrawn);
                pw.print(" animLayerAdjustment="); pw.println(animLayerAdjustment);
        if (lastFreezeDuration != 0) {
            pw.print(prefix); pw.print("lastFreezeDuration=");
                    TimeUtils.formatDuration(lastFreezeDuration, pw); pw.println();
        }
        if (animating || animation != null) {
            pw.print(prefix); pw.print("animating="); pw.println(animating);
            pw.print(prefix); pw.print("animation="); pw.println(animation);
        }
        if (hasTransformation) {
            pw.print(prefix); pw.print("XForm: ");
                    transformation.printShortString(pw);
                    pw.println();
        }
        if (thumbnail != null) {
            pw.print(prefix); pw.print("thumbnail="); pw.print(thumbnail);
                    pw.print(" layer="); pw.println(thumbnailLayer);
            pw.print(prefix); pw.print("thumbnailAnimation="); pw.println(thumbnailAnimation);
            pw.print(prefix); pw.print("thumbnailTransformation=");
                    pw.println(thumbnailTransformation.toShortString());
        }
        for (int i=0; i<mAllAppWinAnimators.size(); i++) {
            WindowStateAnimator wanim = mAllAppWinAnimators.get(i);
            pw.print(prefix); pw.print("App Win Anim #"); pw.print(i);
                    pw.print(": "); pw.println(wanim);
        }
    }

    void startProlongAnimation(int prolongType) {
        mProlongAnimation = prolongType;
        mClearProlongedAnimation = false;
    }

    void endProlongedAnimation() {
        mProlongAnimation = PROLONG_ANIMATION_DISABLED;
    }

    // This is an animation that does nothing: it just immediately finishes
    // itself every time it is called.  It is used as a stub animation in cases
    // where we want to synchronize multiple things that may be animating.
    static final class DummyAnimation extends Animation {
        @Override
        public boolean getTransformation(long currentTime, Transformation outTransformation) {
            return false;
        }
    }

}
