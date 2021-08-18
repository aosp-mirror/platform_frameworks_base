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
import com.android.keyguard.KeyguardVisibilityHelper;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.ViewController;

import com.google.common.util.concurrent.ListenableFuture;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Injectable controller for {@link CommunalHostView}.
 */
public class CommunalHostViewController extends ViewController<CommunalHostView> {
    private static final String TAG = "CommunalController";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String STATE_LIST_FORMAT = "[%s]";
    private static final AnimationProperties COMMUNAL_ANIMATION_PROPERTIES =
            new AnimationProperties().setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);

    private final Executor mMainExecutor;
    private final CommunalStateController mCommunalStateController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final KeyguardStateController mKeyguardStateController;
    private final StatusBarStateController mStatusBarStateController;
    private WeakReference<CommunalSource> mCurrentSource;
    private Optional<ShowRequest> mLastRequest = Optional.empty();
    private int mState;
    private float mQsExpansion;
    private float mShadeExpansion;

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
            STATE_DOZING | STATE_KEYGUARD_OCCLUDED;

    private final KeyguardVisibilityHelper mKeyguardVisibilityHelper;

    private ViewController<? extends View> mCommunalViewController;

    private static class ShowRequest {
        private boolean mShouldShow;
        private WeakReference<CommunalSource> mSource;

        ShowRequest(boolean shouldShow, WeakReference<CommunalSource> source) {
            mShouldShow = shouldShow;
            mSource = source;
        }

        CommunalSource getSource() {
            return mSource != null ? mSource.get() : null;
        }

        boolean shouldShow() {
            return mShouldShow;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ShowRequest)) return false;
            ShowRequest that = (ShowRequest) o;
            return mShouldShow == that.mShouldShow && Objects.equals(getSource(), that.getSource());
        }

        @Override
        public int hashCode() {
            return Objects.hash(mShouldShow, mSource);
        }
    }

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

                @Override
                public void onStateChanged(int newState) {
                    updateCommunalViewOccluded();
                }
            };

    @Inject
    protected CommunalHostViewController(@Main Executor mainExecutor,
            CommunalStateController communalStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            KeyguardStateController keyguardStateController,
            DozeParameters dozeParameters,
            UnlockedScreenOffAnimationController unlockedScreenOffAnimationController,
            StatusBarStateController statusBarStateController, CommunalHostView view) {
        super(view);
        mCommunalStateController = communalStateController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mMainExecutor = mainExecutor;
        mKeyguardStateController = keyguardStateController;
        mStatusBarStateController = statusBarStateController;
        mKeyguardVisibilityHelper = new KeyguardVisibilityHelper(mView, communalStateController,
                keyguardStateController, dozeParameters, unlockedScreenOffAnimationController,
                /* animateYPos= */ false, /* visibleOnCommunal= */ true);
    }

    /**
     * Set the visibility of the keyguard status view based on some new state.
     */
    public void setKeyguardStatusViewVisibility(
            int statusBarState,
            boolean keyguardFadingAway,
            boolean goingToFullShade,
            int oldStatusBarState) {
        mKeyguardVisibilityHelper.setViewVisibility(
                statusBarState, keyguardFadingAway, goingToFullShade, oldStatusBarState);
    }

    /**
     * Set keyguard status view alpha.
     */
    public void setAlpha(float alpha) {
        if (!mKeyguardVisibilityHelper.isVisibilityAnimating()) {
            mView.setAlpha(alpha);

            // Some communal view implementations, such as SurfaceViews, do not behave correctly
            // inheriting the alpha of their parent. Directly set child alpha here to work around
            // this.
            for (int i = mView.getChildCount() - 1; i >= 0; --i) {
                mView.getChildAt(i).setAlpha(alpha);
            }
        }
    }
    @Override
    public void onInit() {
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

        updateCommunalViewOccluded();
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
        final ShowRequest request = new ShowRequest(
                (mState & SHOW_COMMUNAL_VIEW_REQUIRED_STATES) == SHOW_COMMUNAL_VIEW_REQUIRED_STATES
                    && (mState & SHOW_COMMUNAL_VIEW_INVALID_STATES) == 0
                    && mCurrentSource != null,
                mCurrentSource);

        if (mLastRequest.isPresent() && Objects.equals(mLastRequest.get(), request)) {
            return;
        }

        mLastRequest = Optional.of(request);

        // Make sure all necessary states are present for showing communal and all invalid states
        // are absent
        mMainExecutor.execute(() -> {
            if (DEBUG) {
                Log.d(TAG, "showSource. currentSource:" + request.getSource());
            }

            if (request.shouldShow()) {
                mView.removeAllViews();

                // Make view visible.
                mView.setVisibility(View.VISIBLE);

                final Context context = mView.getContext();

                final ListenableFuture<CommunalSource.CommunalViewResult> listenableFuture =
                        request.getSource().requestCommunalView(context);

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
                mCommunalStateController.setCommunalViewShowing(false);
            }
        });
    }

    /**
     * Instructs {@link CommunalHostViewController} to display provided source.
     *
     * @param source The new {@link CommunalSource}, {@code null} if not set.
     */
    public void show(WeakReference<CommunalSource> source) {
        mCurrentSource = source;
        showSource();
    }

    /**
     * Update position of the view with an optional animation
     */
    public void updatePosition(int y, boolean animate) {
        PropertyAnimator.setProperty(mView, AnimatableProperty.Y, y, COMMUNAL_ANIMATION_PROPERTIES,
                animate);
    }

    /**
     * Invoked when the quick settings is expanded.
     * @param expansionFraction the percentage the QS shade has been expanded.
     */
    public void updateQsExpansion(float expansionFraction) {
        mQsExpansion = expansionFraction;
        updateCommunalViewOccluded();
    }

    /**
     * Invoked when the main shade is expanded.
     * @param shadeExpansion the percentage the main shade has expanded.
     */
    public void updateShadeExpansion(float shadeExpansion) {
        mShadeExpansion = shadeExpansion;
        updateCommunalViewOccluded();
    }

    private void updateCommunalViewOccluded() {
        final boolean bouncerShowing = (mState & STATE_BOUNCER_SHOWING) == STATE_BOUNCER_SHOWING;
        final int statusBarState = mStatusBarStateController.getState();
        final boolean shadeExpanded = statusBarState == StatusBarState.SHADE
                || statusBarState == StatusBarState.SHADE_LOCKED;

        mCommunalStateController.setCommunalViewOccluded(
                bouncerShowing || shadeExpanded || mQsExpansion > 0.0f || mShadeExpansion > 0.0f);
    }
}
