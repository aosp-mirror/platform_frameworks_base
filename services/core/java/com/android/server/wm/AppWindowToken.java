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
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ANIM;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_APP_TRANSITIONS;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_WINDOW_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import com.android.server.input.InputApplicationHandle;
import com.android.server.wm.WindowManagerService.H;

import android.annotation.NonNull;
import android.content.pm.ActivityInfo;
import android.os.Message;
import android.os.RemoteException;
import android.util.Slog;
import android.view.IApplicationToken;
import android.view.View;
import android.view.WindowManager;

import java.io.PrintWriter;
import java.util.ArrayList;

class AppTokenList extends ArrayList<AppWindowToken> {
}

/**
 * Version of WindowToken that is specifically for a particular application (or
 * really activity) that is displaying windows.
 */
class AppWindowToken extends WindowToken {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "AppWindowToken" : TAG_WM;

    // Non-null only for application tokens.
    final IApplicationToken appToken;

    // All of the windows and child windows that are included in this
    // application token.  Note this list is NOT sorted!
    final WindowList allAppWindows = new WindowList();
    @NonNull final AppWindowAnimator mAppAnimator;

    final boolean voiceInteraction;

    // Whether we're performing an entering animation with a saved surface.
    boolean mAnimatingWithSavedSurface;

    Task mTask;
    boolean appFullscreen;
    int requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    boolean layoutConfigChanges;
    boolean showForAllUsers;

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
    // Set to true when this app creates a surface while in the middle of an animation. In that
    // case do not clear allDrawn until the animation completes.
    boolean deferClearAllDrawn;

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

    boolean appDied;
    // Information about an application starting window if displayed.
    StartingData startingData;
    WindowState startingWindow;
    View startingView;
    boolean startingDisplayed;
    boolean startingMoved;
    boolean firstWindowDrawn;

    // Input application handle used by the input dispatcher.
    final InputApplicationHandle mInputApplicationHandle;

    boolean mIsExiting;

    boolean mLaunchTaskBehind;
    boolean mEnteringAnimation;

    // True if the windows associated with this token should be cropped to their stack bounds.
    boolean mCropWindowsToStack;

    // This application will have its window replaced due to relaunch. This allows window manager
    // to differentiate between simple removal of a window and replacement. In the latter case it
    // will preserve the old window until the new one is drawn.
    boolean mWillReplaceWindow;
    // If true, the replaced window was already requested to be removed.
    boolean mReplacingRemoveRequested;
    // Whether the replacement of the window should trigger app transition animation.
    boolean mAnimateReplacingWindow;
    // If not null, the window that will be used to replace the old one. This is being set when
    // the window is added and unset when this window reports its first draw.
    WindowState mReplacingWindow;
    // Whether the new window has replaced the old one, so the old one can be removed without
    // blinking.
    boolean mHasReplacedWindow;

    AppWindowToken(WindowManagerService _service, IApplicationToken _token,
            boolean _voiceInteraction) {
        super(_service, _token.asBinder(),
                WindowManager.LayoutParams.TYPE_APPLICATION, true);
        appWindowToken = this;
        appToken = _token;
        voiceInteraction = _voiceInteraction;
        mInputApplicationHandle = new InputApplicationHandle(this);
        mAppAnimator = new AppWindowAnimator(this);
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
                if (DEBUG_VISIBILITY) Slog.v(TAG,
                        "Setting visibility of " + win + ": " + (!clientHidden));
                win.mClient.dispatchAppVisibility(!clientHidden);
            } catch (RemoteException e) {
            }
        }
    }

    void updateReportedVisibilityLocked() {
        if (appToken == null) {
            return;
        }

        int numInteresting = 0;
        int numVisible = 0;
        int numDrawn = 0;
        boolean nowGone = true;

        if (DEBUG_VISIBILITY) Slog.v(TAG,
                "Update reported visibility: " + this);
        final int N = allAppWindows.size();
        for (int i=0; i<N; i++) {
            WindowState win = allAppWindows.get(i);
            if (win == startingWindow || win.mAppFreezing
                    || win.mViewVisibility != View.VISIBLE
                    || win.mAttrs.type == TYPE_APPLICATION_STARTING
                    || win.mDestroying) {
                continue;
            }
            if (DEBUG_VISIBILITY) {
                Slog.v(TAG, "Win " + win + ": isDrawn="
                        + win.isDrawnLw()
                        + ", isAnimating=" + win.mWinAnimator.isAnimating());
                if (!win.isDrawnLw()) {
                    Slog.v(TAG, "Not displayed: s=" +
                            win.mWinAnimator.mSurfaceController
                            + " pv=" + win.mPolicyVisibility
                            + " mDrawState=" + win.mWinAnimator.mDrawState
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
        if (DEBUG_VISIBILITY) Slog.v(TAG, "VIS " + this + ": interesting="
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
            if (DEBUG_VISIBILITY) Slog.v(
                    TAG, "Visibility changed in " + this
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
        WindowState candidate = null;
        int j = windows.size();
        while (j > 0) {
            j--;
            WindowState win = windows.get(j);
            if (win.mAttrs.type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION
                    || win.mAttrs.type == WindowManager.LayoutParams.TYPE_APPLICATION_STARTING) {
                // In cases where there are multiple windows, we prefer the non-exiting window. This
                // happens for example when replacing windows during an activity relaunch. When
                // constructing the animation, we want the new window, not the exiting one.
                if (win.mExiting) {
                    candidate = win;
                } else {
                    return win;
                }
            }
        }
        return candidate;
    }

    boolean stackCanReceiveKeys() {
        return (windows.size() > 0) ? windows.get(windows.size() - 1).stackCanReceiveKeys() : false;
    }

    boolean isVisible() {
        final int N = allAppWindows.size();
        for (int i=0; i<N; i++) {
            WindowState win = allAppWindows.get(i);
            // If we're animating with a saved surface, we're already visible.
            // Return true so that the alpha doesn't get cleared.
            if (!win.mAppFreezing
                    && (win.mViewVisibility == View.VISIBLE || mAnimatingWithSavedSurface
                            || (win.mWinAnimator.isAnimating()
                                    && !service.mAppTransition.isTransitionSet()))
                    && !win.mDestroying
                    && win.isDrawnLw()) {
                return true;
            }
        }
        return false;
    }

    void removeAppFromTaskLocked() {
        mIsExiting = false;
        removeAllWindows();

        // Use local variable because removeAppToken will null out mTask.
        final Task task = mTask;
        if (task != null) {
            if (!task.removeAppToken(this)) {
                Slog.e(TAG, "removeAppFromTaskLocked: token=" + this
                        + " not found.");
            }
            task.mStack.mExitingAppTokens.remove(this);
        }
    }

    /**
     * Checks whether we should save surfaces for this app.
     *
     * @return true if the surfaces should be saved, false otherwise.
     */
    boolean shouldSaveSurface() {
        // We want to save surface if the app's windows are "allDrawn", or if we're
        // currently animating with save surfaces. (If the app didn't even finish
        // drawing when the user exits, but we have a saved surface from last time,
        // we still want to keep that surface.)
        return allDrawn || mAnimatingWithSavedSurface;
    }

    boolean hasSavedSurface() {
        for (int i = windows.size() -1; i >= 0; i--) {
            final WindowState ws = windows.get(i);
            if (ws.hasSavedSurface()) {
                return true;
            }
        }
        return false;
    }

    void restoreSavedSurfaces() {
        if (!hasSavedSurface()) {
            return;
        }

        if (DEBUG_APP_TRANSITIONS || DEBUG_ANIM) Slog.v(TAG_WM,
                "Restoring saved surfaces: " + this + ", allDrawn=" + allDrawn);

        mAnimatingWithSavedSurface = true;
        for (int i = windows.size() - 1; i >= 0; i--) {
            WindowState ws = windows.get(i);
            ws.restoreSavedSurface();
        }
    }

    void destroySavedSurfaces() {
        for (int i = windows.size() - 1; i >= 0; i--) {
            WindowState win = windows.get(i);
            win.destroySavedSurface();
        }
    }

    @Override
    void removeAllWindows() {
        for (int winNdx = allAppWindows.size() - 1; winNdx >= 0;
                // removeWindowLocked at bottom of loop may remove multiple entries from
                // allAppWindows if the window to be removed has child windows. It also may
                // not remove any windows from allAppWindows at all if win is exiting and
                // currently animating away. This ensures that winNdx is monotonically decreasing
                // and never beyond allAppWindows bounds.
                winNdx = Math.min(winNdx - 1, allAppWindows.size() - 1)) {
            WindowState win = allAppWindows.get(winNdx);
            if (DEBUG_WINDOW_MOVEMENT) {
                Slog.w(TAG, "removeAllWindows: removing win=" + win);
            }

            service.removeWindowLocked(win);
        }
        allAppWindows.clear();
        windows.clear();
    }

    void removeAllDeadWindows() {
        for (int winNdx = allAppWindows.size() - 1; winNdx >= 0;
                // removeWindowLocked at bottom of loop may remove multiple entries from
                // allAppWindows if the window to be removed has child windows. It also may
                // not remove any windows from allAppWindows at all if win is exiting and
                // currently animating away. This ensures that winNdx is monotonically decreasing
                // and never beyond allAppWindows bounds.
                winNdx = Math.min(winNdx - 1, allAppWindows.size() - 1)) {
            WindowState win = allAppWindows.get(winNdx);
            if (win.mAppDied) {
                if (DEBUG_WINDOW_MOVEMENT) {
                    Slog.w(TAG, "removeAllDeadWindows: " + win);
                }
                // Set mDestroying, we don't want any animation or delayed removal here.
                win.mDestroying = true;
                service.removeWindowLocked(win);
            }
        }
    }

    @Override
    void dump(PrintWriter pw, String prefix) {
        super.dump(pw, prefix);
        if (appToken != null) {
            pw.print(prefix); pw.print("app=true voiceInteraction="); pw.println(voiceInteraction);
        }
        if (allAppWindows.size() > 0) {
            pw.print(prefix); pw.print("allAppWindows="); pw.println(allAppWindows);
        }
        pw.print(prefix); pw.print("task="); pw.println(mTask);
        pw.print(prefix); pw.print(" appFullscreen="); pw.print(appFullscreen);
                pw.print(" requestedOrientation="); pw.println(requestedOrientation);
        pw.print(prefix); pw.print("hiddenRequested="); pw.print(hiddenRequested);
                pw.print(" clientHidden="); pw.print(clientHidden);
                pw.print(" reportedDrawn="); pw.print(reportedDrawn);
                pw.print(" reportedVisible="); pw.println(reportedVisible);
        if (paused) {
            pw.print(prefix); pw.print("paused="); pw.println(paused);
        }
        if (numInterestingWindows != 0 || numDrawnWindows != 0
                || allDrawn || mAppAnimator.allDrawn) {
            pw.print(prefix); pw.print("numInterestingWindows=");
                    pw.print(numInterestingWindows);
                    pw.print(" numDrawnWindows="); pw.print(numDrawnWindows);
                    pw.print(" inPendingTransaction="); pw.print(inPendingTransaction);
                    pw.print(" allDrawn="); pw.print(allDrawn);
                    pw.print(" (animator="); pw.print(mAppAnimator.allDrawn);
                    pw.println(")");
        }
        if (inPendingTransaction) {
            pw.print(prefix); pw.print("inPendingTransaction=");
                    pw.println(inPendingTransaction);
        }
        if (startingData != null || removed || firstWindowDrawn || mIsExiting) {
            pw.print(prefix); pw.print("startingData="); pw.print(startingData);
                    pw.print(" removed="); pw.print(removed);
                    pw.print(" firstWindowDrawn="); pw.print(firstWindowDrawn);
                    pw.print(" mIsExiting="); pw.println(mIsExiting);
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
