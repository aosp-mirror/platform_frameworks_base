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

import android.annotation.IntDef;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.ViewController;

import com.google.common.util.concurrent.ListenableFuture;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Injectable controller for {@link CommunalHostView}.
 */
public class CommunalHostViewController extends ViewController<CommunalHostView> {
    private static final String TAG = "CommunalController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String STATE_LIST_FORMAT = "[%s]";

    private final Executor mMainExecutor;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final KeyguardStateController mKeyguardStateController;
    private final StatusBarStateController mStatusBarStateController;
    private WeakReference<CommunalSource> mLastSource;
    private int mState;

    @Retention(RetentionPolicy.RUNTIME)
    @IntDef({STATE_KEYGUARD_SHOWING, STATE_DOZING, STATE_BOUNCER_SHOWING, STATE_KEYGUARD_OCCLUDED})
    public @interface State {}

    private static final int STATE_KEYGUARD_SHOWING = 1 << 0;
    private static final int STATE_DOZING = 1 << 1;
    private static final int STATE_BOUNCER_SHOWING = 1 << 2;
    private static final int STATE_KEYGUARD_OCCLUDED = 1 << 3;

    // Only show communal view when keyguard is showing and not dozing.
    private static final int SHOW_COMMUNAL_VIEW_REQUIRED_STATES = STATE_KEYGUARD_SHOWING;
    private static final int SHOW_COMMUNAL_VIEW_INVALID_STATES =
            STATE_DOZING | STATE_BOUNCER_SHOWING | STATE_KEYGUARD_OCCLUDED;

    private ViewController<? extends View> mCommunalViewController;

    private KeyguardUpdateMonitorCallback mKeyguardUpdateCallback =
            new KeyguardUpdateMonitorCallback() {
                @Override
                public void onKeyguardBouncerChanged(boolean bouncer) {
                    if (DEBUG) {
                        Log.d(TAG, "onKeyguardBouncerChanged:" + bouncer);
                    }

                    setState(STATE_BOUNCER_SHOWING, bouncer);
                }

                @Override
                public void onKeyguardOccludedChanged(boolean occluded) {
                    if (DEBUG) {
                        Log.d(TAG, "onKeyguardOccludedChanged" + occluded);
                    }

                    setState(STATE_KEYGUARD_OCCLUDED, occluded);
                }
            };

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
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            KeyguardStateController keyguardStateController,
            StatusBarStateController statusBarStateController, CommunalHostView view) {
        super(view);
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mMainExecutor = mainExecutor;
        mKeyguardStateController = keyguardStateController;
        mStatusBarStateController = statusBarStateController;
    }

    @Override
    public void init() {
        super.init();

        setState(STATE_KEYGUARD_SHOWING, mKeyguardStateController.isShowing());
        setState(STATE_DOZING, mStatusBarStateController.isDozing());
    }

    @Override
    protected void onViewAttached() {
        mKeyguardStateController.addCallback(mKeyguardCallback);
        mStatusBarStateController.addCallback(mDozeCallback);
        mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateCallback);
    }

    @Override
    protected void onViewDetached() {
        mKeyguardStateController.removeCallback(mKeyguardCallback);
        mStatusBarStateController.removeCallback(mDozeCallback);
        mKeyguardUpdateMonitor.removeCallback(mKeyguardUpdateCallback);
    }

    private void setState(@State int stateFlag, boolean enabled) {
        final int existingState = mState;
        if (DEBUG) {
            Log.d(TAG, "setState flag:" + describeState(stateFlag) + " enabled:" + enabled);
        }

        if (enabled) {
            mState |= stateFlag;
        } else {
            mState &= ~stateFlag;
        }

        if (DEBUG) {
            Log.d(TAG, "updated state:" + describeState());
        }

        if (existingState != mState) {
            showSource();
        }
    }

    private String describeState(@State int stateFlag) {
        switch(stateFlag) {
            case STATE_DOZING:
                return "dozing";
            case STATE_BOUNCER_SHOWING:
                return "bouncer_showing";
            case STATE_KEYGUARD_SHOWING:
                return "keyguard_showing";
            default:
                return "UNDEFINED_STATE";
        }
    }

    private String describeState() {
        StringBuilder stringBuilder = new StringBuilder();

        if ((mState & STATE_KEYGUARD_SHOWING) == STATE_KEYGUARD_SHOWING) {
            stringBuilder.append(String.format(STATE_LIST_FORMAT,
                    describeState(STATE_KEYGUARD_SHOWING)));
        }
        if ((mState & STATE_DOZING) == STATE_DOZING) {
            stringBuilder.append(String.format(STATE_LIST_FORMAT,
                    describeState(STATE_DOZING)));
        }
        if ((mState & STATE_BOUNCER_SHOWING) == STATE_BOUNCER_SHOWING) {
            stringBuilder.append(String.format(STATE_LIST_FORMAT,
                    describeState(STATE_BOUNCER_SHOWING)));
        }

        return stringBuilder.toString();
    }

    private void showSource() {
        // Make sure all necessary states are present for showing communal and all invalid states
        // are absent
        mMainExecutor.execute(() -> {
            final CommunalSource currentSource = mLastSource != null ? mLastSource.get() : null;

            if (DEBUG) {
                Log.d(TAG, "showSource. currentSource:" + currentSource);
            }

            if ((mState & SHOW_COMMUNAL_VIEW_REQUIRED_STATES) == SHOW_COMMUNAL_VIEW_REQUIRED_STATES
                    && (mState & SHOW_COMMUNAL_VIEW_INVALID_STATES) == 0
                    && currentSource != null) {
                mView.removeAllViews();

                // Make view visible.
                mView.setVisibility(View.VISIBLE);

                final Context context = mView.getContext();

                final ListenableFuture<CommunalSource.CommunalViewResult> listenableFuture =
                        currentSource.requestCommunalView(context);

                if (listenableFuture == null) {
                    Log.e(TAG, "could not request communal view");
                    return;
                }

                listenableFuture.addListener(() -> {
                    try {
                        final CommunalSource.CommunalViewResult result = listenableFuture.get();
                        result.view.setLayoutParams(new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                        mView.addView(result.view);

                        mCommunalViewController = result.viewController;
                        mCommunalViewController.init();
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
}
