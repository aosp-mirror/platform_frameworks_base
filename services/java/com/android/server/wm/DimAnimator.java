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

import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.util.Slog;
import android.util.TypedValue;
import android.view.Surface;
import android.view.SurfaceSession;

import java.io.PrintWriter;

/**
 * DimAnimator class that controls the dim animation. This holds the surface and
 * all state used for dim animation.
 */
class DimAnimator {
    Surface mDimSurface;
    boolean mDimShown = false;
    float mDimCurrentAlpha;
    float mDimTargetAlpha;
    float mDimDeltaPerMs;
    long mLastDimAnimTime;
    
    int mLastDimWidth, mLastDimHeight;

    DimAnimator (SurfaceSession session) {
        if (mDimSurface == null) {
            if (WindowManagerService.SHOW_TRANSACTIONS ||
                    WindowManagerService.SHOW_SURFACE_ALLOC) Slog.i(WindowManagerService.TAG,
                            "  DIM " + mDimSurface + ": CREATE");
            try {
                mDimSurface = new Surface(session, 0,
                        "DimAnimator",
                        -1, 16, 16, PixelFormat.OPAQUE,
                        Surface.FX_SURFACE_DIM);
                mDimSurface.setAlpha(0.0f);
            } catch (Exception e) {
                Slog.e(WindowManagerService.TAG, "Exception creating Dim surface", e);
            }
        }
    }

    /**
     * Show the dim surface.
     */
    void show(int dw, int dh) {
        if (!mDimShown) {
            if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(WindowManagerService.TAG, "  DIM " + mDimSurface + ": SHOW pos=(0,0) (" +
                    dw + "x" + dh + ")");
            mDimShown = true;
            try {
                mLastDimWidth = dw;
                mLastDimHeight = dh;
                mDimSurface.setPosition(0, 0);
                mDimSurface.setSize(dw, dh);
                mDimSurface.show();
            } catch (RuntimeException e) {
                Slog.w(WindowManagerService.TAG, "Failure showing dim surface", e);
            }
        } else if (mLastDimWidth != dw || mLastDimHeight != dh) {
            mLastDimWidth = dw;
            mLastDimHeight = dh;
            mDimSurface.setSize(dw, dh);
        }
    }

    /**
     * Set's the dim surface's layer and update dim parameters that will be used in
     * {@link updateSurface} after all windows are examined.
     */
    void updateParameters(Resources res, WindowState w, long currentTime) {
        mDimSurface.setLayer(w.mAnimLayer - WindowManagerService.LAYER_OFFSET_DIM);

        final float target = w.mExiting ? 0 : w.mAttrs.dimAmount;
        if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(WindowManagerService.TAG, "  DIM " + mDimSurface
                + ": layer=" + (w.mAnimLayer-1) + " target=" + target);
        if (mDimTargetAlpha != target) {
            // If the desired dim level has changed, then
            // start an animation to it.
            mLastDimAnimTime = currentTime;
            long duration = (w.mAnimating && w.mAnimation != null)
                    ? w.mAnimation.computeDurationHint()
                    : WindowManagerService.DEFAULT_DIM_DURATION;
            if (target > mDimTargetAlpha) {
                TypedValue tv = new TypedValue();
                res.getValue(com.android.internal.R.fraction.config_dimBehindFadeDuration,
                        tv, true);
                if (tv.type == TypedValue.TYPE_FRACTION) {
                    duration = (long)tv.getFraction((float)duration, (float)duration);
                } else if (tv.type >= TypedValue.TYPE_FIRST_INT
                        && tv.type <= TypedValue.TYPE_LAST_INT) {
                    duration = tv.data;
                }
            }
            if (duration < 1) {
                // Don't divide by zero
                duration = 1;
            }
            mDimTargetAlpha = target;
            mDimDeltaPerMs = (mDimTargetAlpha-mDimCurrentAlpha) / duration;
        }
    }

    /**
     * Updating the surface's alpha. Returns true if the animation continues, or returns
     * false when the animation is finished and the dim surface is hidden.
     */
    boolean updateSurface(boolean dimming, long currentTime, boolean displayFrozen) {
        if (!dimming) {
            if (mDimTargetAlpha != 0) {
                mLastDimAnimTime = currentTime;
                mDimTargetAlpha = 0;
                mDimDeltaPerMs = (-mDimCurrentAlpha) / WindowManagerService.DEFAULT_DIM_DURATION;
            }
        }

        boolean animating = false;
        if (mLastDimAnimTime != 0) {
            mDimCurrentAlpha += mDimDeltaPerMs
                    * (currentTime-mLastDimAnimTime);
            boolean more = true;
            if (displayFrozen) {
                // If the display is frozen, there is no reason to animate.
                more = false;
            } else if (mDimDeltaPerMs > 0) {
                if (mDimCurrentAlpha > mDimTargetAlpha) {
                    more = false;
                }
            } else if (mDimDeltaPerMs < 0) {
                if (mDimCurrentAlpha < mDimTargetAlpha) {
                    more = false;
                }
            } else {
                more = false;
            }

            // Do we need to continue animating?
            if (more) {
                if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(WindowManagerService.TAG, "  DIM "
                        + mDimSurface + ": alpha=" + mDimCurrentAlpha);
                mLastDimAnimTime = currentTime;
                mDimSurface.setAlpha(mDimCurrentAlpha);
                animating = true;
            } else {
                mDimCurrentAlpha = mDimTargetAlpha;
                mLastDimAnimTime = 0;
                if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(WindowManagerService.TAG, "  DIM "
                        + mDimSurface + ": final alpha=" + mDimCurrentAlpha);
                mDimSurface.setAlpha(mDimCurrentAlpha);
                if (!dimming) {
                    if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(WindowManagerService.TAG, "  DIM " + mDimSurface
                            + ": HIDE");
                    try {
                        mDimSurface.hide();
                    } catch (RuntimeException e) {
                        Slog.w(WindowManagerService.TAG, "Illegal argument exception hiding dim surface");
                    }
                    mDimShown = false;
                }
            }
        }
        return animating;
    }

    public void printTo(String prefix, PrintWriter pw) {
        pw.print(prefix);
        pw.print("mDimSurface="); pw.print(mDimSurface);
                pw.print(" "); pw.print(mLastDimWidth); pw.print(" x ");
                pw.println(mLastDimHeight);
        pw.print(prefix);
        pw.print("mDimShown="); pw.print(mDimShown);
        pw.print(" current="); pw.print(mDimCurrentAlpha);
        pw.print(" target="); pw.print(mDimTargetAlpha);
        pw.print(" delta="); pw.print(mDimDeltaPerMs);
        pw.print(" lastAnimTime="); pw.println(mLastDimAnimTime);
    }
}