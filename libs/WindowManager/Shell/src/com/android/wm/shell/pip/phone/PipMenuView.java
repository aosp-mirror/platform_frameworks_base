/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.pip.phone;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.provider.Settings.ACTION_PICTURE_IN_PICTURE_SETTINGS;
import static android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS;
import static android.view.accessibility.AccessibilityManager.FLAG_CONTENT_ICONS;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK;

import static com.android.wm.shell.pip.phone.PhonePipMenuController.MENU_STATE_CLOSE;
import static com.android.wm.shell.pip.phone.PhonePipMenuController.MENU_STATE_FULL;
import static com.android.wm.shell.pip.phone.PhonePipMenuController.MENU_STATE_NONE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.PendingIntent.CanceledException;
import android.app.RemoteAction;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.android.wm.shell.R;
import com.android.wm.shell.animation.Interpolators;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.pip.PipUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Translucent window that gets started on top of a task in PIP to allow the user to control it.
 */
public class PipMenuView extends FrameLayout {

    private static final String TAG = "PipMenuView";

    private static final int INITIAL_DISMISS_DELAY = 3500;
    private static final int POST_INTERACTION_DISMISS_DELAY = 2000;
    private static final long MENU_FADE_DURATION = 125;
    private static final long MENU_SHOW_ON_EXPAND_START_DELAY = 30;

    private static final float MENU_BACKGROUND_ALPHA = 0.3f;
    private static final float DISABLED_ACTION_ALPHA = 0.54f;

    private static final boolean ENABLE_RESIZE_HANDLE = false;

    private int mMenuState;
    private boolean mAllowMenuTimeout = true;
    private boolean mAllowTouches = true;

    private final List<RemoteAction> mActions = new ArrayList<>();

    private AccessibilityManager mAccessibilityManager;
    private Drawable mBackgroundDrawable;
    private View mMenuContainer;
    private LinearLayout mActionsGroup;
    private int mBetweenActionPaddingLand;

    private AnimatorSet mMenuContainerAnimator;
    private PhonePipMenuController mController;

    private ValueAnimator.AnimatorUpdateListener mMenuBgUpdateListener =
            new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    final float alpha = (float) animation.getAnimatedValue();
                    mBackgroundDrawable.setAlpha((int) (MENU_BACKGROUND_ALPHA * alpha * 255));
                }
            };

    private ShellExecutor mMainExecutor;
    private Handler mMainHandler;

    private final Runnable mHideMenuRunnable = this::hideMenu;

    protected View mViewRoot;
    protected View mSettingsButton;
    protected View mDismissButton;
    protected View mResizeHandle;
    protected View mTopEndContainer;
    protected PipMenuIconsAlgorithm mPipMenuIconsAlgorithm;

    public PipMenuView(Context context, PhonePipMenuController controller,
            ShellExecutor mainExecutor, Handler mainHandler) {
        super(context, null, 0);
        mContext = context;
        mController = controller;
        mMainExecutor = mainExecutor;
        mMainHandler = mainHandler;

        mAccessibilityManager = context.getSystemService(AccessibilityManager.class);
        inflate(context, R.layout.pip_menu, this);

        mBackgroundDrawable = new ColorDrawable(Color.BLACK);
        mBackgroundDrawable.setAlpha(0);
        mViewRoot = findViewById(R.id.background);
        mViewRoot.setBackground(mBackgroundDrawable);
        mMenuContainer = findViewById(R.id.menu_container);
        mMenuContainer.setAlpha(0);
        mTopEndContainer = findViewById(R.id.top_end_container);
        mSettingsButton = findViewById(R.id.settings);
        mSettingsButton.setAlpha(0);
        mSettingsButton.setOnClickListener((v) -> {
            if (v.getAlpha() != 0) {
                showSettings();
            }
        });
        mDismissButton = findViewById(R.id.dismiss);
        mDismissButton.setAlpha(0);
        mDismissButton.setOnClickListener(v -> dismissPip());
        findViewById(R.id.expand_button).setOnClickListener(v -> {
            if (mMenuContainer.getAlpha() != 0) {
                expandPip();
            }
        });

        mResizeHandle = findViewById(R.id.resize_handle);
        mResizeHandle.setAlpha(0);
        mActionsGroup = findViewById(R.id.actions_group);
        mBetweenActionPaddingLand = getResources().getDimensionPixelSize(
                R.dimen.pip_between_action_padding_land);
        mPipMenuIconsAlgorithm = new PipMenuIconsAlgorithm(mContext);
        mPipMenuIconsAlgorithm.bindViews((ViewGroup) mViewRoot, (ViewGroup) mTopEndContainer,
                mResizeHandle, mSettingsButton, mDismissButton);

        initAccessibility();
    }

    private void initAccessibility() {
        this.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                String label = getResources().getString(R.string.pip_menu_title);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_CLICK, label));
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == ACTION_CLICK && mMenuState == MENU_STATE_CLOSE) {
                    mController.showMenu();
                }
                return super.performAccessibilityAction(host, action, args);
            }
        });
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            hideMenu();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (!mAllowTouches) {
            return false;
        }

        if (mAllowMenuTimeout) {
            repostDelayedHide(POST_INTERACTION_DISMISS_DELAY);
        }

        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (mAllowMenuTimeout) {
            repostDelayedHide(POST_INTERACTION_DISMISS_DELAY);
        }

        return super.dispatchGenericMotionEvent(event);
    }

    void showMenu(int menuState, Rect stackBounds, boolean allowMenuTimeout,
            boolean resizeMenuOnShow, boolean withDelay, boolean showResizeHandle) {
        mAllowMenuTimeout = allowMenuTimeout;
        if (mMenuState != menuState) {
            // Disallow touches if the menu needs to resize while showing, and we are transitioning
            // to/from a full menu state.
            boolean disallowTouchesUntilAnimationEnd = resizeMenuOnShow
                    && (mMenuState == MENU_STATE_FULL || menuState == MENU_STATE_FULL);
            mAllowTouches = !disallowTouchesUntilAnimationEnd;
            cancelDelayedHide();
            if (mMenuContainerAnimator != null) {
                mMenuContainerAnimator.cancel();
            }
            mMenuContainerAnimator = new AnimatorSet();
            ObjectAnimator menuAnim = ObjectAnimator.ofFloat(mMenuContainer, View.ALPHA,
                    mMenuContainer.getAlpha(), 1f);
            menuAnim.addUpdateListener(mMenuBgUpdateListener);
            ObjectAnimator settingsAnim = ObjectAnimator.ofFloat(mSettingsButton, View.ALPHA,
                    mSettingsButton.getAlpha(), 1f);
            ObjectAnimator dismissAnim = ObjectAnimator.ofFloat(mDismissButton, View.ALPHA,
                    mDismissButton.getAlpha(), 1f);
            ObjectAnimator resizeAnim = ObjectAnimator.ofFloat(mResizeHandle, View.ALPHA,
                    mResizeHandle.getAlpha(),
                    ENABLE_RESIZE_HANDLE && menuState == MENU_STATE_CLOSE && showResizeHandle
                            ? 1f : 0f);
            if (menuState == MENU_STATE_FULL) {
                mMenuContainerAnimator.playTogether(menuAnim, settingsAnim, dismissAnim,
                        resizeAnim);
            } else {
                mMenuContainerAnimator.playTogether(dismissAnim, resizeAnim);
            }
            mMenuContainerAnimator.setInterpolator(Interpolators.ALPHA_IN);
            mMenuContainerAnimator.setDuration(MENU_FADE_DURATION);
            if (allowMenuTimeout) {
                mMenuContainerAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        repostDelayedHide(INITIAL_DISMISS_DELAY);
                    }
                });
            }
            if (withDelay) {
                // starts the menu container animation after window expansion is completed
                notifyMenuStateChange(menuState, resizeMenuOnShow, () -> {
                    if (mMenuContainerAnimator == null) {
                        return;
                    }
                    mMenuContainerAnimator.setStartDelay(MENU_SHOW_ON_EXPAND_START_DELAY);
                    setVisibility(VISIBLE);
                    mMenuContainerAnimator.start();
                });
            } else {
                notifyMenuStateChange(menuState, resizeMenuOnShow, null);
                setVisibility(VISIBLE);
                mMenuContainerAnimator.start();
            }
            updateActionViews(stackBounds);
        } else {
            // If we are already visible, then just start the delayed dismiss and unregister any
            // existing input consumers from the previous drag
            if (allowMenuTimeout) {
                repostDelayedHide(POST_INTERACTION_DISMISS_DELAY);
            }
        }
    }

    /**
     * Different from {@link #hideMenu()}, this function does not try to finish this menu activity
     * and instead, it fades out the controls by setting the alpha to 0 directly without menu
     * visibility callbacks invoked.
     */
    void fadeOutMenu() {
        mMenuContainer.setAlpha(0f);
        mSettingsButton.setAlpha(0f);
        mDismissButton.setAlpha(0f);
        mResizeHandle.setAlpha(0f);
    }

    void pokeMenu() {
        cancelDelayedHide();
    }

    void onPipAnimationEnded() {
        mAllowTouches = true;
    }

    void updateMenuLayout(Rect bounds) {
        mPipMenuIconsAlgorithm.onBoundsChanged(bounds);
    }

    void hideMenu() {
        hideMenu(null);
    }

    void hideMenu(boolean animate, boolean resize) {
        hideMenu(null, true /* notifyMenuVisibility */, animate, resize);
    }

    void hideMenu(Runnable animationEndCallback) {
        hideMenu(animationEndCallback, true /* notifyMenuVisibility */, true /* animate */,
                true /* resize */);
    }

    private void hideMenu(final Runnable animationFinishedRunnable, boolean notifyMenuVisibility,
            boolean animate, boolean resize) {
        if (mMenuState != MENU_STATE_NONE) {
            cancelDelayedHide();
            if (notifyMenuVisibility) {
                notifyMenuStateChange(MENU_STATE_NONE, resize, null);
            }
            mMenuContainerAnimator = new AnimatorSet();
            ObjectAnimator menuAnim = ObjectAnimator.ofFloat(mMenuContainer, View.ALPHA,
                    mMenuContainer.getAlpha(), 0f);
            menuAnim.addUpdateListener(mMenuBgUpdateListener);
            ObjectAnimator settingsAnim = ObjectAnimator.ofFloat(mSettingsButton, View.ALPHA,
                    mSettingsButton.getAlpha(), 0f);
            ObjectAnimator dismissAnim = ObjectAnimator.ofFloat(mDismissButton, View.ALPHA,
                    mDismissButton.getAlpha(), 0f);
            ObjectAnimator resizeAnim = ObjectAnimator.ofFloat(mResizeHandle, View.ALPHA,
                    mResizeHandle.getAlpha(), 0f);
            mMenuContainerAnimator.playTogether(menuAnim, settingsAnim, dismissAnim, resizeAnim);
            mMenuContainerAnimator.setInterpolator(Interpolators.ALPHA_OUT);
            mMenuContainerAnimator.setDuration(animate ? MENU_FADE_DURATION : 0);
            mMenuContainerAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    setVisibility(GONE);
                    if (animationFinishedRunnable != null) {
                        animationFinishedRunnable.run();
                    }
                }
            });
            mMenuContainerAnimator.start();
        }
    }

    /**
     * @return Estimated minimum {@link Size} to hold the actions.
     *         See also {@link #updateActionViews(Rect)}
     */
    Size getEstimatedMinMenuSize() {
        final int pipActionSize = getResources().getDimensionPixelSize(R.dimen.pip_action_size);
        // the minimum width would be (2 * pipActionSize) since we have settings and dismiss button
        // on the top action container.
        final int width = Math.max(2, mActions.size()) * pipActionSize;
        final int height = getResources().getDimensionPixelSize(R.dimen.pip_expand_action_size)
                + getResources().getDimensionPixelSize(R.dimen.pip_action_padding)
                + getResources().getDimensionPixelSize(R.dimen.pip_expand_container_edge_margin);
        return new Size(width, height);
    }

    void setActions(Rect stackBounds, List<RemoteAction> actions) {
        mActions.clear();
        mActions.addAll(actions);
        updateActionViews(stackBounds);
    }

    private void updateActionViews(Rect stackBounds) {
        ViewGroup expandContainer = findViewById(R.id.expand_container);
        ViewGroup actionsContainer = findViewById(R.id.actions_container);
        actionsContainer.setOnTouchListener((v, ev) -> {
            // Do nothing, prevent click through to parent
            return true;
        });

        // Update the expand button only if it should show with the menu
        expandContainer.setVisibility(mMenuState == MENU_STATE_FULL
                ? View.VISIBLE
                : View.INVISIBLE);

        FrameLayout.LayoutParams expandedLp =
                (FrameLayout.LayoutParams) expandContainer.getLayoutParams();
        if (mActions.isEmpty() || mMenuState == MENU_STATE_CLOSE || mMenuState == MENU_STATE_NONE) {
            actionsContainer.setVisibility(View.INVISIBLE);

            // Update the expand container margin to adjust the center of the expand button to
            // account for the existence of the action container
            expandedLp.topMargin = 0;
            expandedLp.bottomMargin = 0;
        } else {
            actionsContainer.setVisibility(View.VISIBLE);
            if (mActionsGroup != null) {
                // Ensure we have as many buttons as actions
                final LayoutInflater inflater = LayoutInflater.from(mContext);
                while (mActionsGroup.getChildCount() < mActions.size()) {
                    final PipMenuActionView actionView = (PipMenuActionView) inflater.inflate(
                            R.layout.pip_menu_action, mActionsGroup, false);
                    mActionsGroup.addView(actionView);
                }

                // Update the visibility of all views
                for (int i = 0; i < mActionsGroup.getChildCount(); i++) {
                    mActionsGroup.getChildAt(i).setVisibility(i < mActions.size()
                            ? View.VISIBLE
                            : View.GONE);
                }

                // Recreate the layout
                final boolean isLandscapePip = stackBounds != null
                        && (stackBounds.width() > stackBounds.height());
                for (int i = 0; i < mActions.size(); i++) {
                    final RemoteAction action = mActions.get(i);
                    final PipMenuActionView actionView =
                            (PipMenuActionView) mActionsGroup.getChildAt(i);

                    // TODO: Check if the action drawable has changed before we reload it
                    action.getIcon().loadDrawableAsync(mContext, d -> {
                        if (d != null) {
                            d.setTint(Color.WHITE);
                            actionView.setImageDrawable(d);
                        }
                    }, mMainHandler);
                    actionView.setContentDescription(action.getContentDescription());
                    if (action.isEnabled()) {
                        actionView.setOnClickListener(v -> {
                            try {
                                action.getActionIntent().send();
                            } catch (CanceledException e) {
                                Log.w(TAG, "Failed to send action", e);
                            }
                        });
                    }
                    actionView.setEnabled(action.isEnabled());
                    actionView.setAlpha(action.isEnabled() ? 1f : DISABLED_ACTION_ALPHA);

                    // Update the margin between actions
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)
                            actionView.getLayoutParams();
                    lp.leftMargin = (isLandscapePip && i > 0) ? mBetweenActionPaddingLand : 0;
                }
            }

            // Update the expand container margin to adjust the center of the expand button to
            // account for the existence of the action container
            expandedLp.topMargin = getResources().getDimensionPixelSize(
                    R.dimen.pip_action_padding);
            expandedLp.bottomMargin = getResources().getDimensionPixelSize(
                    R.dimen.pip_expand_container_edge_margin);
        }
        expandContainer.requestLayout();
    }

    private void notifyMenuStateChange(int menuState, boolean resize, Runnable callback) {
        mMenuState = menuState;
        mController.onMenuStateChanged(menuState, resize, callback);
    }

    private void expandPip() {
        // Do not notify menu visibility when hiding the menu, the controller will do this when it
        // handles the message
        hideMenu(mController::onPipExpand, false /* notifyMenuVisibility */, true /* animate */,
                true /* resize */);
    }

    private void dismissPip() {
        // Since tapping on the close-button invokes a double-tap wait callback in PipTouchHandler,
        // we want to disable animating the fadeout animation of the buttons in order to call on
        // PipTouchHandler#onPipDismiss fast enough.
        final boolean animate = mMenuState != MENU_STATE_CLOSE;
        // Do not notify menu visibility when hiding the menu, the controller will do this when it
        // handles the message
        hideMenu(mController::onPipDismiss, false /* notifyMenuVisibility */, animate,
                true /* resize */);
    }

    private void showSettings() {
        final Pair<ComponentName, Integer> topPipActivityInfo =
                PipUtils.getTopPipActivity(mContext);
        if (topPipActivityInfo.first != null) {
            final Intent settingsIntent = new Intent(ACTION_PICTURE_IN_PICTURE_SETTINGS,
                    Uri.fromParts("package", topPipActivityInfo.first.getPackageName(), null));
            settingsIntent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK);
            mContext.startActivityAsUser(settingsIntent, UserHandle.of(topPipActivityInfo.second));
        }
    }

    private void cancelDelayedHide() {
        mMainExecutor.removeCallbacks(mHideMenuRunnable);
    }

    private void repostDelayedHide(int delay) {
        int recommendedTimeout = mAccessibilityManager.getRecommendedTimeoutMillis(delay,
                FLAG_CONTENT_ICONS | FLAG_CONTENT_CONTROLS);
        mMainExecutor.removeCallbacks(mHideMenuRunnable);
        mMainExecutor.executeDelayed(mHideMenuRunnable, recommendedTimeout);
    }
}
