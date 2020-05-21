/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout.LayoutParams;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.R.id;
import com.android.systemui.media.MediaHost;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.customize.QSCustomizer;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.NotificationsQuickSettingsContainer;
import com.android.systemui.statusbar.policy.RemoteInputQuickSettingsDisabler;
import com.android.systemui.util.InjectionInflationController;
import com.android.systemui.util.LifecycleFragment;
import com.android.systemui.util.Utils;

import javax.inject.Inject;

public class QSFragment extends LifecycleFragment implements QS, CommandQueue.Callbacks,
        StatusBarStateController.StateListener {
    private static final String TAG = "QS";
    private static final boolean DEBUG = false;
    private static final String EXTRA_EXPANDED = "expanded";
    private static final String EXTRA_LISTENING = "listening";

    private final Rect mQsBounds = new Rect();
    private final StatusBarStateController mStatusBarStateController;
    private boolean mQsExpanded;
    private boolean mHeaderAnimating;
    private boolean mStackScrollerOverscrolling;

    private long mDelay;

    private QSAnimator mQSAnimator;
    private HeightListener mPanelView;
    protected QuickStatusBarHeader mHeader;
    private QSCustomizer mQSCustomizer;
    protected QSPanel mQSPanel;
    private QSDetail mQSDetail;
    private boolean mListening;
    private QSContainerImpl mContainer;
    private int mLayoutDirection;
    private QSFooter mFooter;
    private float mLastQSExpansion = -1;
    private boolean mQsDisabled;

    private final RemoteInputQuickSettingsDisabler mRemoteInputQuickSettingsDisabler;
    private final InjectionInflationController mInjectionInflater;
    private final QSContainerImplController.Builder mQSContainerImplControllerBuilder;
    private final QSTileHost mHost;
    private boolean mShowCollapsedOnKeyguard;
    private boolean mLastKeyguardAndExpanded;
    /**
     * The last received state from the controller. This should not be used directly to check if
     * we're on keyguard but use {@link #isKeyguardShowing()} instead since that is more accurate
     * during state transitions which often call into us.
     */
    private int mState;
    private QSContainerImplController mQSContainerImplController;
    private int[] mTmpLocation = new int[2];

    @Inject
    public QSFragment(RemoteInputQuickSettingsDisabler remoteInputQsDisabler,
            InjectionInflationController injectionInflater, QSTileHost qsTileHost,
            StatusBarStateController statusBarStateController, CommandQueue commandQueue,
            QSContainerImplController.Builder qsContainerImplControllerBuilder) {
        mRemoteInputQuickSettingsDisabler = remoteInputQsDisabler;
        mInjectionInflater = injectionInflater;
        mQSContainerImplControllerBuilder = qsContainerImplControllerBuilder;
        commandQueue.observe(getLifecycle(), this);
        mHost = qsTileHost;
        mStatusBarStateController = statusBarStateController;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
            Bundle savedInstanceState) {
        inflater = mInjectionInflater.injectable(
                inflater.cloneInContext(new ContextThemeWrapper(getContext(), R.style.qs_theme)));
        return inflater.inflate(R.layout.qs_panel, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mQSPanel = view.findViewById(R.id.quick_settings_panel);
        mQSDetail = view.findViewById(R.id.qs_detail);
        mHeader = view.findViewById(R.id.header);
        mFooter = view.findViewById(R.id.qs_footer);
        mContainer = view.findViewById(id.quick_settings_container);

        mQSContainerImplController = mQSContainerImplControllerBuilder
                .setQSContainerImpl((QSContainerImpl) view)
                .build();


        mQSDetail.setQsPanel(mQSPanel, mHeader, (View) mFooter);
        mQSAnimator = new QSAnimator(this,
                mHeader.findViewById(R.id.quick_qs_panel), mQSPanel);

        mQSCustomizer = view.findViewById(R.id.qs_customize);
        mQSCustomizer.setQs(this);
        if (savedInstanceState != null) {
            setExpanded(savedInstanceState.getBoolean(EXTRA_EXPANDED));
            setListening(savedInstanceState.getBoolean(EXTRA_LISTENING));
            setEditLocation(view);
            mQSCustomizer.restoreInstanceState(savedInstanceState);
            if (mQsExpanded) {
                mQSPanel.getTileLayout().restoreInstanceState(savedInstanceState);
            }
        }
        setHost(mHost);
        mStatusBarStateController.addCallback(this);
        onStateChanged(mStatusBarStateController.getState());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mStatusBarStateController.removeCallback(this);
        if (mListening) {
            setListening(false);
        }
        mQSCustomizer.setQs(null);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(EXTRA_EXPANDED, mQsExpanded);
        outState.putBoolean(EXTRA_LISTENING, mListening);
        mQSCustomizer.saveInstanceState(outState);
        if (mQsExpanded) {
            mQSPanel.getTileLayout().saveInstanceState(outState);
        }
    }

    @VisibleForTesting
    boolean isListening() {
        return mListening;
    }

    @VisibleForTesting
    boolean isExpanded() {
        return mQsExpanded;
    }

    @Override
    public View getHeader() {
        return mHeader;
    }

    @Override
    public void setHasNotifications(boolean hasNotifications) {
    }

    @Override
    public void setPanelView(HeightListener panelView) {
        mPanelView = panelView;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        setEditLocation(getView());
        if (newConfig.getLayoutDirection() != mLayoutDirection) {
            mLayoutDirection = newConfig.getLayoutDirection();
            if (mQSAnimator != null) {
                mQSAnimator.onRtlChanged();
            }
        }
    }

    private void setEditLocation(View view) {
        View edit = view.findViewById(android.R.id.edit);
        int[] loc = edit.getLocationOnScreen();
        int x = loc[0] + edit.getWidth() / 2;
        int y = loc[1] + edit.getHeight() / 2;
        mQSCustomizer.setEditLocation(x, y);
    }

    @Override
    public void setContainer(ViewGroup container) {
        if (container instanceof NotificationsQuickSettingsContainer) {
            mQSCustomizer.setContainer((NotificationsQuickSettingsContainer) container);
        }
    }

    @Override
    public boolean isCustomizing() {
        return mQSCustomizer.isCustomizing();
    }

    public void setHost(QSTileHost qsh) {
        mQSPanel.setHost(qsh, mQSCustomizer);
        mHeader.setQSPanel(mQSPanel);
        mFooter.setQSPanel(mQSPanel);
        mQSDetail.setHost(qsh);

        if (mQSAnimator != null) {
            mQSAnimator.setHost(qsh);
        }
    }

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        if (displayId != getContext().getDisplayId()) {
            return;
        }
        state2 = mRemoteInputQuickSettingsDisabler.adjustDisableFlags(state2);

        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        mContainer.disable(state1, state2, animate);
        mHeader.disable(state1, state2, animate);
        mFooter.disable(state1, state2, animate);
        updateQsState();
    }

    private void updateQsState() {
        final boolean expandVisually = mQsExpanded || mStackScrollerOverscrolling
                || mHeaderAnimating;
        mQSPanel.setExpanded(mQsExpanded);
        mQSDetail.setExpanded(mQsExpanded);
        boolean keyguardShowing = isKeyguardShowing();
        mHeader.setVisibility((mQsExpanded || !keyguardShowing || mHeaderAnimating
                || mShowCollapsedOnKeyguard)
                ? View.VISIBLE
                : View.INVISIBLE);
        mHeader.setExpanded((keyguardShowing && !mHeaderAnimating && !mShowCollapsedOnKeyguard)
                || (mQsExpanded && !mStackScrollerOverscrolling));
        mFooter.setVisibility(
                !mQsDisabled && (mQsExpanded || !keyguardShowing || mHeaderAnimating
                        || mShowCollapsedOnKeyguard)
                ? View.VISIBLE
                : View.INVISIBLE);
        mFooter.setExpanded((keyguardShowing && !mHeaderAnimating && !mShowCollapsedOnKeyguard)
                || (mQsExpanded && !mStackScrollerOverscrolling));
        mQSPanel.setVisibility(!mQsDisabled && expandVisually ? View.VISIBLE : View.INVISIBLE);
    }

    private boolean isKeyguardShowing() {
        // We want the freshest state here since otherwise we'll have some weirdness if earlier
        // listeners trigger updates
        return mStatusBarStateController.getState() == StatusBarState.KEYGUARD;
    }

    @Override
    public void setShowCollapsedOnKeyguard(boolean showCollapsedOnKeyguard) {
        if (showCollapsedOnKeyguard != mShowCollapsedOnKeyguard) {
            mShowCollapsedOnKeyguard = showCollapsedOnKeyguard;
            updateQsState();
            if (mQSAnimator != null) {
                mQSAnimator.setShowCollapsedOnKeyguard(showCollapsedOnKeyguard);
            }
            if (!showCollapsedOnKeyguard && isKeyguardShowing()) {
                setQsExpansion(mLastQSExpansion, 0);
            }
        }
    }

    public QSPanel getQsPanel() {
        return mQSPanel;
    }

    public QSCustomizer getCustomizer() {
        return mQSCustomizer;
    }

    @Override
    public boolean isShowingDetail() {
        return mQSPanel.isShowingCustomize() || mQSDetail.isShowingDetail();
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return isCustomizing();
    }

    @Override
    public void setHeaderClickable(boolean clickable) {
        if (DEBUG) Log.d(TAG, "setHeaderClickable " + clickable);
    }

    @Override
    public void setExpanded(boolean expanded) {
        if (DEBUG) Log.d(TAG, "setExpanded " + expanded);
        mQsExpanded = expanded;
        mQSPanel.setListening(mListening, mQsExpanded);
        updateQsState();
    }

    private void setKeyguardShowing(boolean keyguardShowing) {
        if (DEBUG) Log.d(TAG, "setKeyguardShowing " + keyguardShowing);
        mLastQSExpansion = -1;

        if (mQSAnimator != null) {
            mQSAnimator.setOnKeyguard(keyguardShowing);
        }

        mFooter.setKeyguardShowing(keyguardShowing);
        updateQsState();
    }

    @Override
    public void setOverscrolling(boolean stackScrollerOverscrolling) {
        if (DEBUG) Log.d(TAG, "setOverscrolling " + stackScrollerOverscrolling);
        mStackScrollerOverscrolling = stackScrollerOverscrolling;
        updateQsState();
    }

    @Override
    public void setListening(boolean listening) {
        if (DEBUG) Log.d(TAG, "setListening " + listening);
        mListening = listening;
        mQSContainerImplController.setListening(listening);
        mHeader.setListening(listening);
        mFooter.setListening(listening);
        mQSPanel.setListening(mListening, mQsExpanded);
    }

    @Override
    public void setHeaderListening(boolean listening) {
        mHeader.setListening(listening);
        mFooter.setListening(listening);
    }

    @Override
    public void setQsExpansion(float expansion, float headerTranslation) {
        if (DEBUG) Log.d(TAG, "setQSExpansion " + expansion + " " + headerTranslation);
        mContainer.setExpansion(expansion);
        final float translationScaleY = expansion - 1;
        boolean onKeyguardAndExpanded = isKeyguardShowing() && !mShowCollapsedOnKeyguard;
        if (!mHeaderAnimating && !headerWillBeAnimating()) {
            getView().setTranslationY(
                    onKeyguardAndExpanded
                            ? translationScaleY * mHeader.getHeight()
                            : headerTranslation);
        }
        if (expansion == mLastQSExpansion && mLastKeyguardAndExpanded == onKeyguardAndExpanded) {
            return;
        }
        mLastQSExpansion = expansion;
        mLastKeyguardAndExpanded = onKeyguardAndExpanded;

        boolean fullyExpanded = expansion == 1;
        int heightDiff = mQSPanel.getBottom() - mHeader.getBottom() + mHeader.getPaddingBottom();
        float panelTranslationY = translationScaleY * heightDiff;

        // Let the views animate their contents correctly by giving them the necessary context.
        mHeader.setExpansion(onKeyguardAndExpanded, expansion,
                panelTranslationY);
        mFooter.setExpansion(onKeyguardAndExpanded ? 1 : expansion);
        mQSPanel.getQsTileRevealController().setExpansion(expansion);
        mQSPanel.getTileLayout().setExpansion(expansion);
        mQSPanel.setTranslationY(translationScaleY * heightDiff);
        mQSDetail.setFullyExpanded(fullyExpanded);

        if (fullyExpanded) {
            // Always draw within the bounds of the view when fully expanded.
            mQSPanel.setClipBounds(null);
        } else {
            // Set bounds on the QS panel so it doesn't run over the header when animating.
            mQsBounds.top = (int) -mQSPanel.getTranslationY();
            mQsBounds.right = mQSPanel.getWidth();
            mQsBounds.bottom = mQSPanel.getHeight();
            mQSPanel.setClipBounds(mQsBounds);
        }

        if (mQSAnimator != null) {
            mQSAnimator.setPosition(expansion);
        }
        updateMediaPositions();
    }

    private void updateMediaPositions() {
        if (Utils.useQsMediaPlayer(getContext())) {
            mContainer.getLocationOnScreen(mTmpLocation);
            float absoluteBottomPosition = mTmpLocation[1] + mContainer.getHeight();
            pinToBottom(absoluteBottomPosition, mQSPanel.getMediaHost());
            pinToBottom(absoluteBottomPosition - mHeader.getPaddingBottom(),
                    mHeader.getHeaderQsPanel().getMediaHost());
        }
    }

    private void pinToBottom(float absoluteBottomPosition, MediaHost mediaHost) {
        View hostView = mediaHost.getHostView();
        if (mLastQSExpansion > 0) {
            ViewGroup.MarginLayoutParams params =
                    (ViewGroup.MarginLayoutParams) hostView.getLayoutParams();
            float targetPosition = absoluteBottomPosition - params.bottomMargin
                    - hostView.getHeight();
            float currentPosition = mediaHost.getCurrentState().getBoundsOnScreen().top
                    - hostView.getTranslationY();
            hostView.setTranslationY(targetPosition - currentPosition);
        } else {
            hostView.setTranslationY(0);
        }
    }

    private boolean headerWillBeAnimating() {
        return mState == StatusBarState.KEYGUARD && mShowCollapsedOnKeyguard
                && !isKeyguardShowing();
    }

    @Override
    public void animateHeaderSlidingIn(long delay) {
        if (DEBUG) Log.d(TAG, "animateHeaderSlidingIn");
        // If the QS is already expanded we don't need to slide in the header as it's already
        // visible.
        if (!mQsExpanded && getView().getTranslationY() != 0) {
            mHeaderAnimating = true;
            mDelay = delay;
            getView().getViewTreeObserver().addOnPreDrawListener(mStartHeaderSlidingIn);
        }
    }

    @Override
    public void animateHeaderSlidingOut() {
        if (DEBUG) Log.d(TAG, "animateHeaderSlidingOut");
        if (getView().getY() == -mHeader.getHeight()) {
            return;
        }
        mHeaderAnimating = true;
        getView().animate().y(-mHeader.getHeight())
                .setStartDelay(0)
                .setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (getView() != null) {
                            // The view could be destroyed before the animation completes when
                            // switching users.
                            getView().animate().setListener(null);
                        }
                        mHeaderAnimating = false;
                        updateQsState();
                    }
                })
                .start();
    }

    @Override
    public void setExpandClickListener(OnClickListener onClickListener) {
        mFooter.setExpandClickListener(onClickListener);
    }

    @Override
    public void closeDetail() {
        mQSPanel.closeDetail();
    }

    public void notifyCustomizeChanged() {
        // The customize state changed, so our height changed.
        mContainer.updateExpansion();
        mQSPanel.setVisibility(!mQSCustomizer.isCustomizing() ? View.VISIBLE : View.INVISIBLE);
        mFooter.setVisibility(!mQSCustomizer.isCustomizing() ? View.VISIBLE : View.INVISIBLE);
        // Let the panel know the position changed and it needs to update where notifications
        // and whatnot are.
        mPanelView.onQsHeightChanged();
    }

    /**
     * The height this view wants to be. This is different from {@link #getMeasuredHeight} such that
     * during closing the detail panel, this already returns the smaller height.
     */
    @Override
    public int getDesiredHeight() {
        if (mQSCustomizer.isCustomizing()) {
            return getView().getHeight();
        }
        if (mQSDetail.isClosingDetail()) {
            LayoutParams layoutParams = (LayoutParams) mQSPanel.getLayoutParams();
            int panelHeight = layoutParams.topMargin + layoutParams.bottomMargin +
                    + mQSPanel.getMeasuredHeight();
            return panelHeight + getView().getPaddingBottom();
        } else {
            return getView().getMeasuredHeight();
        }
    }

    @Override
    public void setHeightOverride(int desiredHeight) {
        mContainer.setHeightOverride(desiredHeight);
    }

    @Override
    public int getQsMinExpansionHeight() {
        return mHeader.getHeight();
    }

    @Override
    public void hideImmediately() {
        getView().animate().cancel();
        getView().setY(-mHeader.getHeight());
    }

    private final ViewTreeObserver.OnPreDrawListener mStartHeaderSlidingIn
            = new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            getView().getViewTreeObserver().removeOnPreDrawListener(this);
            getView().animate()
                    .translationY(0f)
                    .setStartDelay(mDelay)
                    .setDuration(StackStateAnimator.ANIMATION_DURATION_GO_TO_FULL_SHADE)
                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                    .setListener(mAnimateHeaderSlidingInListener)
                    .start();
            return true;
        }
    };

    private final Animator.AnimatorListener mAnimateHeaderSlidingInListener
            = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mHeaderAnimating = false;
            updateQsState();
        }
    };

    @Override
    public void onStateChanged(int newState) {
        mState = newState;
        setKeyguardShowing(newState == StatusBarState.KEYGUARD);
    }
}
