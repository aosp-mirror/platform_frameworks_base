/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.dreams.touch;

import static com.android.systemui.dreams.complication.dagger.ComplicationHostViewModule.COMPLICATIONS_FADE_OUT_DELAY;
import static com.android.systemui.dreams.complication.dagger.ComplicationHostViewModule.COMPLICATIONS_RESTORE_TIMEOUT;

import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.DreamOverlayStateController;
import com.android.systemui.dreams.complication.Complication;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.touch.TouchInsetManager;
import com.android.systemui.util.concurrency.DelayableExecutor;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayDeque;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * {@link HideComplicationTouchHandler} is responsible for hiding the overlay complications from
 * visibility whenever there is touch interactions outside the overlay. The overlay interaction
 * scope includes touches to the complication plus any touch entry region for gestures as specified
 * to the {@link DreamOverlayTouchMonitor}.
 *
 * This {@link DreamTouchHandler} is also responsible for fading in the complications at the end
 * of the {@link com.android.systemui.dreams.touch.DreamTouchHandler.TouchSession}.
 */
public class HideComplicationTouchHandler implements DreamTouchHandler {
    private static final String TAG = "HideComplicationHandler";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final int mRestoreTimeout;
    private final int mFadeOutDelay;
    private final StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    private final DelayableExecutor mExecutor;
    private final DreamOverlayStateController mOverlayStateController;
    private final TouchInsetManager mTouchInsetManager;
    private final Complication.VisibilityController mVisibilityController;
    private boolean mHidden = false;
    @Nullable
    private Runnable mHiddenCallback;
    private final ArrayDeque<Runnable> mCancelCallbacks = new ArrayDeque<>();


    private final Runnable mRestoreComplications = new Runnable() {
        @Override
        public void run() {
            mVisibilityController.setVisibility(View.VISIBLE);
            mHidden = false;
        }
    };

    private final Runnable mHideComplications = new Runnable() {
        @Override
        public void run() {
            if (mOverlayStateController.areExitAnimationsRunning()) {
                // Avoid interfering with the exit animations.
                return;
            }
            mVisibilityController.setVisibility(View.INVISIBLE);
            mHidden = true;
            if (mHiddenCallback != null) {
                mHiddenCallback.run();
                mHiddenCallback = null;
            }
        }
    };

    @Inject
    HideComplicationTouchHandler(Complication.VisibilityController visibilityController,
            @Named(COMPLICATIONS_RESTORE_TIMEOUT) int restoreTimeout,
            @Named(COMPLICATIONS_FADE_OUT_DELAY) int fadeOutDelay,
            TouchInsetManager touchInsetManager,
            StatusBarKeyguardViewManager statusBarKeyguardViewManager,
            @Main DelayableExecutor executor,
            DreamOverlayStateController overlayStateController) {
        mVisibilityController = visibilityController;
        mRestoreTimeout = restoreTimeout;
        mFadeOutDelay = fadeOutDelay;
        mStatusBarKeyguardViewManager = statusBarKeyguardViewManager;
        mTouchInsetManager = touchInsetManager;
        mExecutor = executor;
        mOverlayStateController = overlayStateController;
    }

    @Override
    public void onSessionStart(TouchSession session) {
        if (DEBUG) {
            Log.d(TAG, "onSessionStart");
        }

        final boolean bouncerShowing = mStatusBarKeyguardViewManager.isBouncerShowing();

        // If other sessions are interested in this touch, do not fade out elements.
        if (session.getActiveSessionCount() > 1 || bouncerShowing
                || mOverlayStateController.areExitAnimationsRunning()) {
            if (DEBUG) {
                Log.d(TAG, "not fading. Active session count: " + session.getActiveSessionCount()
                        + ". Bouncer showing: " + bouncerShowing);
            }
            session.pop();
            return;
        }

        session.registerInputListener(ev -> {
            if (!(ev instanceof MotionEvent)) {
                return;
            }

            final MotionEvent motionEvent = (MotionEvent) ev;

            if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
                if (DEBUG) {
                    Log.d(TAG, "ACTION_DOWN received");
                }

                final ListenableFuture<Boolean> touchCheck = mTouchInsetManager
                        .checkWithinTouchRegion(Math.round(motionEvent.getX()),
                                Math.round(motionEvent.getY()));

                touchCheck.addListener(() -> {
                    try {
                        if (!touchCheck.get()) {
                            // Cancel all pending callbacks.
                            while (!mCancelCallbacks.isEmpty()) mCancelCallbacks.pop().run();
                            mCancelCallbacks.add(
                                    mExecutor.executeDelayed(
                                            mHideComplications, mFadeOutDelay));
                        } else {
                            // If a touch occurred inside the dream overlay touch insets, do not
                            // handle the touch.
                            session.pop();
                        }
                    } catch (InterruptedException | ExecutionException exception) {
                        Log.e(TAG, "could not check TouchInsetManager:" + exception);
                    }
                }, mExecutor);
            } else if (motionEvent.getAction() == MotionEvent.ACTION_CANCEL
                    || motionEvent.getAction() == MotionEvent.ACTION_UP) {
                // End session and initiate delayed reappearance of the complications.
                session.pop();
                runAfterHidden(() -> mCancelCallbacks.add(
                        mExecutor.executeDelayed(mRestoreComplications,
                                mRestoreTimeout)));
            }
        });
    }

    /**
     * Triggers a runnable after complications have been hidden. Will override any previously set
     * runnable currently waiting for hide to happen.
     */
    private void runAfterHidden(Runnable runnable) {
        mExecutor.execute(() -> {
            if (mHidden) {
                runnable.run();
            } else {
                mHiddenCallback = runnable;
            }
        });
    }
}
