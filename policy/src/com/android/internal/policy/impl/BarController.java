/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.policy.impl;

import android.app.StatusBarManager;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Slog;
import android.view.View;
import android.view.WindowManagerPolicy.WindowState;

import com.android.internal.statusbar.IStatusBarService;

import java.io.PrintWriter;

/**
 * Controls state/behavior specific to a system bar window.
 */
public class BarController {
    private static final boolean DEBUG = false;

    private static final int TRANSIENT_BAR_NONE = 0;
    private static final int TRANSIENT_BAR_SHOWING = 1;
    private static final int TRANSIENT_BAR_HIDING = 2;

    private static final int TRANSPARENT_ANIMATION_DELAY_MS = 1000;

    private final String mTag;
    private final int mTransientFlag;
    private final int mUnhideFlag;
    private final int mTransparentFlag;
    private final int mStatusBarManagerId;
    private final Handler mHandler;
    private final Object mServiceAquireLock = new Object();
    private IStatusBarService mStatusBarService;

    private WindowState mWin;
    private int mState;
    private int mTransientBarState;
    private boolean mPendingShow;
    private long mLastTransparent;

    public BarController(String tag, int transientFlag, int unhideFlag, int transparentFlag,
            int statusBarManagerId) {
        mTag = "BarController." + tag;
        mTransientFlag = transientFlag;
        mUnhideFlag = unhideFlag;
        mTransparentFlag = transparentFlag;
        mStatusBarManagerId = statusBarManagerId;
        mHandler = new Handler();
    }

    public void setWindow(WindowState win) {
        mWin = win;
    }

    public boolean isHidden() {
        return mState == StatusBarManager.WINDOW_STATE_HIDDEN;
    }

    public void showTransient() {
        if (mWin != null) {
            setTransientBarState(TRANSIENT_BAR_SHOWING);
        }
    }

    public boolean isTransientShowing() {
        return mTransientBarState == TRANSIENT_BAR_SHOWING;
    }

    public boolean wasRecentlyTransparent() {
        return (SystemClock.uptimeMillis() - mLastTransparent) < TRANSPARENT_ANIMATION_DELAY_MS;
    }

    public void adjustSystemUiVisibilityLw(int oldVis, int vis) {
        if (mWin != null && mTransientBarState == TRANSIENT_BAR_SHOWING &&
                (vis & mTransientFlag) == 0) {
            // sysui requests hide
            setTransientBarState(TRANSIENT_BAR_HIDING);
            setBarShowingLw(false);
        } else if (mWin != null && (oldVis & mUnhideFlag) != 0 && (vis & mUnhideFlag) == 0) {
            // sysui ready to unhide
            setBarShowingLw(true);
        }
    }

    public boolean setBarShowingLw(final boolean show) {
        if (mWin == null) return false;
        if (show && mTransientBarState == TRANSIENT_BAR_HIDING) {
            mPendingShow = true;
            return false;
        }
        final boolean oldVis = mWin.isVisibleLw();
        final boolean oldAnim = mWin.isAnimatingLw();
        final boolean rt = show ? mWin.showLw(true) : mWin.hideLw(true);
        final int state = computeState(oldVis, oldAnim, mWin.isVisibleLw(), mWin.isAnimatingLw());
        if (state > -1) {
            updateState(state);
        }
        return rt;
    }

    private int computeState(boolean oldVis, boolean oldAnim, boolean newVis, boolean newAnim) {
        return (!newVis && !newAnim) ? StatusBarManager.WINDOW_STATE_HIDDEN
                : (!oldVis && newVis && newAnim) ? StatusBarManager.WINDOW_STATE_SHOWING
                : (oldVis && newVis && !oldAnim && newAnim) ? StatusBarManager.WINDOW_STATE_HIDING
                : -1;
    }

    private void updateState(final int state) {
        if (state != mState) {
            mState = state;
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        IStatusBarService statusbar = getStatusBarService();
                        if (statusbar != null) {
                            statusbar.setWindowState(mStatusBarManagerId, state);
                        }
                    } catch (RemoteException e) {
                        // re-acquire status bar service next time it is needed.
                        mStatusBarService = null;
                    }
                }
            });
        }
    }

    public boolean checkHiddenLw() {
        if (mWin != null) {
            if (!mWin.isVisibleLw() && !mWin.isAnimatingLw()) {
                updateState(StatusBarManager.WINDOW_STATE_HIDDEN);
            }
            if (mTransientBarState == TRANSIENT_BAR_HIDING && !mWin.isVisibleLw()) {
                // Finished animating out, clean up and reset style
                setTransientBarState(TRANSIENT_BAR_NONE);
                if (mPendingShow) {
                    setBarShowingLw(true);
                    mPendingShow = false;
                }
                return true;
            }
        }
        return false;
    }

    public boolean checkShowTransientBarLw() {
        if (mTransientBarState == TRANSIENT_BAR_SHOWING) {
            if (DEBUG) Slog.d(mTag, "Not showing transient bar, already shown");
            return false;
        } else if (mWin == null) {
            if (DEBUG) Slog.d(mTag, "Not showing transient bar, bar doesn't exist");
            return false;
        } else if (mWin.isDisplayedLw()) {
            if (DEBUG) Slog.d(mTag, "Not showing transient bar, bar already visible");
            return false;
        } else {
            return true;
        }
    }

    public int updateVisibilityLw(boolean allowed, int oldVis, int vis) {
        if (mWin == null) return vis;
        if (mTransientBarState == TRANSIENT_BAR_SHOWING) { // transient bar requested
            if (allowed) {
                vis |= mTransientFlag;
                if ((oldVis & mTransientFlag) == 0) {
                    vis |= mUnhideFlag;  // tell sysui we're ready to unhide
                }
            } else {
                setTransientBarState(TRANSIENT_BAR_NONE);  // request denied
            }
        }
        if (mTransientBarState != TRANSIENT_BAR_NONE) {
            vis |= mTransientFlag;  // ignore clear requests until transition completes
            vis &= ~View.SYSTEM_UI_FLAG_LOW_PROFILE;  // never show transient bars in low profile
        }
        if ((vis & mTransparentFlag) != 0 || (oldVis & mTransparentFlag) != 0) {
            mLastTransparent = SystemClock.uptimeMillis();
        }
        return vis;
    }

    private void setTransientBarState(int state) {
        if (mWin != null && state != mTransientBarState) {
            if (mTransientBarState == TRANSIENT_BAR_SHOWING || state == TRANSIENT_BAR_SHOWING) {
                mLastTransparent = SystemClock.uptimeMillis();
            }
            mTransientBarState = state;
            if (DEBUG) Slog.d(mTag, "New state: " + transientBarStateToString(state));
        }
    }

    private IStatusBarService getStatusBarService() {
        synchronized (mServiceAquireLock) {
            if (mStatusBarService == null) {
                mStatusBarService = IStatusBarService.Stub.asInterface(
                        ServiceManager.getService("statusbar"));
            }
            return mStatusBarService;
        }
    }

    private static String transientBarStateToString(int state) {
        if (state == TRANSIENT_BAR_HIDING) return "TRANSIENT_BAR_HIDING";
        if (state == TRANSIENT_BAR_SHOWING) return "TRANSIENT_BAR_SHOWING";
        if (state == TRANSIENT_BAR_NONE) return "TRANSIENT_BAR_NONE";
        throw new IllegalArgumentException("Unknown state " + state);
    }

    public void dump(PrintWriter pw, String prefix) {
        if (mWin != null) {
            pw.print(prefix); pw.print(mTag); pw.print(' ');
            pw.print("mTransientBar"); pw.print('=');
            pw.println(transientBarStateToString(mTransientBarState));
        }
    }
}
