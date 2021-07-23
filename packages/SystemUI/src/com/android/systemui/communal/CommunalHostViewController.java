/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.communal;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.ViewController;

import com.google.common.util.concurrent.ListenableFuture;

import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Injectable controller for {@link CommunalHostView}.
 */
public class CommunalHostViewController extends ViewController<CommunalHostView> {
    private static final String TAG = "CommunalController";
    private static final boolean DEBUG = false;
    private static final AnimationProperties ANIMATION_PROPERTIES =
            new AnimationProperties().setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);

    private final Executor mMainExecutor;
    private final KeyguardStateController mKeyguardStateController;
    private final StatusBarStateController mStatusBarStateController;
    private WeakReference<CommunalSource> mLastSource;
    private int mState;

    private static final int STATE_KEYGUARD_SHOWING = 1 << 0;
    private static final int STATE_DOZING = 1 << 1;

    // Only show communal view when keyguard is showing and not dozing.
    private static final int SHOW_COMMUNAL_VIEW_REQUIRED_STATES = STATE_KEYGUARD_SHOWING;
    private static final int SHOW_COMMUNAL_VIEW_INVALID_STATES = STATE_DOZING;

    private KeyguardStateController.Callback mKeyguardCallback =
            new KeyguardStateController.Callback() {
                @Override
                public void onKeyguardShowingChanged() {
                    final boolean isShowing = mKeyguardStateController.isShowing();
                    if (DEBUG) {
                        Log.d(TAG, "setKeyguardShowing:" + isShowing);
                    }

                    setState(STATE_KEYGUARD_SHOWING, isShowing);
                }
            };

    private StatusBarStateController.StateListener mDozeCallback =
            new StatusBarStateController.StateListener() {
                @Override
                public void onDozingChanged(boolean isDozing) {
                    if (DEBUG) {
                        Log.d(TAG, "setDozing:" + isDozing);
                    }

                    setState(STATE_DOZING, isDozing);
                }
            };

    @Inject
    protected CommunalHostViewController(@Main Executor mainExecutor,
            KeyguardStateController keyguardStateController,
            StatusBarStateController statusBarStateController, CommunalHostView view) {
        super(view);
        mMainExecutor = mainExecutor;
        mKeyguardStateController = keyguardStateController;
        mStatusBarStateController = statusBarStateController;
        mState = 0;

        if (mKeyguardStateController.isShowing()) {
            mState |= STATE_KEYGUARD_SHOWING;
        }

        if (mStatusBarStateController.isDozing()) {
            mState |= STATE_DOZING;
        }

        mKeyguardStateController.addCallback(mKeyguardCallback);
        mStatusBarStateController.addCallback(mDozeCallback);
    }

    @Override
    protected void onViewAttached() {
        mKeyguardStateController.removeCallback(mKeyguardCallback);
        mKeyguardStateController.addCallback(mKeyguardCallback);
        mStatusBarStateController.removeCallback(mDozeCallback);
        mStatusBarStateController.addCallback(mDozeCallback);
    }

    @Override
    protected void onViewDetached() {
        mKeyguardStateController.removeCallback(mKeyguardCallback);
        mStatusBarStateController.removeCallback(mDozeCallback);
    }

    private void setState(int stateFlag, boolean enabled) {
        final int existingState = mState;
        if (DEBUG) {
            Log.d(TAG, "setState flag:" + stateFlag + " enabled:" + enabled);
        }

        if (enabled) {
            mState |= stateFlag;
        } else {
            mState &= ~stateFlag;
        }

        if (DEBUG) {
            Log.d(TAG, "updated state:" + mState);
        }

        if (existingState != mState) {
            showSource();
        }
    }

    private void showSource() {
        // Make sure all necessary states are present for showing communal and all invalid states
        // are absent
        mMainExecutor.execute(() -> {
            final CommunalSource currentSource = mLastSource != null ? mLastSource.get() : null;

            if ((mState & SHOW_COMMUNAL_VIEW_REQUIRED_STATES) == SHOW_COMMUNAL_VIEW_REQUIRED_STATES
                    && (mState & SHOW_COMMUNAL_VIEW_INVALID_STATES) == 0
                    && currentSource != null) {
                mView.removeAllViews();

                // Make view visible.
                mView.setVisibility(View.VISIBLE);

                final Context context = mView.getContext();

                final ListenableFuture<View> listenableFuture =
                        currentSource.requestCommunalView(context);

                if (listenableFuture == null) {
                    Log.e(TAG, "could not request communal view");
                    return;
                }

                listenableFuture.addListener(() -> {
                    try {
                        final View view = listenableFuture.get();
                        view.setLayoutParams(new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                        mView.addView(view);
                    } catch (Exception e) {
                        Log.e(TAG, "could not obtain communal view through callback:" + e);
                    }
                }, mMainExecutor);
            } else {
                mView.removeAllViews();
                mView.setVisibility(View.INVISIBLE);
            }
        });
    }

    /**
     * Instructs {@link CommunalHostViewController} to display provided source.
     *
     * @param source The new {@link CommunalSource}, {@code null} if not set.
     */
    public void show(WeakReference<CommunalSource> source) {
        mLastSource = source;
        showSource();
    }

    /**
     * Sets the Y position of the {@link CommunalHostView}
     *
     * @param y       Offset from parent top.
     * @param animate Whether the change should be animated.
     */
    public void updatePositionY(int y, boolean animate) {
        PropertyAnimator.setProperty(mView, AnimatableProperty.Y, y, ANIMATION_PROPERTIES, animate);
    }
}
