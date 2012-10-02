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
    static final String TAG = "DimAnimator";

    Surface mDimSurface;
    boolean mDimShown = false;
    float mDimCurrentAlpha;
    float mDimTargetAlpha;
    float mDimDeltaPerMs;
    long mLastDimAnimTime;

    int mLastDimWidth, mLastDimHeight;

    DimAnimator (SurfaceSession session, final int layerStack) {
        try {
            if (WindowManagerService.DEBUG_SURFACE_TRACE) {
                mDimSurface = new WindowStateAnimator.SurfaceTrace(session,
                    "DimAnimator",
                    16, 16, PixelFormat.OPAQUE,
                    Surface.FX_SURFACE_DIM | Surface.HIDDEN);
            } else {
                mDimSurface = new Surface(session, "DimAnimator",
                    16, 16, PixelFormat.OPAQUE,
                    Surface.FX_SURFACE_DIM | Surface.HIDDEN);
            }
            if (WindowManagerService.SHOW_TRANSACTIONS ||
                    WindowManagerService.SHOW_SURFACE_ALLOC) Slog.i(WindowManagerService.TAG,
                            "  DIM " + mDimSurface + ": CREATE");
            mDimSurface.setLayerStack(layerStack);
            mDimSurface.setAlpha(0.0f);
            mDimSurface.show();
        } catch (Exception e) {
            Slog.e(WindowManagerService.TAG, "Exception creating Dim surface", e);
        }
    }

    /**
     * Set's the dim surface's layer and update dim parameters that will be used in
     * {@link #updateSurface} after all windows are examined.
     */
    void updateParameters(final Resources res, final Parameters params, final long currentTime) {
        if (mDimSurface == null) {
            Slog.e(TAG, "updateParameters: no Surface");
            return;
        }

        // Multiply by 1.5 so that rotating a frozen surface that includes this does not expose a
        // corner.
        final int dw = (int) (params.mDimWidth * 1.5);
        final int dh = (int) (params.mDimHeight * 1.5);
        final WindowStateAnimator winAnimator = params.mDimWinAnimator;
        final float target = params.mDimTarget;
        if (!mDimShown) {
            if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(WindowManagerService.TAG,
                "  DIM " + mDimSurface + ": SHOW pos=(0,0) (" + dw + "x" + dh + ")");
            mDimShown = true;
            try {
                mLastDimWidth = dw;
                mLastDimHeight = dh;
                // back off position so mDimXXX/4 is before and mDimXXX/4 is after
                mDimSurface.setPosition(-1 * dw / 6, -1 * dh /6);
                mDimSurface.setSize(dw, dh);
                mDimSurface.show();
            } catch (RuntimeException e) {
                Slog.w(WindowManagerService.TAG, "Failure showing dim surface", e);
            }
        } else if (mLastDimWidth != dw || mLastDimHeight != dh) {
            mLastDimWidth = dw;
            mLastDimHeight = dh;
            mDimSurface.setSize(dw, dh);
            // back off position so mDimXXX/4 is before and mDimXXX/4 is after
            mDimSurface.setPosition(-1 * dw / 6, -1 * dh /6);
        }

        mDimSurface.setLayer(winAnimator.mAnimLayer - WindowManagerService.LAYER_OFFSET_DIM);

        if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(WindowManagerService.TAG, "  DIM "
                + mDimSurface + ": layer=" + (winAnimator.mAnimLayer-1) + " target=" + target);
        if (mDimTargetAlpha != target) {
            // If the desired dim level has changed, then
            // start an animation to it.
            mLastDimAnimTime = currentTime;
            long duration = (winAnimator.mAnimating && winAnimator.mAnimation != null)
                    ? winAnimator.mAnimation.computeDurationHint()
                    : WindowManagerService.DEFAULT_DIM_DURATION;
            if (target > mDimTargetAlpha) {
                TypedValue tv = new TypedValue();
                res.getValue(com.android.internal.R.fraction.config_dimBehindFadeDuration,
                        tv, true);
                if (tv.type == TypedValue.TYPE_FRACTION) {
                    duration = (long)tv.getFraction(duration, duration);
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
        if (mDimSurface == null) {
            Slog.e(TAG, "updateSurface: no Surface");
            return false;
        }

        if (!dimming) {
            if (mDimTargetAlpha != 0) {
                mLastDimAnimTime = currentTime;
                mDimTargetAlpha = 0;
                mDimDeltaPerMs = (-mDimCurrentAlpha) / WindowManagerService.DEFAULT_DIM_DURATION;
            }
        }

        boolean animating = mLastDimAnimTime != 0;
        if (animating) {
            mDimCurrentAlpha += mDimDeltaPerMs
                    * (currentTime-mLastDimAnimTime);
            if (displayFrozen) {
                // If the display is frozen, there is no reason to animate.
                animating = false;
            } else if (mDimDeltaPerMs > 0) {
                if (mDimCurrentAlpha > mDimTargetAlpha) {
                    animating = false;
                }
            } else if (mDimDeltaPerMs < 0) {
                if (mDimCurrentAlpha < mDimTargetAlpha) {
                    animating = false;
                }
            } else {
                animating = false;
            }

            // Do we need to continue animating?
            if (animating) {
                if (WindowManagerService.SHOW_TRANSACTIONS) Slog.i(WindowManagerService.TAG, "  DIM "
                        + mDimSurface + ": alpha=" + mDimCurrentAlpha);
                mLastDimAnimTime = currentTime;
                mDimSurface.setAlpha(mDimCurrentAlpha);
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

    public void kill() {
        if (mDimSurface != null) {
            mDimSurface.destroy();
            mDimSurface = null;
        }
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

    static class Parameters {
        final WindowStateAnimator mDimWinAnimator;
        final int mDimWidth;
        final int mDimHeight;
        final float mDimTarget;
        Parameters(final WindowStateAnimator dimWinAnimator, final int dimWidth,
                final int dimHeight, final float dimTarget) {
            mDimWinAnimator = dimWinAnimator;
            mDimWidth = dimWidth;
            mDimHeight = dimHeight;
            mDimTarget = dimTarget;
        }

        Parameters(Parameters o) {
            mDimWinAnimator = o.mDimWinAnimator;
            mDimWidth = o.mDimWidth;
            mDimHeight = o.mDimHeight;
            mDimTarget = o.mDimTarget;
        }

        public void printTo(String prefix, PrintWriter pw) {
            pw.print(prefix);
            pw.print("mDimWinAnimator="); pw.print(mDimWinAnimator.mWin.mAttrs.getTitle());
                    pw.print(" "); pw.print(mDimWidth); pw.print(" x ");
                    pw.print(mDimHeight);
            pw.print(" mDimTarget="); pw.println(mDimTarget);
        }
    }
}
