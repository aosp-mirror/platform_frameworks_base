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
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.view.MotionEvent;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.util.concurrency.DelayableExecutor;

import javax.inject.Inject;

/**
 * Detects single and double taps on notifications.
 */
public class NotificationTapHelper {

    public static final long DOUBLE_TAP_TIMEOUT_MS = 1200;

    private final ActivationListener mActivationListener;
    private final DoubleTapListener mDoubleTapListener;
    private final FalsingManager mFalsingManager;
    private final DelayableExecutor mExecutor;
    private final SlideBackListener mSlideBackListener;

    private boolean mTrackTouch;
    private Runnable mTimeoutCancel;

    private NotificationTapHelper(FalsingManager falsingManager, DelayableExecutor executor,
            ActivationListener activationListener, DoubleTapListener doubleTapListener,
            SlideBackListener slideBackListener) {
        mFalsingManager = falsingManager;
        mExecutor = executor;
        mActivationListener = activationListener;
        mDoubleTapListener = doubleTapListener;
        mSlideBackListener = slideBackListener;
    }

    @VisibleForTesting
    boolean onTouchEvent(MotionEvent event) {
        return onTouchEvent(event, Integer.MAX_VALUE);
    }

    /** Call to have the helper process a touch event. */
    public boolean onTouchEvent(MotionEvent event, int maxTouchableHeight) {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                mTrackTouch = event.getY() <= maxTouchableHeight;
                break;
            case MotionEvent.ACTION_MOVE:
                if (mTrackTouch && !mFalsingManager.isSimpleTap()) {
                    makeInactive();
                    mTrackTouch = false;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                makeInactive();
                mTrackTouch = false;
                break;
            case MotionEvent.ACTION_UP:
                mTrackTouch = false;

                // 1) See if we have confidence that we can activate after a single tap.
                // 2) Else, see if it looks like a tap at all and check for a double-tap.
                if (!mFalsingManager.isFalseTap(FalsingManager.NO_PENALTY)) {
                    makeInactive();
                    return mDoubleTapListener.onDoubleTap();
                } else if (mFalsingManager.isSimpleTap()) {
                    if (mSlideBackListener != null && mSlideBackListener.onSlideBack()) {
                        return true;
                    }
                    if (mTimeoutCancel == null) {
                        // first tap
                        makeActive();
                        return true;
                    } else {
                        // second tap
                        makeInactive();
                        if (!mFalsingManager.isFalseDoubleTap()) {
                            return mDoubleTapListener.onDoubleTap();
                        }
                    }
                } else {
                    makeInactive();
                }
                break;
            default:
                break;
        }
        return mTrackTouch;
    }

    private void makeActive() {
        mTimeoutCancel = mExecutor.executeDelayed(this::makeInactive, DOUBLE_TAP_TIMEOUT_MS);
        mActivationListener.onActiveChanged(true);
    }

    private void makeInactive() {
        mActivationListener.onActiveChanged(false);
        if (mTimeoutCancel != null) {
            mTimeoutCancel.run();
            mTimeoutCancel = null;
        }
    }

    /** */
    @FunctionalInterface
    public interface ActivationListener {
        /** */
        void onActiveChanged(boolean active);
    }

    /** */
    @FunctionalInterface
    public interface DoubleTapListener {
        /** */
        boolean onDoubleTap();
    }

    /** */
    @FunctionalInterface
    public interface SlideBackListener {
        /** */
        boolean onSlideBack();
    }

    /**
     * Injectable factory that creates a {@link NotificationTapHelper}.
     */
    public static class Factory {
        private final FalsingManager mFalsingManager;
        private final DelayableExecutor mDelayableExecutor;

        @Inject
        public Factory(FalsingManager falsingManager, @Main DelayableExecutor delayableExecutor) {
            mFalsingManager = falsingManager;
            mDelayableExecutor = delayableExecutor;
        }

        /** Create a {@link NotificationTapHelper} */
        public NotificationTapHelper create(ActivationListener activationListener,
                DoubleTapListener doubleTapListener, SlideBackListener slideBackListener) {
            return new NotificationTapHelper(mFalsingManager, mDelayableExecutor,
                    activationListener, doubleTapListener, slideBackListener);
        }
    }
}
