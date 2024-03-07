/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;

import static com.android.systemui.media.dagger.MediaModule.QS_PANEL;
import static com.android.systemui.media.dagger.MediaModule.QUICK_QS_PANEL;
import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.StatusBarState.SHADE_LOCKED;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.FloatRange;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.android.app.animation.Interpolators;
import com.android.keyguard.BouncerPanelExpansionCalculator;
import com.android.systemui.Dumpable;
import com.android.systemui.animation.ShadeInterpolation;
import com.android.systemui.compose.ComposeFacade;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.media.controls.ui.MediaHost;
import com.android.systemui.plugins.qs.QS;
import com.android.systemui.plugins.qs.QSContainerController;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.customize.QSCustomizerController;
import com.android.systemui.qs.dagger.QSComponent;
import com.android.systemui.qs.footer.ui.binder.FooterActionsViewBinder;
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.res.R;
import com.android.systemui.shade.transition.LargeScreenShadeInterpolator;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.disableflags.DisableFlagsLogger;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.policy.BrightnessMirrorController;
import com.android.systemui.statusbar.policy.RemoteInputQuickSettingsDisabler;
import com.android.systemui.util.Utils;

import dalvik.annotation.optimization.NeverCompile;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.function.Consumer;

import javax.inject.Inject;
import javax.inject.Named;

public class QSImpl implements QS, CommandQueue.Callbacks, StatusBarStateController.StateListener,
        Dumpable {
    private static final String TAG = "QS";
    private static final boolean DEBUG = false;
    private static final String EXTRA_EXPANDED = "expanded";
    private static final String EXTRA_LISTENING = "listening";
    private static final String EXTRA_VISIBLE = "visible";

    private final Rect mQsBounds = new Rect();
    private final SysuiStatusBarStateController mStatusBarStateController;
    private final KeyguardBypassController mBypassController;
    private boolean mQsExpanded;
    private boolean mHeaderAnimating;
    private boolean mStackScrollerOverscrolling;

    private QSAnimator mQSAnimator;
    @Nullable
    private HeightListener mPanelView;
    private QSSquishinessController mQSSquishinessController;
    protected QuickStatusBarHeader mHeader;
    protected NonInterceptingScrollView mQSPanelScrollView;
    private boolean mListening;
    private QSContainerImpl mContainer;
    private int mLayoutDirection;
    private QSFooter mFooter;
    private float mLastQSExpansion = -1;
    private float mLastPanelFraction;
    private float mSquishinessFraction = 1;
    private boolean mQsDisabled;
    private int[] mLocationTemp = new int[2];

    private final RemoteInputQuickSettingsDisabler mRemoteInputQuickSettingsDisabler;
    private final MediaHost mQsMediaHost;
    private final MediaHost mQqsMediaHost;
    private final QSDisableFlagsLogger mQsDisableFlagsLogger;
    private final LargeScreenShadeInterpolator mLargeScreenShadeInterpolator;
    private final FeatureFlags mFeatureFlags;
    private final QSLogger mLogger;
    private final FooterActionsController mFooterActionsController;
    private final FooterActionsViewModel.Factory mFooterActionsViewModelFactory;
    private final FooterActionsViewBinder mFooterActionsViewBinder;
    private final ListeningAndVisibilityLifecycleOwner mListeningAndVisibilityLifecycleOwner;
    private boolean mShowCollapsedOnKeyguard;
    private boolean mLastKeyguardAndExpanded;
    /**
     * The last received state from the controller. This should not be used directly to check if
     * we're on keyguard but use {@link #isKeyguardState()} instead since that is more accurate
     * during state transitions which often call into us.
     */
    private int mStatusBarState = -1;
    private QSContainerImplController mQSContainerImplController;
    private int[] mTmpLocation = new int[2];
    private int mLastViewHeight;
    private float mLastHeaderTranslation;
    private QSPanelController mQSPanelController;
    private QuickQSPanelController mQuickQSPanelController;
    private QSCustomizerController mQSCustomizerController;
    private FooterActionsViewModel mQSFooterActionsViewModel;
    @Nullable
    private ScrollListener mScrollListener;
    /**
     * When true, QS will translate from outside the screen. It will be clipped with parallax
     * otherwise.
     */
    private boolean mInSplitShade;

    /**
     * Are we currently transitioning from lockscreen to the full shade?
     */
    private boolean mTransitioningToFullShade;

    private final DumpManager mDumpManager;

    /**
     * Progress of pull down from the center of the lock screen.
     * @see com.android.systemui.statusbar.LockscreenShadeTransitionController
     */
    private float mLockscreenToShadeProgress;

    private boolean mOverScrolling;

    // Whether QQS or QS is visible. When in lockscreen, this is true if and only if QQS or QS is
    // visible;
    private boolean mQsVisible;

    private boolean mIsSmallScreen;

    private CommandQueue mCommandQueue;

    private View mRootView;
    private View mFooterActionsView;

    @Inject
    public QSImpl(RemoteInputQuickSettingsDisabler remoteInputQsDisabler,
            SysuiStatusBarStateController statusBarStateController, CommandQueue commandQueue,
            @Named(QS_PANEL) MediaHost qsMediaHost,
            @Named(QUICK_QS_PANEL) MediaHost qqsMediaHost,
            KeyguardBypassController keyguardBypassController,
            QSDisableFlagsLogger qsDisableFlagsLogger,
            DumpManager dumpManager, QSLogger qsLogger,
            FooterActionsController footerActionsController,
            FooterActionsViewModel.Factory footerActionsViewModelFactory,
            FooterActionsViewBinder footerActionsViewBinder,
            LargeScreenShadeInterpolator largeScreenShadeInterpolator,
            FeatureFlags featureFlags) {
        mRemoteInputQuickSettingsDisabler = remoteInputQsDisabler;
        mQsMediaHost = qsMediaHost;
        mQqsMediaHost = qqsMediaHost;
        mQsDisableFlagsLogger = qsDisableFlagsLogger;
        mLogger = qsLogger;
        mLargeScreenShadeInterpolator = largeScreenShadeInterpolator;
        mFeatureFlags = featureFlags;
        mCommandQueue = commandQueue;
        mBypassController = keyguardBypassController;
        mStatusBarStateController = statusBarStateController;
        mDumpManager = dumpManager;
        mFooterActionsController = footerActionsController;
        mFooterActionsViewModelFactory = footerActionsViewModelFactory;
        mFooterActionsViewBinder = footerActionsViewBinder;
        mListeningAndVisibilityLifecycleOwner = new ListeningAndVisibilityLifecycleOwner();
    }

    /**
     * This method will set up all the necessary fields. Methods from the implemented interfaces
     * should not be called before this method returns.
     */
    public void onComponentCreated(QSComponent qsComponent, @Nullable Bundle savedInstanceState) {
        mRootView = qsComponent.getRootView();

        mQSPanelController = qsComponent.getQSPanelController();
        mQuickQSPanelController = qsComponent.getQuickQSPanelController();

        mQSPanelController.init();
        mQuickQSPanelController.init();

        mQSFooterActionsViewModel = mFooterActionsViewModelFactory
                .create(mListeningAndVisibilityLifecycleOwner);
        bindFooterActionsView(mRootView);
        mFooterActionsController.init();

        mQSPanelScrollView = mRootView.findViewById(R.id.expanded_qs_scroll_view);
        mQSPanelScrollView.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    updateQsBounds();
                });
        mQSPanelScrollView.setOnScrollChangeListener(
                (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                    // Lazily update animators whenever the scrolling changes
                    mQSAnimator.requestAnimatorUpdate();
                    if (mScrollListener != null) {
                        mScrollListener.onQsPanelScrollChanged(scrollY);
                    }
                });
        mHeader = mRootView.findViewById(R.id.header);
        mFooter = qsComponent.getQSFooter();

        mQSContainerImplController = qsComponent.getQSContainerImplController();
        mQSContainerImplController.init();
        mContainer = mQSContainerImplController.getView();
        mDumpManager.registerDumpable(mContainer.getClass().getSimpleName(), mContainer);

        mQSAnimator = qsComponent.getQSAnimator();
        mQSSquishinessController = qsComponent.getQSSquishinessController();

        mQSCustomizerController = qsComponent.getQSCustomizerController();
        mQSCustomizerController.init();
        mQSCustomizerController.setQs(this);
        if (savedInstanceState != null) {
            setQsVisible(savedInstanceState.getBoolean(EXTRA_VISIBLE));
            setExpanded(savedInstanceState.getBoolean(EXTRA_EXPANDED));
            setListening(savedInstanceState.getBoolean(EXTRA_LISTENING));
            setEditLocation(mRootView);
            mQSCustomizerController.restoreInstanceState(savedInstanceState);
            if (mQsExpanded) {
                mQSPanelController.getTileLayout().restoreInstanceState(savedInstanceState);
            }
        }
        mStatusBarStateController.addCallback(this);
        onStateChanged(mStatusBarStateController.getState());
        mRootView.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    boolean sizeChanged = (oldTop - oldBottom) != (top - bottom);
                    if (sizeChanged) {
                        setQsExpansion(mLastQSExpansion, mLastPanelFraction,
                                mLastHeaderTranslation, mSquishinessFraction);
                    }
                });
        mQSPanelController.setUsingHorizontalLayoutChangeListener(
                () -> {
                    // The hostview may be faded out in the horizontal layout. Let's make sure to
                    // reset the alpha when switching layouts. This is fine since the animator will
                    // update the alpha if it's not supposed to be 1.0f
                    mQSPanelController.getMediaHost().getHostView().setAlpha(1.0f);
                    mQSAnimator.requestAnimatorUpdate();
                });

        // This will immediately call disable, so it needs to be added after setting up the fields.
        mCommandQueue.addCallback(this);
    }

    private void bindFooterActionsView(View root) {
        LinearLayout footerActionsView = root.findViewById(R.id.qs_footer_actions);

        if (!mFeatureFlags.isEnabled(Flags.COMPOSE_QS_FOOTER_ACTIONS)
                || !ComposeFacade.INSTANCE.isComposeAvailable()) {
            Log.d(TAG, "Binding the View implementation of the QS footer actions");
            mFooterActionsView = footerActionsView;
            mFooterActionsViewBinder.bind(footerActionsView, mQSFooterActionsViewModel,
                    mListeningAndVisibilityLifecycleOwner);
            return;
        }

        // Compose is available, so let's use the Compose implementation of the footer actions.
        Log.d(TAG, "Binding the Compose implementation of the QS footer actions");
        View composeView = ComposeFacade.INSTANCE.createFooterActionsView(root.getContext(),
                mQSFooterActionsViewModel, mListeningAndVisibilityLifecycleOwner);
        mFooterActionsView = composeView;

        // The id R.id.qs_footer_actions is used by QSContainerImpl to set the horizontal margin
        // to all views except for qs_footer_actions, so we set it to the Compose view.
        composeView.setId(R.id.qs_footer_actions);

        // Set this tag so that QSContainerImpl does not add horizontal paddings to this Compose
        // implementation of the footer actions. They will be set in Compose instead so that the
        // background fills the full screen width.
        composeView.setTag(R.id.tag_compose_qs_footer_actions, true);

        // Set the same elevation as the View implementation, otherwise the footer actions will be
        // drawn below the scroll view with QS grid and clicks won't get through on small devices
        // where there isn't enough vertical space to show all the tiles and the footer actions.
        composeView.setElevation(
                composeView.getContext().getResources().getDimension(R.dimen.qs_panel_elevation));

        // Replace the View by the Compose provided one.
        ViewGroup parent = (ViewGroup) footerActionsView.getParent();
        ViewGroup.LayoutParams layoutParams = footerActionsView.getLayoutParams();
        int index = parent.indexOfChild(footerActionsView);
        parent.removeViewAt(index);
        parent.addView(composeView, index, layoutParams);
    }

    @Override
    public void setScrollListener(ScrollListener listener) {
        mScrollListener = listener;
    }

    public void onCreate(Bundle savedInstanceState) {
        mDumpManager.registerDumpable(getClass().getSimpleName(), this);
    }

    public void onDestroy() {
        mCommandQueue.removeCallback(this);
        mStatusBarStateController.removeCallback(this);
        mQSPanelController.destroy();
        mQuickQSPanelController.destroy();
        if (mListening) {
            setListening(false);
        }
        if (mQSCustomizerController != null) {
            mQSCustomizerController.setQs(null);
            mQSCustomizerController.setContainerController(null);
        }
        mScrollListener = null;
        if (mContainer != null) {
            mDumpManager.unregisterDumpable(mContainer.getClass().getSimpleName());
        }
        mDumpManager.unregisterDumpable(getClass().getSimpleName());
        mListeningAndVisibilityLifecycleOwner.destroy();
        ViewGroup parent = ((ViewGroup) getView().getParent());
        if (parent != null) {
            parent.removeView(getView());
        }
    }

    public void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(EXTRA_EXPANDED, mQsExpanded);
        outState.putBoolean(EXTRA_LISTENING, mListening);
        outState.putBoolean(EXTRA_VISIBLE, mQsVisible);
        if (mQSCustomizerController != null) {
            mQSCustomizerController.saveInstanceState(outState);
        }
        if (mQsExpanded) {
            mQSPanelController.getTileLayout().saveInstanceState(outState);
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

    @VisibleForTesting
    boolean isQsVisible() {
        return mQsVisible;
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

    public void onConfigurationChanged(Configuration newConfig) {
        setEditLocation(getView());
        if (newConfig.getLayoutDirection() != mLayoutDirection) {
            mLayoutDirection = newConfig.getLayoutDirection();
            if (mQSAnimator != null) {
                mQSAnimator.onRtlChanged();
            }
        }
        updateQsState();
    }

    @Override
    public void setFancyClipping(int leftInset, int top, int rightInset, int bottom,
            int cornerRadius, boolean visible, boolean fullWidth) {
        if (getView() instanceof QSContainerImpl) {
            ((QSContainerImpl) getView()).setFancyClipping(leftInset, top, rightInset, bottom,
                    cornerRadius, visible, fullWidth);
        }
    }

    @Override
    public boolean isFullyCollapsed() {
        return mLastQSExpansion == 0.0f || mLastQSExpansion == -1;
    }

    @Override
    public void setCollapsedMediaVisibilityChangedListener(Consumer<Boolean> listener) {
        mQuickQSPanelController.setMediaVisibilityChangedListener(listener);
    }

    private void setEditLocation(View view) {
        View edit = view.findViewById(android.R.id.edit);
        int[] loc = edit.getLocationOnScreen();
        int x = loc[0] + edit.getWidth() / 2;
        int y = loc[1] + edit.getHeight() / 2;
        mQSCustomizerController.setEditLocation(x, y);
    }

    @Override
    public void setContainerController(QSContainerController controller) {
        mQSCustomizerController.setContainerController(controller);
    }

    @Override
    public boolean isCustomizing() {
        return mQSCustomizerController.isCustomizing();
    }

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        if (displayId != getContext().getDisplayId()) {
            return;
        }
        int state2BeforeAdjustment = state2;
        state2 = mRemoteInputQuickSettingsDisabler.adjustDisableFlags(state2);

        mQsDisableFlagsLogger.logDisableFlagChange(
                /* new= */ new DisableFlagsLogger.DisableState(state1, state2BeforeAdjustment),
                /* newAfterLocalModification= */ new DisableFlagsLogger.DisableState(state1, state2)
        );

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
        mQSPanelController.setExpanded(mQsExpanded);
        boolean keyguardShowing = isKeyguardState();
        mHeader.setVisibility((mQsExpanded || !keyguardShowing || mHeaderAnimating
                || mShowCollapsedOnKeyguard)
                ? View.VISIBLE
                : View.INVISIBLE);
        mHeader.setExpanded((keyguardShowing && !mHeaderAnimating && !mShowCollapsedOnKeyguard)
                || (mQsExpanded && !mStackScrollerOverscrolling), mQuickQSPanelController);
        boolean qsPanelVisible = !mQsDisabled && expandVisually;
        boolean footerVisible = qsPanelVisible && (mQsExpanded || !keyguardShowing
                || mHeaderAnimating || mShowCollapsedOnKeyguard);
        mFooter.setVisibility(footerVisible ? View.VISIBLE : View.INVISIBLE);
        mFooterActionsView.setVisibility(footerVisible ? View.VISIBLE : View.INVISIBLE);
        mFooter.setExpanded((keyguardShowing && !mHeaderAnimating && !mShowCollapsedOnKeyguard)
                || (mQsExpanded && !mStackScrollerOverscrolling));
        mQSPanelController.setVisibility(qsPanelVisible ? View.VISIBLE : View.INVISIBLE);
        if (DEBUG) {
            Log.d(TAG, "Footer: " + footerVisible + ", QS Panel: " + qsPanelVisible);
        }
    }

    private boolean isKeyguardState() {
        // We want the freshest state here since otherwise we'll have some weirdness if earlier
        // listeners trigger updates
        return mStatusBarStateController.getCurrentOrUpcomingState() == KEYGUARD;
    }

    private void updateShowCollapsedOnKeyguard() {
        boolean showCollapsed = mBypassController.getBypassEnabled()
                || (mTransitioningToFullShade && !mInSplitShade);
        if (showCollapsed != mShowCollapsedOnKeyguard) {
            mShowCollapsedOnKeyguard = showCollapsed;
            updateQsState();
            if (mQSAnimator != null) {
                mQSAnimator.setShowCollapsedOnKeyguard(showCollapsed);
            }
            if (!showCollapsed && isKeyguardState()) {
                setQsExpansion(mLastQSExpansion, mLastPanelFraction, 0,
                        mSquishinessFraction);
            }
        }
    }

    public QSPanelController getQSPanelController() {
        return mQSPanelController;
    }

    public void setBrightnessMirrorController(
            BrightnessMirrorController brightnessMirrorController) {
        mQSPanelController.setBrightnessMirror(brightnessMirrorController);
    }

    @Override
    public boolean isShowingDetail() {
        return mQSCustomizerController.isCustomizing();
    }

    @Override
    public void setHeaderClickable(boolean clickable) {
        if (DEBUG) Log.d(TAG, "setHeaderClickable " + clickable);
    }

    @Override
    public void setExpanded(boolean expanded) {
        if (DEBUG) Log.d(TAG, "setExpanded " + expanded);
        mQsExpanded = expanded;
        if (mInSplitShade && mQsExpanded) {
            // in split shade QS is expanded immediately when shade expansion starts and then we
            // also need to listen to changes - otherwise QS is updated only once its fully expanded
            setListening(true);
        } else {
            updateQsPanelControllerListening();
        }
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
        mQSContainerImplController.setListening(listening && mQsVisible);
        mListeningAndVisibilityLifecycleOwner.updateState();
        updateQsPanelControllerListening();
    }

    private void updateQsPanelControllerListening() {
        mQSPanelController.setListening(mListening && mQsVisible, mQsExpanded);
    }

    @Override
    public void setQsVisible(boolean visible) {
        if (DEBUG) Log.d(TAG, "setQsVisible " + visible);
        mQsVisible = visible;
        setListening(mListening);
        mListeningAndVisibilityLifecycleOwner.updateState();
    }

    @Override
    public void setHeaderListening(boolean listening) {
        mQSContainerImplController.setListening(listening);
    }

    @Override
    public void setInSplitShade(boolean inSplitShade) {
        mInSplitShade = inSplitShade;
        updateShowCollapsedOnKeyguard();
        updateQsState();
    }

    @Override
    public void setTransitionToFullShadeProgress(
            boolean isTransitioningToFullShade,
            @FloatRange(from = 0.0, to = 1.0) float qsTransitionFraction,
            @FloatRange(from = 0.0, to = 1.0) float qsSquishinessFraction) {
        if (isTransitioningToFullShade != mTransitioningToFullShade) {
            mTransitioningToFullShade = isTransitioningToFullShade;
            updateShowCollapsedOnKeyguard();
        }
        mLockscreenToShadeProgress = qsTransitionFraction;
        setQsExpansion(mLastQSExpansion, mLastPanelFraction, mLastHeaderTranslation,
                isTransitioningToFullShade ? qsSquishinessFraction : mSquishinessFraction);
    }

    @Override
    public void setOverScrollAmount(int overScrollAmount) {
        mOverScrolling = overScrollAmount != 0;
        View view = getView();
        if (view != null) {
            view.setTranslationY(overScrollAmount);
        }
    }

    @Override
    public int getHeightDiff() {
        return mQSPanelScrollView.getBottom() - mHeader.getBottom()
                + mHeader.getPaddingBottom();
    }

    @Override
    public void setIsNotificationPanelFullWidth(boolean isFullWidth) {
        mIsSmallScreen = isFullWidth;
    }

    @Override
    public void setQsExpansion(float expansion, float panelExpansionFraction,
            float proposedTranslation, float squishinessFraction) {
        float headerTranslation = mTransitioningToFullShade ? 0 : proposedTranslation;
        float alphaProgress = calculateAlphaProgress(panelExpansionFraction);
        setAlphaAnimationProgress(alphaProgress);
        mContainer.setExpansion(expansion);
        final float translationScaleY = (mInSplitShade
                ? 1 : QSAnimator.SHORT_PARALLAX_AMOUNT) * (expansion - 1);
        boolean onKeyguard = isKeyguardState();
        boolean onKeyguardAndExpanded = onKeyguard && !mShowCollapsedOnKeyguard;
        if (!mHeaderAnimating && !headerWillBeAnimating() && !mOverScrolling) {
            getView().setTranslationY(
                    onKeyguardAndExpanded
                            ? translationScaleY * mHeader.getHeight()
                            : headerTranslation);
        }
        int currentHeight = getView().getHeight();
        if (expansion == mLastQSExpansion
                && mLastKeyguardAndExpanded == onKeyguardAndExpanded
                && mLastViewHeight == currentHeight
                && mLastHeaderTranslation == headerTranslation
                && mSquishinessFraction == squishinessFraction
                && mLastPanelFraction == panelExpansionFraction) {
            return;
        }
        mLastHeaderTranslation = headerTranslation;
        mLastPanelFraction = panelExpansionFraction;
        mSquishinessFraction = squishinessFraction;
        mLastQSExpansion = expansion;
        mLastKeyguardAndExpanded = onKeyguardAndExpanded;
        mLastViewHeight = currentHeight;

        boolean fullyExpanded = expansion == 1;
        boolean fullyCollapsed = expansion == 0.0f;
        int heightDiff = getHeightDiff();
        float panelTranslationY = translationScaleY * heightDiff;

        if (expansion < 1 && expansion > 0.99) {
            if (mQuickQSPanelController.switchTileLayout(false)) {
                mHeader.updateResources();
            }
        }
        mQSPanelController.setIsOnKeyguard(onKeyguard);
        mFooter.setExpansion(onKeyguardAndExpanded ? 1 : expansion);
        float footerActionsExpansion =
                onKeyguardAndExpanded ? 1 : mInSplitShade ? alphaProgress : expansion;
        mQSFooterActionsViewModel.onQuickSettingsExpansionChanged(footerActionsExpansion,
                mInSplitShade);
        mQSPanelController.setRevealExpansion(expansion);
        mQSPanelController.getTileLayout().setExpansion(expansion, proposedTranslation);
        mQuickQSPanelController.getTileLayout().setExpansion(expansion, proposedTranslation);

        float qsScrollViewTranslation =
                onKeyguard && !mShowCollapsedOnKeyguard ? panelTranslationY : 0;
        mQSPanelScrollView.setTranslationY(qsScrollViewTranslation);

        if (fullyCollapsed) {
            mQSPanelScrollView.setScrollY(0);
        }

        if (!fullyExpanded) {
            // Set bounds on the QS panel so it doesn't run over the header when animating.
            mQsBounds.top = (int) -mQSPanelScrollView.getTranslationY();
            mQsBounds.right = mQSPanelScrollView.getWidth();
            mQsBounds.bottom = mQSPanelScrollView.getHeight();
        }
        updateQsBounds();

        if (mQSSquishinessController != null) {
            mQSSquishinessController.setSquishiness(mSquishinessFraction);
        }
        if (mQSAnimator != null) {
            mQSAnimator.setPosition(expansion);
        }
        if (!mInSplitShade
                || mStatusBarStateController.getState() == KEYGUARD
                || mStatusBarStateController.getState() == SHADE_LOCKED) {
            // At beginning, state is 0 and will apply wrong squishiness to MediaHost in lockscreen
            // and media player expect no change by squishiness in lock screen shade. Don't bother
            // squishing mQsMediaHost when not in split shade to prevent problems with stale state.
            mQsMediaHost.setSquishFraction(1.0F);
        } else {
            mQsMediaHost.setSquishFraction(mSquishinessFraction);
        }
        updateMediaPositions();
    }

    private void setAlphaAnimationProgress(float progress) {
        final View view = getView();
        if (progress == 0 && view.getVisibility() != View.INVISIBLE) {
            mLogger.logVisibility("QS fragment", View.INVISIBLE);
            view.setVisibility(View.INVISIBLE);
        } else if (progress > 0 && view.getVisibility() != View.VISIBLE) {
            mLogger.logVisibility("QS fragment", View.VISIBLE);
            view.setVisibility((View.VISIBLE));
        }
        view.setAlpha(interpolateAlphaAnimationProgress(progress));
    }

    private float calculateAlphaProgress(float panelExpansionFraction) {
        if (mIsSmallScreen) {
            // Small screens. QS alpha is not animated.
            return 1;
        }
        if (mInSplitShade) {
            // Large screens in landscape.
            // Need to check upcoming state as for unlocked -> AOD transition current state is
            // not updated yet, but we're transitioning and UI should already follow KEYGUARD state
            if (mTransitioningToFullShade
                    || mStatusBarStateController.getCurrentOrUpcomingState() == KEYGUARD) {
                // Always use "mFullShadeProgress" on keyguard, because
                // "panelExpansionFractions" is always 1 on keyguard split shade.
                return mLockscreenToShadeProgress;
            } else {
                return panelExpansionFraction;
            }
        }
        // Large screens in portrait.
        if (mTransitioningToFullShade) {
            // Only use this value during the standard lock screen shade expansion. During the
            // "quick" expansion from top, this value is 0.
            return mLockscreenToShadeProgress;
        } else {
            return panelExpansionFraction;
        }
    }

    private float interpolateAlphaAnimationProgress(float progress) {
        if (mQSPanelController.isBouncerInTransit()) {
            return BouncerPanelExpansionCalculator.aboutToShowBouncerProgress(progress);
        }
        if (isKeyguardState()) {
            // Alpha progress should be linear on lockscreen shade expansion.
            return progress;
        }
        if (mIsSmallScreen) {
            return ShadeInterpolation.getContentAlpha(progress);
        } else {
            return mLargeScreenShadeInterpolator.getQsAlpha(progress);
        }
    }

    @VisibleForTesting
    void updateQsBounds() {
        if (mLastQSExpansion == 1.0f) {
            // Fully expanded, let's set the layout bounds as clip bounds. This is necessary because
            // it's a scrollview and otherwise wouldn't be clipped. However, we set the horizontal
            // bounds so the pages go to the ends of QSContainerImpl (most cases) or its parent
            // (large screen portrait)
            int sideMargin = getResources().getDimensionPixelSize(
                    R.dimen.qs_tiles_page_horizontal_margin) * 2;
            mQsBounds.set(-sideMargin, 0, mQSPanelScrollView.getWidth() + sideMargin,
                    mQSPanelScrollView.getHeight());
        }
        mQSPanelScrollView.setClipBounds(mQsBounds);

        mQSPanelScrollView.getLocationOnScreen(mLocationTemp);
        int left = mLocationTemp[0];
        int top = mLocationTemp[1];
        mQsMediaHost.getCurrentClipping().set(left, top,
                left + getView().getMeasuredWidth(),
                top + mQSPanelScrollView.getMeasuredHeight()
                        - mQSPanelController.getPaddingBottom());
    }

    private void updateMediaPositions() {
        if (Utils.useQsMediaPlayer(getContext())) {
            View hostView = mQsMediaHost.getHostView();
            // Make sure the media appears a bit from the top to make it look nicer
            if (mLastQSExpansion > 0 && !isKeyguardState() && !mQqsMediaHost.getVisible()
                    && !mQSPanelController.shouldUseHorizontalLayout() && !mInSplitShade) {
                float interpolation = 1.0f - mLastQSExpansion;
                interpolation = Interpolators.ACCELERATE.getInterpolation(interpolation);
                float translationY = -hostView.getHeight() * 1.3f * interpolation;
                hostView.setTranslationY(translationY);
            } else {
                hostView.setTranslationY(0);
            }
        }
    }

    private boolean headerWillBeAnimating() {
        return mStatusBarState == KEYGUARD && mShowCollapsedOnKeyguard && !isKeyguardState();
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
    public void setCollapseExpandAction(Runnable action) {
        mQSPanelController.setCollapseExpandAction(action);
        mQuickQSPanelController.setCollapseExpandAction(action);
    }

    @Override
    public void closeDetail() {
        mQSPanelController.closeDetail();
    }

    @Override
    public void closeCustomizer() {
        mQSCustomizerController.hide();
    }

    public void closeCustomizerImmediately() {
        mQSCustomizerController.hide(false);
    }

    public void notifyCustomizeChanged() {
        // The customize state changed, so our height changed.
        mContainer.updateExpansion();
        boolean customizing = isCustomizing();
        mQSPanelScrollView.setVisibility(!customizing ? View.VISIBLE : View.INVISIBLE);
        mFooter.setVisibility(!customizing ? View.VISIBLE : View.INVISIBLE);
        mFooterActionsView.setVisibility(!customizing ? View.VISIBLE : View.INVISIBLE);
        mHeader.setVisibility(!customizing ? View.VISIBLE : View.INVISIBLE);
        // Let the panel know the position changed and it needs to update where notifications
        // and whatnot are.
        if (mPanelView != null) {
            mPanelView.onQsHeightChanged();
        }
    }

    /**
     * The height this view wants to be. This is different from {@link View#getMeasuredHeight} such
     * that during closing the detail panel, this already returns the smaller height.
     */
    @Override
    public int getDesiredHeight() {
        if (mQSCustomizerController.isCustomizing()) {
            return getView().getHeight();
        }
        return getView().getMeasuredHeight();
    }

    @Override
    public void setHeightOverride(int desiredHeight) {
        mContainer.setHeightOverride(desiredHeight);
    }

    @Override
    public int getQsMinExpansionHeight() {
        if (mInSplitShade) {
            return getQsMinExpansionHeightForSplitShade();
        }
        return mHeader.getHeight();
    }

    /**
     * Returns the min expansion height for split shade.
     *
     * On split shade, QS is always expanded and goes from the top of the screen to the bottom of
     * the QS container.
     */
    private int getQsMinExpansionHeightForSplitShade() {
        getView().getLocationOnScreen(mLocationTemp);
        int top = mLocationTemp[1];
        // We want to get the original top position, so we subtract any translation currently set.
        int originalTop = (int) (top - getView().getTranslationY());
        // On split shade the QS view doesn't start at the top of the screen, so we need to add the
        // top margin.
        return originalTop + getView().getHeight();
    }

    @Override
    public void hideImmediately() {
        getView().animate().cancel();
        getView().setY(-getQsMinExpansionHeight());
    }

    @Override
    public void onUpcomingStateChanged(int upcomingState) {
        if (upcomingState == KEYGUARD) {
            // refresh state of QS as soon as possible - while it's still upcoming - so in case of
            // transition to KEYGUARD (e.g. from unlocked to AOD) all objects are aware they should
            // already behave like on keyguard. Otherwise we might be doing extra work,
            // e.g. QSAnimator making QS visible and then quickly invisible
            onStateChanged(upcomingState);
        }
    }

    @Override
    public void onStateChanged(int newState) {
        if (newState == mStatusBarState) {
            return;
        }
        mStatusBarState = newState;
        setKeyguardShowing(newState == KEYGUARD);
        updateShowCollapsedOnKeyguard();
    }

    @VisibleForTesting
    public ListeningAndVisibilityLifecycleOwner getListeningAndVisibilityLifecycleOwner() {
        return mListeningAndVisibilityLifecycleOwner;
    }

    @NeverCompile
    @Override
    public void dump(PrintWriter pw, String[] args) {
        IndentingPrintWriter indentingPw = new IndentingPrintWriter(pw, /* singleIndent= */ "  ");
        indentingPw.println("QSImpl:");
        indentingPw.increaseIndent();
        indentingPw.println("mQsBounds: " + mQsBounds);
        indentingPw.println("mQsExpanded: " + mQsExpanded);
        indentingPw.println("mHeaderAnimating: " + mHeaderAnimating);
        indentingPw.println("mStackScrollerOverscrolling: " + mStackScrollerOverscrolling);
        indentingPw.println("mListening: " + mListening);
        indentingPw.println("mQsVisible: " + mQsVisible);
        indentingPw.println("mLayoutDirection: " + mLayoutDirection);
        indentingPw.println("mLastQSExpansion: " + mLastQSExpansion);
        indentingPw.println("mLastPanelFraction: " + mLastPanelFraction);
        indentingPw.println("mSquishinessFraction: " + mSquishinessFraction);
        indentingPw.println("mQsDisabled: " + mQsDisabled);
        indentingPw.println("mTemp: " + Arrays.toString(mLocationTemp));
        indentingPw.println("mShowCollapsedOnKeyguard: " + mShowCollapsedOnKeyguard);
        indentingPw.println("mLastKeyguardAndExpanded: " + mLastKeyguardAndExpanded);
        indentingPw.println("mStatusBarState: " + StatusBarState.toString(mStatusBarState));
        indentingPw.println("mTmpLocation: " + Arrays.toString(mTmpLocation));
        indentingPw.println("mLastViewHeight: " + mLastViewHeight);
        indentingPw.println("mLastHeaderTranslation: " + mLastHeaderTranslation);
        indentingPw.println("mInSplitShade: " + mInSplitShade);
        indentingPw.println("mTransitioningToFullShade: " + mTransitioningToFullShade);
        indentingPw.println("mLockscreenToShadeProgress: " + mLockscreenToShadeProgress);
        indentingPw.println("mOverScrolling: " + mOverScrolling);
        indentingPw.println("isCustomizing: " + mQSCustomizerController.isCustomizing());
        View view = getView();
        if (view != null) {
            indentingPw.println("top: " + view.getTop());
            indentingPw.println("y: " + view.getY());
            indentingPw.println("translationY: " + view.getTranslationY());
            indentingPw.println("alpha: " + view.getAlpha());
            indentingPw.println("height: " + view.getHeight());
            indentingPw.println("measuredHeight: " + view.getMeasuredHeight());
            indentingPw.println("clipBounds: " + view.getClipBounds());
        } else {
            indentingPw.println("getView(): null");
        }
        QuickStatusBarHeader header = mHeader;
        if (header != null) {
            indentingPw.println("headerHeight: " + header.getHeight());
            indentingPw.println("Header visibility: " + visibilityToString(header.getVisibility()));
        } else {
            indentingPw.println("mHeader: null");
        }
    }

    private static String visibilityToString(int visibility) {
        if (visibility == View.VISIBLE) {
            return "VISIBLE";
        }
        if (visibility == View.INVISIBLE) {
            return "INVISIBLE";
        }
        return "GONE";
    }

    @Override
    public View getView() {
        return mRootView;
    }

    @Override
    public Context getContext() {
        return mRootView.getContext();
    }

    private Resources getResources() {
        return getContext().getResources();
    }

    /**
     * A {@link LifecycleOwner} whose state is driven by the current state of this fragment:
     *
     *  - DESTROYED when the fragment is destroyed.
     *  - CREATED when mListening == mQsVisible == false.
     *  - STARTED when mListening == true && mQsVisible == false.
     *  - RESUMED when mListening == true && mQsVisible == true.
     */
    @VisibleForTesting
    class ListeningAndVisibilityLifecycleOwner implements LifecycleOwner {
        private final LifecycleRegistry mLifecycleRegistry = new LifecycleRegistry(this);
        private boolean mDestroyed = false;

        {
            updateState();
        }

        @Override
        public Lifecycle getLifecycle() {
            return mLifecycleRegistry;
        }

        /**
         * Update the state of the associated lifecycle. This should be called whenever
         * {@code mListening} or {@code mQsVisible} is changed.
         */
        public void updateState() {
            if (mDestroyed) {
                mLifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
                return;
            }

            if (!mListening) {
                mLifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
                return;
            }

            // mListening && !mQsVisible.
            if (!mQsVisible) {
                mLifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
                return;
            }

            // mListening && mQsVisible.
            mLifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);
        }

        public void destroy() {
            mDestroyed = true;
            updateState();
        }
    }
}
