/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.policy;

import android.app.StatusBarManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Slog;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.Interpolator;
import android.view.animation.TranslateAnimation;

import com.android.internal.statusbar.IStatusBarService;

import static android.view.WindowManagerInternal.*;

/**
 * Implements status bar specific behavior.
 */
public class StatusBarController extends BarController {

    private static final long TRANSITION_DURATION = 120L;

    private final AppTransitionListener mAppTransitionListener
            = new AppTransitionListener() {

        @Override
        public void onAppTransitionPendingLocked() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        IStatusBarService statusbar = getStatusBarService();
                        if (statusbar != null) {
                            statusbar.appTransitionPending();
                        }
                    } catch (RemoteException e) {
                        Slog.e(mTag, "RemoteException when app transition is pending", e);
                        // re-acquire status bar service next time it is needed.
                        mStatusBarService = null;
                    }
                }
            });
        }

        @Override
        public void onAppTransitionStartingLocked(IBinder openToken, IBinder closeToken,
                final Animation openAnimation, final Animation closeAnimation) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        IStatusBarService statusbar = getStatusBarService();
                        if (statusbar != null) {
                            long startTime = calculateStatusBarTransitionStartTime(openAnimation,
                                    closeAnimation);
                            statusbar.appTransitionStarting(startTime, TRANSITION_DURATION);
                        }
                    } catch (RemoteException e) {
                        Slog.e(mTag, "RemoteException when app transition is starting", e);
                        // re-acquire status bar service next time it is needed.
                        mStatusBarService = null;
                    }
                }
            });
        }

        @Override
        public void onAppTransitionCancelledLocked() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        IStatusBarService statusbar = getStatusBarService();
                        if (statusbar != null) {
                            statusbar.appTransitionCancelled();
                        }
                    } catch (RemoteException e) {
                        Slog.e(mTag, "RemoteException when app transition is cancelled", e);
                        // re-acquire status bar service next time it is needed.
                        mStatusBarService = null;
                    }
                }
            });
        }
    };

    public StatusBarController() {
        super("StatusBar",
                View.STATUS_BAR_TRANSIENT,
                View.STATUS_BAR_UNHIDE,
                View.STATUS_BAR_TRANSLUCENT,
                StatusBarManager.WINDOW_STATUS_BAR,
                WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
    }

    public AppTransitionListener getAppTransitionListener() {
        return mAppTransitionListener;
    }

    /**
     * For a given app transition with {@code openAnimation} and {@code closeAnimation}, this
     * calculates the timings for the corresponding status bar transition.
     *
     * @return the desired start time of the status bar transition, in uptime millis
     */
    private long calculateStatusBarTransitionStartTime(Animation openAnimation,
            Animation closeAnimation) {
        if (openAnimation != null && closeAnimation != null) {
            TranslateAnimation openTranslateAnimation = findTranslateAnimation(openAnimation);
            TranslateAnimation closeTranslateAnimation = findTranslateAnimation(closeAnimation);
            if (openTranslateAnimation != null) {

                // Some interpolators are extremely quickly mostly finished, but not completely. For
                // our purposes, we need to find the fraction for which ther interpolator is mostly
                // there, and use that value for the calculation.
                float t = findAlmostThereFraction(openTranslateAnimation.getInterpolator());
                return SystemClock.uptimeMillis()
                        + openTranslateAnimation.getStartOffset()
                        + (long)(openTranslateAnimation.getDuration()*t) - TRANSITION_DURATION;
            } else if (closeTranslateAnimation != null) {
                return SystemClock.uptimeMillis();
            } else {
                return SystemClock.uptimeMillis();
            }
        } else {
            return SystemClock.uptimeMillis();
        }
    }

    /**
     * Tries to find a {@link TranslateAnimation} inside the {@code animation}.
     *
     * @return the found animation, {@code null} otherwise
     */
    private TranslateAnimation findTranslateAnimation(Animation animation) {
        if (animation instanceof TranslateAnimation) {
            return (TranslateAnimation) animation;
        } else if (animation instanceof AnimationSet) {
            AnimationSet set = (AnimationSet) animation;
            for (int i = 0; i < set.getAnimations().size(); i++) {
                Animation a = set.getAnimations().get(i);
                if (a instanceof TranslateAnimation) {
                    return (TranslateAnimation) a;
                }
            }
        }
        return null;
    }

    /**
     * Binary searches for a {@code t} such that there exists a {@code -0.01 < eps < 0.01} for which
     * {@code interpolator(t + eps) > 0.99}.
     */
    private float findAlmostThereFraction(Interpolator interpolator) {
        float val = 0.5f;
        float adj = 0.25f;
        while (adj >= 0.01f) {
            if (interpolator.getInterpolation(val) < 0.99f) {
                val += adj;
            } else {
                val -= adj;
            }
            adj /= 2;
        }
        return val;
    }
}
