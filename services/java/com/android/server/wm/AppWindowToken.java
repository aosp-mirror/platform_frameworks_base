/*
 * Copyright (C) 2011 The Android Open Source Project
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

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;

import com.android.server.wm.WindowManagerService.H;

import android.content.pm.ActivityInfo;
import android.graphics.Matrix;
import android.os.Message;
import android.os.RemoteException;
import android.util.Slog;
import android.view.IApplicationToken;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerPolicy;
import android.view.animation.Animation;
import android.view.animation.Transformation;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Version of WindowToken that is specifically for a particular application (or
 * really activity) that is displaying windows.
 */
class AppWindowToken extends WindowToken {
    // Non-null only for application tokens.
    final IApplicationToken appToken;

    // All of the windows and child windows that are included in this
    // application token.  Note this list is NOT sorted!
    final ArrayList<WindowState> allAppWindows = new ArrayList<WindowState>();

    int groupId = -1;
    boolean appFullscreen;
    int requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

    // The input dispatching timeout for this application token in nanoseconds.
    long inputDispatchingTimeoutNanos;

    // These are used for determining when all windows associated with
    // an activity have been drawn, so they can be made visible together
    // at the same time.
    // initialize so that it doesn't match mTransactionSequence which is an int.
    long lastTransactionSequence = Long.MIN_VALUE;
    int numInterestingWindows;
    int numDrawnWindows;
    boolean inPendingTransaction;
    boolean allDrawn;

    // Is this token going to be hidden in a little while?  If so, it
    // won't be taken into account for setting the screen orientation.
    boolean willBeHidden;

    // Is this window's surface needed?  This is almost like hidden, except
    // it will sometimes be true a little earlier: when the token has
    // been shown, but is still waiting for its app transition to execute
    // before making its windows shown.
    boolean hiddenRequested;

    // Have we told the window clients to hide themselves?
    boolean clientHidden;

    // Last visibility state we reported to the app token.
    boolean reportedVisible;

    // Last drawn state we reported to the app token.
    boolean reportedDrawn;

    // Set to true when the token has been removed from the window mgr.
    boolean removed;

    // Have we been asked to have this token keep the screen frozen?
    boolean freezingScreen;

    boolean animating;
    Animation animation;
    boolean animInitialized;
    boolean hasTransformation;
    final Transformation transformation = new Transformation();

    // Offset to the window of all layers in the token, for use by
    // AppWindowToken animations.
    int animLayerAdjustment;

    // Information about an application starting window if displayed.
    StartingData startingData;
    WindowState startingWindow;
    View startingView;
    boolean startingDisplayed;
    boolean startingMoved;
    boolean firstWindowDrawn;

    // Special surface for thumbnail animation.
    Surface thumbnail;
    int thumbnailTransactionSeq;
    int thumbnailX;
    int thumbnailY;
    int thumbnailLayer;
    Animation thumbnailAnimation;
    final Transformation thumbnailTransformation = new Transformation();

    // Input application handle used by the input dispatcher.
    final InputApplicationHandle mInputApplicationHandle;

    AppWindowToken(WindowManagerService _service, IApplicationToken _token) {
        super(_service, _token.asBinder(),
                WindowManager.LayoutParams.TYPE_APPLICATION, true);
        appWindowToken = this;
        appToken = _token;
        mInputApplicationHandle = new InputApplicationHandle(this);
    }

    public void setAnimation(Animation anim, boolean initialized) {
        if (WindowManagerService.localLOGV) Slog.v(
            WindowManagerService.TAG, "Setting animation in " + this + ": " + anim);
        animation = anim;
        animating = false;
        animInitialized = initialized;
        anim.restrictDuration(WindowManagerService.MAX_ANIMATION_DURATION);
        anim.scaleCurrentDuration(service.mTransitionAnimationScale);
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
        transformation.setAlpha(reportedVisible ? 1 : 0);
        hasTransformation = true;
    }

    public void setDummyAnimation() {
        if (animation == null) {
            if (WindowManagerService.localLOGV) Slog.v(
                WindowManagerService.TAG, "Setting dummy animation in " + this);
            animation = WindowManagerService.sDummyAnimation;
            animInitialized = false;
        }
    }

    public void clearAnimation() {
        if (animation != null) {
            animation = null;
            animating = true;
            animInitialized = false;
        }
        clearThumbnail();
    }

    public void clearThumbnail() {
        if (thumbnail != null) {
            thumbnail.destroy();
            thumbnail = null;
        }
    }

    void updateLayers() {
        final int N = allAppWindows.size();
        final int adj = animLayerAdjustment;
        thumbnailLayer = -1;
        for (int i=0; i<N; i++) {
            final WindowState w = allAppWindows.get(i);
            final WindowStateAnimator winAnimator = w.mWinAnimator;
            winAnimator.mAnimLayer = w.mLayer + adj;
            if (winAnimator.mAnimLayer > thumbnailLayer) {
                thumbnailLayer = winAnimator.mAnimLayer;
            }
            if (WindowManagerService.DEBUG_LAYERS) Slog.v(WindowManagerService.TAG, "Updating layer " + w + ": "
                    + winAnimator.mAnimLayer);
            if (w == service.mInputMethodTarget && !service.mInputMethodTargetWaitingAnim) {
                service.setInputMethodAnimLayerAdjustment(adj);
            }
            if (w == service.mWallpaperTarget && service.mLowerWallpaperTarget == null) {
                service.setWallpaperAnimLayerAdjustmentLocked(adj);
            }
        }
    }

    void sendAppVisibilityToClients() {
        final int N = allAppWindows.size();
        for (int i=0; i<N; i++) {
            WindowState win = allAppWindows.get(i);
            if (win == startingWindow && clientHidden) {
                // Don't hide the starting window.
                continue;
            }
            try {
                if (WindowManagerService.DEBUG_VISIBILITY) Slog.v(WindowManagerService.TAG,
                        "Setting visibility of " + win + ": " + (!clientHidden));
                win.mClient.dispatchAppVisibility(!clientHidden);
            } catch (RemoteException e) {
            }
        }
    }

    boolean showAllWindowsLocked() {
        boolean isAnimating = false;
        final int NW = allAppWindows.size();
        for (int i=0; i<NW; i++) {
            WindowStateAnimator winAnimator = allAppWindows.get(i).mWinAnimator;
            if (WindowManagerService.DEBUG_VISIBILITY) Slog.v(WindowManagerService.TAG,
                    "performing show on: " + winAnimator);
            winAnimator.performShowLocked();
            isAnimating |= winAnimator.isAnimating();
        }
        return isAnimating;
    }

    private void stepThumbnailAnimation(long currentTime) {
        thumbnailTransformation.clear();
        thumbnailAnimation.getTransformation(currentTime, thumbnailTransformation);
        thumbnailTransformation.getMatrix().preTranslate(thumbnailX, thumbnailY);
        final boolean screenAnimation = service.mAnimator.mScreenRotationAnimation != null
                && service.mAnimator.mScreenRotationAnimation.isAnimating();
        if (screenAnimation) {
            thumbnailTransformation.postCompose(
                    service.mAnimator.mScreenRotationAnimation.getEnterTransformation());
        }
        // cache often used attributes locally
        final float tmpFloats[] = service.mTmpFloats;
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
        // The thumbnail is layered below the window immediately above this
        // token's anim layer.
        thumbnail.setLayer(thumbnailLayer + WindowManagerService.WINDOW_LAYER_MULTIPLIER
                - WindowManagerService.LAYER_OFFSET_THUMBNAIL);
        thumbnail.setMatrix(tmpFloats[Matrix.MSCALE_X], tmpFloats[Matrix.MSKEW_Y],
                tmpFloats[Matrix.MSKEW_X], tmpFloats[Matrix.MSCALE_Y]);
    }

    private boolean stepAnimation(long currentTime) {
        if (animation == null) {
            return false;
        }
        transformation.clear();
        final boolean more = animation.getTransformation(currentTime, transformation);
        if (WindowManagerService.DEBUG_ANIM) Slog.v(
            WindowManagerService.TAG, "Stepped animation in " + this +
            ": more=" + more + ", xform=" + transformation);
        if (!more) {
            animation = null;
            clearThumbnail();
            if (WindowManagerService.DEBUG_ANIM) Slog.v(
                WindowManagerService.TAG, "Finished animation in " + this +
                " @ " + currentTime);
        }
        hasTransformation = more;
        return more;
    }

    // This must be called while inside a transaction.
    boolean stepAnimationLocked(long currentTime, int dw, int dh) {
        if (service.okToDisplay()) {
            // We will run animations as long as the display isn't frozen.

            if (animation == WindowManagerService.sDummyAnimation) {
                // This guy is going to animate, but not yet.  For now count
                // it as not animating for purposes of scheduling transactions;
                // when it is really time to animate, this will be set to
                // a real animation and the next call will execute normally.
                return false;
            }

            if ((allDrawn || animating || startingDisplayed) && animation != null) {
                if (!animating) {
                    if (WindowManagerService.DEBUG_ANIM) Slog.v(
                        WindowManagerService.TAG, "Starting animation in " + this +
                        " @ " + currentTime + ": dw=" + dw + " dh=" + dh
                        + " scale=" + service.mTransitionAnimationScale
                        + " allDrawn=" + allDrawn + " animating=" + animating);
                    if (!animInitialized) {
                        animation.initialize(dw, dh, dw, dh);
                    }
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

        if (!animating) {
            return false;
        }

        service.mPendingLayoutChanges |= WindowManagerPolicy.FINISH_LAYOUT_REDO_ANIM;
        if (WindowManagerService.DEBUG_LAYOUT_REPEATS) {
            service.debugLayoutRepeats("AppWindowToken");
        }

        clearAnimation();
        animating = false;
        if (animLayerAdjustment != 0) {
            animLayerAdjustment = 0;
            updateLayers();
        }
        if (service.mInputMethodTarget != null && service.mInputMethodTarget.mAppToken == this) {
            service.moveInputMethodWindowsIfNeededLocked(true);
        }

        if (WindowManagerService.DEBUG_ANIM) Slog.v(
                WindowManagerService.TAG, "Animation done in " + this
                + ": reportedVisible=" + reportedVisible);

        transformation.clear();

        final int N = windows.size();
        for (int i=0; i<N; i++) {
            windows.get(i).mWinAnimator.finishExit();
        }
        updateReportedVisibilityLocked();

        return false;
    }

    void updateReportedVisibilityLocked() {
        if (appToken == null) {
            return;
        }

        int numInteresting = 0;
        int numVisible = 0;
        int numDrawn = 0;
        boolean nowGone = true;

        if (WindowManagerService.DEBUG_VISIBILITY) Slog.v(WindowManagerService.TAG, "Update reported visibility: " + this);
        final int N = allAppWindows.size();
        for (int i=0; i<N; i++) {
            WindowState win = allAppWindows.get(i);
            if (win == startingWindow || win.mAppFreezing
                    || win.mViewVisibility != View.VISIBLE
                    || win.mAttrs.type == TYPE_APPLICATION_STARTING
                    || win.mDestroying) {
                continue;
            }
            if (WindowManagerService.DEBUG_VISIBILITY) {
                Slog.v(WindowManagerService.TAG, "Win " + win + ": isDrawn="
                        + win.isDrawnLw()
                        + ", isAnimating=" + win.mWinAnimator.isAnimating());
                if (!win.isDrawnLw()) {
                    Slog.v(WindowManagerService.TAG, "Not displayed: s=" + win.mWinAnimator.mSurface
                            + " pv=" + win.mPolicyVisibility
                            + " dp=" + win.mWinAnimator.mDrawPending
                            + " cdp=" + win.mWinAnimator.mCommitDrawPending
                            + " ah=" + win.mAttachedHidden
                            + " th="
                            + (win.mAppToken != null
                                    ? win.mAppToken.hiddenRequested : false)
                            + " a=" + win.mWinAnimator.mAnimating);
                }
            }
            numInteresting++;
            if (win.isDrawnLw()) {
                numDrawn++;
                if (!win.mWinAnimator.isAnimating()) {
                    numVisible++;
                }
                nowGone = false;
            } else if (win.mWinAnimator.isAnimating()) {
                nowGone = false;
            }
        }

        boolean nowDrawn = numInteresting > 0 && numDrawn >= numInteresting;
        boolean nowVisible = numInteresting > 0 && numVisible >= numInteresting;
        if (!nowGone) {
            // If the app is not yet gone, then it can only become visible/drawn.
            if (!nowDrawn) {
                nowDrawn = reportedDrawn;
            }
            if (!nowVisible) {
                nowVisible = reportedVisible;
            }
        }
        if (WindowManagerService.DEBUG_VISIBILITY) Slog.v(WindowManagerService.TAG, "VIS " + this + ": interesting="
                + numInteresting + " visible=" + numVisible);
        if (nowDrawn != reportedDrawn) {
            if (nowDrawn) {
                Message m = service.mH.obtainMessage(
                        H.REPORT_APPLICATION_TOKEN_DRAWN, this);
                service.mH.sendMessage(m);
            }
            reportedDrawn = nowDrawn;
        }
        if (nowVisible != reportedVisible) {
            if (WindowManagerService.DEBUG_VISIBILITY) Slog.v(
                    WindowManagerService.TAG, "Visibility changed in " + this
                    + ": vis=" + nowVisible);
            reportedVisible = nowVisible;
            Message m = service.mH.obtainMessage(
                    H.REPORT_APPLICATION_TOKEN_WINDOWS,
                    nowVisible ? 1 : 0,
                    nowGone ? 1 : 0,
                    this);
            service.mH.sendMessage(m);
        }
    }

    WindowState findMainWindow() {
        int j = windows.size();
        while (j > 0) {
            j--;
            WindowState win = windows.get(j);
            if (win.mAttrs.type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION
                    || win.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING) {
                return win;
            }
        }
        return null;
    }

    @Override
    void dump(PrintWriter pw, String prefix) {
        super.dump(pw, prefix);
        if (appToken != null) {
            pw.print(prefix); pw.println("app=true");
        }
        if (allAppWindows.size() > 0) {
            pw.print(prefix); pw.print("allAppWindows="); pw.println(allAppWindows);
        }
        pw.print(prefix); pw.print("groupId="); pw.print(groupId);
                pw.print(" appFullscreen="); pw.print(appFullscreen);
                pw.print(" requestedOrientation="); pw.println(requestedOrientation);
        pw.print(prefix); pw.print("hiddenRequested="); pw.print(hiddenRequested);
                pw.print(" clientHidden="); pw.print(clientHidden);
                pw.print(" willBeHidden="); pw.print(willBeHidden);
                pw.print(" reportedDrawn="); pw.print(reportedDrawn);
                pw.print(" reportedVisible="); pw.println(reportedVisible);
        if (paused || freezingScreen) {
            pw.print(prefix); pw.print("paused="); pw.print(paused);
                    pw.print(" freezingScreen="); pw.println(freezingScreen);
        }
        if (numInterestingWindows != 0 || numDrawnWindows != 0
                || inPendingTransaction || allDrawn) {
            pw.print(prefix); pw.print("numInterestingWindows=");
                    pw.print(numInterestingWindows);
                    pw.print(" numDrawnWindows="); pw.print(numDrawnWindows);
                    pw.print(" inPendingTransaction="); pw.print(inPendingTransaction);
                    pw.print(" allDrawn="); pw.println(allDrawn);
        }
        if (animating || animation != null) {
            pw.print(prefix); pw.print("animating="); pw.print(animating);
                    pw.print(" animation="); pw.println(animation);
        }
        if (hasTransformation) {
            pw.print(prefix); pw.print("XForm: ");
                    transformation.printShortString(pw);
                    pw.println();
        }
        if (animLayerAdjustment != 0) {
            pw.print(prefix); pw.print("animLayerAdjustment="); pw.println(animLayerAdjustment);
        }
        if (startingData != null || removed || firstWindowDrawn) {
            pw.print(prefix); pw.print("startingData="); pw.print(startingData);
                    pw.print(" removed="); pw.print(removed);
                    pw.print(" firstWindowDrawn="); pw.println(firstWindowDrawn);
        }
        if (startingWindow != null || startingView != null
                || startingDisplayed || startingMoved) {
            pw.print(prefix); pw.print("startingWindow="); pw.print(startingWindow);
                    pw.print(" startingView="); pw.print(startingView);
                    pw.print(" startingDisplayed="); pw.print(startingDisplayed);
                    pw.print(" startingMoved"); pw.println(startingMoved);
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
    }

    @Override
    public String toString() {
        if (stringName == null) {
            StringBuilder sb = new StringBuilder();
            sb.append("AppWindowToken{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(" token="); sb.append(token); sb.append('}');
            stringName = sb.toString();
        }
        return stringName;
    }
}