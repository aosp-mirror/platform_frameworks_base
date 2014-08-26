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

import android.graphics.Matrix;
import android.os.RemoteException;
import android.util.Slog;
import android.util.TimeUtils;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.WindowManagerPolicy;
import android.view.animation.Animation;
import android.view.animation.Transformation;

import java.io.PrintWriter;
import java.util.ArrayList;

public class AppWindowAnimator {
    static final String TAG = "AppWindowAnimator";

    final AppWindowToken mAppToken;
    final WindowManagerService mService;
    final WindowAnimator mAnimator;

    boolean animating;
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

    // Special surface for thumbnail animation.
    SurfaceControl thumbnail;
    int thumbnailTransactionSeq;
    int thumbnailX;
    int thumbnailY;
    int thumbnailLayer;
    int thumbnailForceAboveLayer;
    Animation thumbnailAnimation;
    final Transformation thumbnailTransformation = new Transformation();
    // This flag indicates that the destruction of the thumbnail surface is synchronized with
    // another animation, so do not pre-emptively destroy the thumbnail surface when the animation
    // completes
    boolean deferThumbnailDestruction;
    // This is the thumbnail surface that has been bestowed upon this animator, and when the
    // surface for this animator's animation is complete, we will destroy the thumbnail surface
    // as well.  Do not animate or do anything with this surface.
    SurfaceControl deferredThumbnail;

    /** WindowStateAnimator from mAppAnimator.allAppWindows as of last performLayout */
    ArrayList<WindowStateAnimator> mAllAppWinAnimators = new ArrayList<WindowStateAnimator>();

    static final Animation sDummyAnimation = new DummyAnimation();

    public AppWindowAnimator(final AppWindowToken atoken) {
        mAppToken = atoken;
        mService = atoken.service;
        mAnimator = atoken.mAnimator;
    }

    public void setAnimation(Animation anim, int width, int height) {
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
            adj = WindowManagerService.TYPE_LAYER_OFFSET;
        } else if (zorder == Animation.ZORDER_BOTTOM) {
            adj = -WindowManagerService.TYPE_LAYER_OFFSET;
        }

        if (animLayerAdjustment != adj) {
            animLayerAdjustment = adj;
            updateLayers();
        }
        // Start out animation gone if window is gone, or visible if window is visible.
        transformation.clear();
        transformation.setAlpha(mAppToken.isVisible() ? 1 : 0);
        hasTransformation = true;
    }

    public void setDummyAnimation() {
        if (WindowManagerService.localLOGV) Slog.v(TAG, "Setting dummy animation in " + mAppToken
                + " isVisible=" + mAppToken.isVisible());
        animation = sDummyAnimation;
        hasTransformation = true;
        transformation.clear();
        transformation.setAlpha(mAppToken.isVisible() ? 1 : 0);
    }

    public void clearAnimation() {
        if (animation != null) {
            animation = null;
            animating = true;
        }
        if (!deferThumbnailDestruction) {
            clearThumbnail();
        }
        if (mAppToken.deferClearAllDrawn) {
            mAppToken.allDrawn = false;
            mAppToken.deferClearAllDrawn = false;
        }
    }

    public void clearThumbnail() {
        if (thumbnail != null) {
            thumbnail.destroy();
            thumbnail = null;
        }
    }

    public void clearDeferredThumbnail() {
        if (deferredThumbnail != null) {
            deferredThumbnail.destroy();
            deferredThumbnail = null;
        }
    }

    void updateLayers() {
        final int N = mAppToken.allAppWindows.size();
        final int adj = animLayerAdjustment;
        thumbnailLayer = -1;
        for (int i=0; i<N; i++) {
            final WindowState w = mAppToken.allAppWindows.get(i);
            final WindowStateAnimator winAnimator = w.mWinAnimator;
            winAnimator.mAnimLayer = w.mLayer + adj;
            if (winAnimator.mAnimLayer > thumbnailLayer) {
                thumbnailLayer = winAnimator.mAnimLayer;
            }
            if (WindowManagerService.DEBUG_LAYERS) Slog.v(TAG, "Updating layer " + w + ": "
                    + winAnimator.mAnimLayer);
            if (w == mService.mInputMethodTarget && !mService.mInputMethodTargetWaitingAnim) {
                mService.setInputMethodAnimLayerAdjustment(adj);
            }
            if (w == mService.mWallpaperTarget && mService.mLowerWallpaperTarget == null) {
                mService.setWallpaperAnimLayerAdjustmentLocked(adj);
            }
        }
    }

    private void stepThumbnailAnimation(long currentTime) {
        thumbnailTransformation.clear();
        thumbnailAnimation.getTransformation(currentTime, thumbnailTransformation);
        thumbnailTransformation.getMatrix().preTranslate(thumbnailX, thumbnailY);

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
        if (WindowManagerService.SHOW_TRANSACTIONS) WindowManagerService.logSurface(thumbnail,
                "thumbnail", "POS " + tmpFloats[Matrix.MTRANS_X]
                + ", " + tmpFloats[Matrix.MTRANS_Y], null);
        thumbnail.setPosition(tmpFloats[Matrix.MTRANS_X], tmpFloats[Matrix.MTRANS_Y]);
        if (WindowManagerService.SHOW_TRANSACTIONS) WindowManagerService.logSurface(thumbnail,
                "thumbnail", "alpha=" + thumbnailTransformation.getAlpha()
                + " layer=" + thumbnailLayer
                + " matrix=[" + tmpFloats[Matrix.MSCALE_X]
                + "," + tmpFloats[Matrix.MSKEW_Y]
                + "][" + tmpFloats[Matrix.MSKEW_X]
                + "," + tmpFloats[Matrix.MSCALE_Y] + "]", null);
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
    }

    private boolean stepAnimation(long currentTime) {
        if (animation == null) {
            return false;
        }
        transformation.clear();
        final boolean more = animation.getTransformation(currentTime, transformation);
        if (false && WindowManagerService.DEBUG_ANIM) Slog.v(
            TAG, "Stepped animation in " + mAppToken + ": more=" + more + ", xform=" + transformation);
        if (!more) {
            animation = null;
            if (!deferThumbnailDestruction) {
                clearThumbnail();
            }
            if (WindowManagerService.DEBUG_ANIM) Slog.v(
                TAG, "Finished animation in " + mAppToken + " @ " + currentTime);
        }
        hasTransformation = more;
        return more;
    }

    // This must be called while inside a transaction.
    boolean stepAnimationLocked(long currentTime) {
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
                    if (WindowManagerService.DEBUG_ANIM) Slog.v(
                        TAG, "Starting animation in " + mAppToken +
                        " @ " + currentTime + " scale="
                        + mService.getTransitionAnimationScaleLocked()
                        + " allDrawn=" + mAppToken.allDrawn + " animating=" + animating);
                    animation.setStartTime(currentTime);
                    animating = true;
                    if (thumbnail != null) {
                        thumbnail.show();
                        thumbnailAnimation.setStartTime(currentTime);
                    }
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
                "AppWindowToken");

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

        if (WindowManagerService.DEBUG_ANIM) Slog.v(
                TAG, "Animation done in " + mAppToken
                + ": reportedVisible=" + mAppToken.reportedVisible);

        transformation.clear();

        final int N = mAllAppWinAnimators.size();
        for (int i=0; i<N; i++) {
            final WindowStateAnimator winAnim = mAllAppWinAnimators.get(i);
            if (mAppToken.mLaunchTaskBehind) {
                winAnim.mWin.mExiting = true;
            }
            winAnim.finishExit();
        }
        if (mAppToken.mLaunchTaskBehind) {
            try {
                mService.mActivityManager.notifyLaunchTaskBehindComplete(mAppToken.token);
            } catch (RemoteException e) {
            }
            mAppToken.mLaunchTaskBehind = false;
        } else {
            mAppToken.updateReportedVisibilityLocked();
            if (mAppToken.mEnteringAnimation) {
                mAppToken.mEnteringAnimation = false;
                try {
                    mService.mActivityManager.notifyEnterAnimationComplete(mAppToken.token);
                } catch (RemoteException e) {
                }
            }
        }

        return false;
    }

    boolean showAllWindowsLocked() {
        boolean isAnimating = false;
        final int NW = mAllAppWinAnimators.size();
        for (int i=0; i<NW; i++) {
            WindowStateAnimator winAnimator = mAllAppWinAnimators.get(i);
            if (WindowManagerService.DEBUG_VISIBILITY) Slog.v(TAG,
                    "performing show on: " + winAnimator);
            winAnimator.performShowLocked();
            isAnimating |= winAnimator.isAnimating();
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
                    pw.print(" x="); pw.print(thumbnailX);
                    pw.print(" y="); pw.print(thumbnailY);
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
