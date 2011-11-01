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
import android.os.Message;
import android.os.RemoteException;
import android.util.Slog;
import android.view.IApplicationToken;
import android.view.View;
import android.view.WindowManager;
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
    int lastTransactionSequence;
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

    // Input application handle used by the input dispatcher.
    final InputApplicationHandle mInputApplicationHandle;

    AppWindowToken(WindowManagerService _service, IApplicationToken _token) {
        super(_service, _token.asBinder(),
                WindowManager.LayoutParams.TYPE_APPLICATION, true);
        appWindowToken = this;
        appToken = _token;
        mInputApplicationHandle = new InputApplicationHandle(this);
        lastTransactionSequence = service.mTransactionSequence-1;
    }

    public void setAnimation(Animation anim) {
        if (WindowManagerService.localLOGV) Slog.v(
            WindowManagerService.TAG, "Setting animation in " + this + ": " + anim);
        animation = anim;
        animating = false;
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
    }

    public void setDummyAnimation() {
        if (animation == null) {
            if (WindowManagerService.localLOGV) Slog.v(
                WindowManagerService.TAG, "Setting dummy animation in " + this);
            animation = WindowManagerService.sDummyAnimation;
        }
    }

    public void clearAnimation() {
        if (animation != null) {
            animation = null;
            animating = true;
        }
    }

    void updateLayers() {
        final int N = allAppWindows.size();
        final int adj = animLayerAdjustment;
        for (int i=0; i<N; i++) {
            WindowState w = allAppWindows.get(i);
            w.mAnimLayer = w.mLayer + adj;
            if (WindowManagerService.DEBUG_LAYERS) Slog.v(WindowManagerService.TAG, "Updating layer " + w + ": "
                    + w.mAnimLayer);
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

    void showAllWindowsLocked() {
        final int NW = allAppWindows.size();
        for (int i=0; i<NW; i++) {
            WindowState w = allAppWindows.get(i);
            if (WindowManagerService.DEBUG_VISIBILITY) Slog.v(WindowManagerService.TAG,
                    "performing show on: " + w);
            w.performShowLocked();
        }
    }

    // This must be called while inside a transaction.
    boolean stepAnimationLocked(long currentTime, int dw, int dh) {
        if (!service.mDisplayFrozen && service.mPolicy.isScreenOnFully()) {
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
                    animation.initialize(dw, dh, dw, dh);
                    animation.setStartTime(currentTime);
                    animating = true;
                }
                transformation.clear();
                final boolean more = animation.getTransformation(
                    currentTime, transformation);
                if (WindowManagerService.DEBUG_ANIM) Slog.v(
                    WindowManagerService.TAG, "Stepped animation in " + this +
                    ": more=" + more + ", xform=" + transformation);
                if (more) {
                    // we're done!
                    hasTransformation = true;
                    return true;
                }
                if (WindowManagerService.DEBUG_ANIM) Slog.v(
                    WindowManagerService.TAG, "Finished animation in " + this +
                    " @ " + currentTime);
                animation = null;
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
            windows.get(i).finishExit();
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
                        + ", isAnimating=" + win.isAnimating());
                if (!win.isDrawnLw()) {
                    Slog.v(WindowManagerService.TAG, "Not displayed: s=" + win.mSurface
                            + " pv=" + win.mPolicyVisibility
                            + " dp=" + win.mDrawPending
                            + " cdp=" + win.mCommitDrawPending
                            + " ah=" + win.mAttachedHidden
                            + " th="
                            + (win.mAppToken != null
                                    ? win.mAppToken.hiddenRequested : false)
                            + " a=" + win.mAnimating);
                }
            }
            numInteresting++;
            if (win.isDrawnLw()) {
                numDrawn++;
                if (!win.isAnimating()) {
                    numVisible++;
                }
                nowGone = false;
            } else if (win.isAnimating()) {
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