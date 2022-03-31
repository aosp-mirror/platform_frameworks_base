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

import static com.android.systemui.dreams.complication.dagger.ComplicationHostViewModule.COMPLICATIONS_RESTORE_TIMEOUT;

import android.os.Handler;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dreams.complication.Complication;
import com.android.systemui.touch.TouchInsetManager;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

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

    private final Complication.VisibilityController mVisibilityController;
    private final int mRestoreTimeout;
    private final Handler mHandler;
    private final Executor mExecutor;
    private final TouchInsetManager mTouchInsetManager;

    private final Runnable mRestoreComplications = new Runnable() {
        @Override
        public void run() {
            mVisibilityController.setVisibility(View.VISIBLE, true);
        }
    };

    @Inject
    HideComplicationTouchHandler(Complication.VisibilityController visibilityController,
            @Named(COMPLICATIONS_RESTORE_TIMEOUT) int restoreTimeout,
            TouchInsetManager touchInsetManager,
            @Main Executor executor,
            @Main Handler handler) {
        mVisibilityController = visibilityController;
        mRestoreTimeout = restoreTimeout;
        mHandler = handler;
        mTouchInsetManager = touchInsetManager;
        mExecutor = executor;
    }

    @Override
    public void onSessionStart(TouchSession session) {
        if (DEBUG) {
            Log.d(TAG, "onSessionStart");
        }

        // If other sessions are interested in this touch, do not fade out elements.
        if (session.getActiveSessionCount() > 1) {
            if (DEBUG) {
                Log.d(TAG, "multiple active touch sessions, not fading");
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
                            mHandler.removeCallbacks(mRestoreComplications);
                            mVisibilityController.setVisibility(View.INVISIBLE, true);
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
                mHandler.postDelayed(mRestoreComplications, mRestoreTimeout);
            }
        });
    }
}
