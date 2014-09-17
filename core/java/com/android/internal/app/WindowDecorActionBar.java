/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.internal.app;

import android.animation.ValueAnimator;
import android.content.res.TypedArray;
import android.view.ViewParent;
import android.widget.Toolbar;
import com.android.internal.R;
import com.android.internal.view.ActionBarPolicy;
import com.android.internal.view.menu.MenuBuilder;
import com.android.internal.view.menu.MenuPopupHelper;
import com.android.internal.view.menu.SubMenuBuilder;
import com.android.internal.widget.ActionBarContainer;
import com.android.internal.widget.ActionBarContextView;
import com.android.internal.widget.ActionBarOverlayLayout;
import com.android.internal.widget.DecorToolbar;
import com.android.internal.widget.ScrollingTabContainerView;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.ActionBar;
import android.app.Activity;
import android.app.Dialog;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.AnimationUtils;
import android.widget.SpinnerAdapter;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * WindowDecorActionBar is the ActionBar implementation used
 * by devices of all screen sizes as part of the window decor layout.
 * If it detects a compatible decor, it will split contextual modes
 * across both the ActionBarView at the top of the screen and
 * a horizontal LinearLayout at the bottom which is normally hidden.
 */
public class WindowDecorActionBar extends ActionBar implements
        ActionBarOverlayLayout.ActionBarVisibilityCallback {
    private static final String TAG = "WindowDecorActionBar";

    private Context mContext;
    private Context mThemedContext;
    private Activity mActivity;
    private Dialog mDialog;

    private ActionBarOverlayLayout mOverlayLayout;
    private ActionBarContainer mContainerView;
    private DecorToolbar mDecorToolbar;
    private ActionBarContextView mContextView;
    private ActionBarContainer mSplitView;
    private View mContentView;
    private ScrollingTabContainerView mTabScrollView;

    private ArrayList<TabImpl> mTabs = new ArrayList<TabImpl>();

    private TabImpl mSelectedTab;
    private int mSavedTabPosition = INVALID_POSITION;
    
    private boolean mDisplayHomeAsUpSet;

    ActionModeImpl mActionMode;
    ActionMode mDeferredDestroyActionMode;
    ActionMode.Callback mDeferredModeDestroyCallback;
    
    private boolean mLastMenuVisibility;
    private ArrayList<OnMenuVisibilityListener> mMenuVisibilityListeners =
            new ArrayList<OnMenuVisibilityListener>();

    private static final int CONTEXT_DISPLAY_NORMAL = 0;
    private static final int CONTEXT_DISPLAY_SPLIT = 1;
    
    private static final int INVALID_POSITION = -1;

    private int mContextDisplayMode;
    private boolean mHasEmbeddedTabs;

    private int mCurWindowVisibility = View.VISIBLE;

    private boolean mContentAnimations = true;
    private boolean mHiddenByApp;
    private boolean mHiddenBySystem;
    private boolean mShowingForMode;

    private boolean mNowShowing = true;

    private Animator mCurrentShowAnim;
    private boolean mShowHideAnimationEnabled;
    boolean mHideOnContentScroll;

    final AnimatorListener mHideListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            if (mContentAnimations && mContentView != null) {
                mContentView.setTranslationY(0);
                mContainerView.setTranslationY(0);
            }
            if (mSplitView != null && mContextDisplayMode == CONTEXT_DISPLAY_SPLIT) {
                mSplitView.setVisibility(View.GONE);
            }
            mContainerView.setVisibility(View.GONE);
            mContainerView.setTransitioning(false);
            mCurrentShowAnim = null;
            completeDeferredDestroyActionMode();
            if (mOverlayLayout != null) {
                mOverlayLayout.requestApplyInsets();
            }
        }
    };

    final AnimatorListener mShowListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mCurrentShowAnim = null;
            mContainerView.requestLayout();
        }
    };

    final ValueAnimator.AnimatorUpdateListener mUpdateListener =
            new ValueAnimator.AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator animation) {
            final ViewParent parent = mContainerView.getParent();
            ((View) parent).invalidate();
        }
    };

    public WindowDecorActionBar(Activity activity) {
        mActivity = activity;
        Window window = activity.getWindow();
        View decor = window.getDecorView();
        boolean overlayMode = mActivity.getWindow().hasFeature(Window.FEATURE_ACTION_BAR_OVERLAY);
        init(decor);
        if (!overlayMode) {
            mContentView = decor.findViewById(android.R.id.content);
        }
    }

    public WindowDecorActionBar(Dialog dialog) {
        mDialog = dialog;
        init(dialog.getWindow().getDecorView());
    }

    /**
     * Only for edit mode.
     * @hide
     */
    public WindowDecorActionBar(View layout) {
        assert layout.isInEditMode();
        init(layout);
    }

    private void init(View decor) {
        mOverlayLayout = (ActionBarOverlayLayout) decor.findViewById(
                com.android.internal.R.id.decor_content_parent);
        if (mOverlayLayout != null) {
            mOverlayLayout.setActionBarVisibilityCallback(this);
        }
        mDecorToolbar = getDecorToolbar(decor.findViewById(com.android.internal.R.id.action_bar));
        mContextView = (ActionBarContextView) decor.findViewById(
                com.android.internal.R.id.action_context_bar);
        mContainerView = (ActionBarContainer) decor.findViewById(
                com.android.internal.R.id.action_bar_container);
        mSplitView = (ActionBarContainer) decor.findViewById(
                com.android.internal.R.id.split_action_bar);

        if (mDecorToolbar == null || mContextView == null || mContainerView == null) {
            throw new IllegalStateException(getClass().getSimpleName() + " can only be used " +
                    "with a compatible window decor layout");
        }

        mContext = mDecorToolbar.getContext();
        mContextDisplayMode = mDecorToolbar.isSplit() ?
                CONTEXT_DISPLAY_SPLIT : CONTEXT_DISPLAY_NORMAL;

        // This was initially read from the action bar style
        final int current = mDecorToolbar.getDisplayOptions();
        final boolean homeAsUp = (current & DISPLAY_HOME_AS_UP) != 0;
        if (homeAsUp) {
            mDisplayHomeAsUpSet = true;
        }

        ActionBarPolicy abp = ActionBarPolicy.get(mContext);
        setHomeButtonEnabled(abp.enableHomeButtonByDefault() || homeAsUp);
        setHasEmbeddedTabs(abp.hasEmbeddedTabs());

        final TypedArray a = mContext.obtainStyledAttributes(null,
                com.android.internal.R.styleable.ActionBar,
                com.android.internal.R.attr.actionBarStyle, 0);
        if (a.getBoolean(R.styleable.ActionBar_hideOnContentScroll, false)) {
            setHideOnContentScrollEnabled(true);
        }
        final int elevation = a.getDimensionPixelSize(R.styleable.ActionBar_elevation, 0);
        if (elevation != 0) {
            setElevation(elevation);
        }
        a.recycle();
    }

    private DecorToolbar getDecorToolbar(View view) {
        if (view instanceof DecorToolbar) {
            return (DecorToolbar) view;
        } else if (view instanceof Toolbar) {
            return ((Toolbar) view).getWrapper();
        } else {
            throw new IllegalStateException("Can't make a decor toolbar out of " +
                    view.getClass().getSimpleName());
        }
    }

    @Override
    public void setElevation(float elevation) {
        mContainerView.setElevation(elevation);
        if (mSplitView != null) {
            mSplitView.setElevation(elevation);
        }
    }

    @Override
    public float getElevation() {
        return mContainerView.getElevation();
    }

    public void onConfigurationChanged(Configuration newConfig) {
        setHasEmbeddedTabs(ActionBarPolicy.get(mContext).hasEmbeddedTabs());
    }

    private void setHasEmbeddedTabs(boolean hasEmbeddedTabs) {
        mHasEmbeddedTabs = hasEmbeddedTabs;
        // Switch tab layout configuration if needed
        if (!mHasEmbeddedTabs) {
            mDecorToolbar.setEmbeddedTabView(null);
            mContainerView.setTabContainer(mTabScrollView);
        } else {
            mContainerView.setTabContainer(null);
            mDecorToolbar.setEmbeddedTabView(mTabScrollView);
        }
        final boolean isInTabMode = getNavigationMode() == NAVIGATION_MODE_TABS;
        if (mTabScrollView != null) {
            if (isInTabMode) {
                mTabScrollView.setVisibility(View.VISIBLE);
                if (mOverlayLayout != null) {
                    mOverlayLayout.requestApplyInsets();
                }
            } else {
                mTabScrollView.setVisibility(View.GONE);
            }
        }
        mDecorToolbar.setCollapsible(!mHasEmbeddedTabs && isInTabMode);
        mOverlayLayout.setHasNonEmbeddedTabs(!mHasEmbeddedTabs && isInTabMode);
    }

    private void ensureTabsExist() {
        if (mTabScrollView != null) {
            return;
        }

        ScrollingTabContainerView tabScroller = new ScrollingTabContainerView(mContext);

        if (mHasEmbeddedTabs) {
            tabScroller.setVisibility(View.VISIBLE);
            mDecorToolbar.setEmbeddedTabView(tabScroller);
        } else {
            if (getNavigationMode() == NAVIGATION_MODE_TABS) {
                tabScroller.setVisibility(View.VISIBLE);
                if (mOverlayLayout != null) {
                    mOverlayLayout.requestApplyInsets();
                }
            } else {
                tabScroller.setVisibility(View.GONE);
            }
            mContainerView.setTabContainer(tabScroller);
        }
        mTabScrollView = tabScroller;
    }

    void completeDeferredDestroyActionMode() {
        if (mDeferredModeDestroyCallback != null) {
            mDeferredModeDestroyCallback.onDestroyActionMode(mDeferredDestroyActionMode);
            mDeferredDestroyActionMode = null;
            mDeferredModeDestroyCallback = null;
        }
    }

    public void onWindowVisibilityChanged(int visibility) {
        mCurWindowVisibility = visibility;
    }

    /**
     * Enables or disables animation between show/hide states.
     * If animation is disabled using this method, animations in progress
     * will be finished.
     *
     * @param enabled true to animate, false to not animate.
     */
    public void setShowHideAnimationEnabled(boolean enabled) {
        mShowHideAnimationEnabled = enabled;
        if (!enabled && mCurrentShowAnim != null) {
            mCurrentShowAnim.end();
        }
    }

    public void addOnMenuVisibilityListener(OnMenuVisibilityListener listener) {
        mMenuVisibilityListeners.add(listener);
    }

    public void removeOnMenuVisibilityListener(OnMenuVisibilityListener listener) {
        mMenuVisibilityListeners.remove(listener);
    }

    public void dispatchMenuVisibilityChanged(boolean isVisible) {
        if (isVisible == mLastMenuVisibility) {
            return;
        }
        mLastMenuVisibility = isVisible;

        final int count = mMenuVisibilityListeners.size();
        for (int i = 0; i < count; i++) {
            mMenuVisibilityListeners.get(i).onMenuVisibilityChanged(isVisible);
        }
    }

    @Override
    public void setCustomView(int resId) {
        setCustomView(LayoutInflater.from(getThemedContext()).inflate(resId,
                mDecorToolbar.getViewGroup(), false));
    }

    @Override
    public void setDisplayUseLogoEnabled(boolean useLogo) {
        setDisplayOptions(useLogo ? DISPLAY_USE_LOGO : 0, DISPLAY_USE_LOGO);
    }

    @Override
    public void setDisplayShowHomeEnabled(boolean showHome) {
        setDisplayOptions(showHome ? DISPLAY_SHOW_HOME : 0, DISPLAY_SHOW_HOME);
    }

    @Override
    public void setDisplayHomeAsUpEnabled(boolean showHomeAsUp) {
        setDisplayOptions(showHomeAsUp ? DISPLAY_HOME_AS_UP : 0, DISPLAY_HOME_AS_UP);
    }

    @Override
    public void setDisplayShowTitleEnabled(boolean showTitle) {
        setDisplayOptions(showTitle ? DISPLAY_SHOW_TITLE : 0, DISPLAY_SHOW_TITLE);
    }

    @Override
    public void setDisplayShowCustomEnabled(boolean showCustom) {
        setDisplayOptions(showCustom ? DISPLAY_SHOW_CUSTOM : 0, DISPLAY_SHOW_CUSTOM);
    }

    @Override
    public void setHomeButtonEnabled(boolean enable) {
        mDecorToolbar.setHomeButtonEnabled(enable);
    }

    @Override
    public void setTitle(int resId) {
        setTitle(mContext.getString(resId));
    }

    @Override
    public void setSubtitle(int resId) {
        setSubtitle(mContext.getString(resId));
    }

    public void setSelectedNavigationItem(int position) {
        switch (mDecorToolbar.getNavigationMode()) {
        case NAVIGATION_MODE_TABS:
            selectTab(mTabs.get(position));
            break;
        case NAVIGATION_MODE_LIST:
            mDecorToolbar.setDropdownSelectedPosition(position);
            break;
        default:
            throw new IllegalStateException(
                    "setSelectedNavigationIndex not valid for current navigation mode");
        }
    }

    public void removeAllTabs() {
        cleanupTabs();
    }

    private void cleanupTabs() {
        if (mSelectedTab != null) {
            selectTab(null);
        }
        mTabs.clear();
        if (mTabScrollView != null) {
            mTabScrollView.removeAllTabs();
        }
        mSavedTabPosition = INVALID_POSITION;
    }

    public void setTitle(CharSequence title) {
        mDecorToolbar.setTitle(title);
    }

    @Override
    public void setWindowTitle(CharSequence title) {
        mDecorToolbar.setWindowTitle(title);
    }

    public void setSubtitle(CharSequence subtitle) {
        mDecorToolbar.setSubtitle(subtitle);
    }

    public void setDisplayOptions(int options) {
        if ((options & DISPLAY_HOME_AS_UP) != 0) {
            mDisplayHomeAsUpSet = true;
        }
        mDecorToolbar.setDisplayOptions(options);
    }

    public void setDisplayOptions(int options, int mask) {
        final int current = mDecorToolbar.getDisplayOptions();
        if ((mask & DISPLAY_HOME_AS_UP) != 0) {
            mDisplayHomeAsUpSet = true;
        }
        mDecorToolbar.setDisplayOptions((options & mask) | (current & ~mask));
    }

    public void setBackgroundDrawable(Drawable d) {
        mContainerView.setPrimaryBackground(d);
    }

    public void setStackedBackgroundDrawable(Drawable d) {
        mContainerView.setStackedBackground(d);
    }

    public void setSplitBackgroundDrawable(Drawable d) {
        if (mSplitView != null) {
            mSplitView.setSplitBackground(d);
        }
    }

    public View getCustomView() {
        return mDecorToolbar.getCustomView();
    }

    public CharSequence getTitle() {
        return mDecorToolbar.getTitle();
    }

    public CharSequence getSubtitle() {
        return mDecorToolbar.getSubtitle();
    }

    public int getNavigationMode() {
        return mDecorToolbar.getNavigationMode();
    }

    public int getDisplayOptions() {
        return mDecorToolbar.getDisplayOptions();
    }

    public ActionMode startActionMode(ActionMode.Callback callback) {
        if (mActionMode != null) {
            mActionMode.finish();
        }

        mOverlayLayout.setHideOnContentScrollEnabled(false);
        mContextView.killMode();
        ActionModeImpl mode = new ActionModeImpl(callback);
        if (mode.dispatchOnCreate()) {
            mode.invalidate();
            mContextView.initForMode(mode);
            animateToMode(true);
            if (mSplitView != null && mContextDisplayMode == CONTEXT_DISPLAY_SPLIT) {
                // TODO animate this
                if (mSplitView.getVisibility() != View.VISIBLE) {
                    mSplitView.setVisibility(View.VISIBLE);
                    if (mOverlayLayout != null) {
                        mOverlayLayout.requestApplyInsets();
                    }
                }
            }
            mContextView.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            mActionMode = mode;
            return mode;
        }
        return null;
    }

    private void configureTab(Tab tab, int position) {
        final TabImpl tabi = (TabImpl) tab;
        final ActionBar.TabListener callback = tabi.getCallback();

        if (callback == null) {
            throw new IllegalStateException("Action Bar Tab must have a Callback");
        }

        tabi.setPosition(position);
        mTabs.add(position, tabi);

        final int count = mTabs.size();
        for (int i = position + 1; i < count; i++) {
            mTabs.get(i).setPosition(i);
        }
    }

    @Override
    public void addTab(Tab tab) {
        addTab(tab, mTabs.isEmpty());
    }

    @Override
    public void addTab(Tab tab, int position) {
        addTab(tab, position, mTabs.isEmpty());
    }

    @Override
    public void addTab(Tab tab, boolean setSelected) {
        ensureTabsExist();
        mTabScrollView.addTab(tab, setSelected);
        configureTab(tab, mTabs.size());
        if (setSelected) {
            selectTab(tab);
        }
    }

    @Override
    public void addTab(Tab tab, int position, boolean setSelected) {
        ensureTabsExist();
        mTabScrollView.addTab(tab, position, setSelected);
        configureTab(tab, position);
        if (setSelected) {
            selectTab(tab);
        }
    }

    @Override
    public Tab newTab() {
        return new TabImpl();
    }

    @Override
    public void removeTab(Tab tab) {
        removeTabAt(tab.getPosition());
    }

    @Override
    public void removeTabAt(int position) {
        if (mTabScrollView == null) {
            // No tabs around to remove
            return;
        }

        int selectedTabPosition = mSelectedTab != null
                ? mSelectedTab.getPosition() : mSavedTabPosition;
        mTabScrollView.removeTabAt(position);
        TabImpl removedTab = mTabs.remove(position);
        if (removedTab != null) {
            removedTab.setPosition(-1);
        }

        final int newTabCount = mTabs.size();
        for (int i = position; i < newTabCount; i++) {
            mTabs.get(i).setPosition(i);
        }

        if (selectedTabPosition == position) {
            selectTab(mTabs.isEmpty() ? null : mTabs.get(Math.max(0, position - 1)));
        }
    }

    @Override
    public void selectTab(Tab tab) {
        if (getNavigationMode() != NAVIGATION_MODE_TABS) {
            mSavedTabPosition = tab != null ? tab.getPosition() : INVALID_POSITION;
            return;
        }

        final FragmentTransaction trans = mDecorToolbar.getViewGroup().isInEditMode() ? null :
                mActivity.getFragmentManager().beginTransaction().disallowAddToBackStack();

        if (mSelectedTab == tab) {
            if (mSelectedTab != null) {
                mSelectedTab.getCallback().onTabReselected(mSelectedTab, trans);
                mTabScrollView.animateToTab(tab.getPosition());
            }
        } else {
            mTabScrollView.setTabSelected(tab != null ? tab.getPosition() : Tab.INVALID_POSITION);
            if (mSelectedTab != null) {
                mSelectedTab.getCallback().onTabUnselected(mSelectedTab, trans);
            }
            mSelectedTab = (TabImpl) tab;
            if (mSelectedTab != null) {
                mSelectedTab.getCallback().onTabSelected(mSelectedTab, trans);
            }
        }

        if (trans != null && !trans.isEmpty()) {
            trans.commit();
        }
    }

    @Override
    public Tab getSelectedTab() {
        return mSelectedTab;
    }

    @Override
    public int getHeight() {
        return mContainerView.getHeight();
    }

    public void enableContentAnimations(boolean enabled) {
        mContentAnimations = enabled;
    }

    @Override
    public void show() {
        if (mHiddenByApp) {
            mHiddenByApp = false;
            updateVisibility(false);
        }
    }

    private void showForActionMode() {
        if (!mShowingForMode) {
            mShowingForMode = true;
            if (mOverlayLayout != null) {
                mOverlayLayout.setShowingForActionMode(true);
            }
            updateVisibility(false);
        }
    }

    public void showForSystem() {
        if (mHiddenBySystem) {
            mHiddenBySystem = false;
            updateVisibility(true);
        }
    }

    @Override
    public void hide() {
        if (!mHiddenByApp) {
            mHiddenByApp = true;
            updateVisibility(false);
        }
    }

    private void hideForActionMode() {
        if (mShowingForMode) {
            mShowingForMode = false;
            if (mOverlayLayout != null) {
                mOverlayLayout.setShowingForActionMode(false);
            }
            updateVisibility(false);
        }
    }

    public void hideForSystem() {
        if (!mHiddenBySystem) {
            mHiddenBySystem = true;
            updateVisibility(true);
        }
    }

    @Override
    public void setHideOnContentScrollEnabled(boolean hideOnContentScroll) {
        if (hideOnContentScroll && !mOverlayLayout.isInOverlayMode()) {
            throw new IllegalStateException("Action bar must be in overlay mode " +
                    "(Window.FEATURE_OVERLAY_ACTION_BAR) to enable hide on content scroll");
        }
        mHideOnContentScroll = hideOnContentScroll;
        mOverlayLayout.setHideOnContentScrollEnabled(hideOnContentScroll);
    }

    @Override
    public boolean isHideOnContentScrollEnabled() {
        return mOverlayLayout.isHideOnContentScrollEnabled();
    }

    @Override
    public int getHideOffset() {
        return mOverlayLayout.getActionBarHideOffset();
    }

    @Override
    public void setHideOffset(int offset) {
        if (offset != 0 && !mOverlayLayout.isInOverlayMode()) {
            throw new IllegalStateException("Action bar must be in overlay mode " +
                    "(Window.FEATURE_OVERLAY_ACTION_BAR) to set a non-zero hide offset");
        }
        mOverlayLayout.setActionBarHideOffset(offset);
    }

    private static boolean checkShowingFlags(boolean hiddenByApp, boolean hiddenBySystem,
            boolean showingForMode) {
        if (showingForMode) {
            return true;
        } else if (hiddenByApp || hiddenBySystem) {
            return false;
        } else {
            return true;
        }
    }

    private void updateVisibility(boolean fromSystem) {
        // Based on the current state, should we be hidden or shown?
        final boolean shown = checkShowingFlags(mHiddenByApp, mHiddenBySystem,
                mShowingForMode);

        if (shown) {
            if (!mNowShowing) {
                mNowShowing = true;
                doShow(fromSystem);
            }
        } else {
            if (mNowShowing) {
                mNowShowing = false;
                doHide(fromSystem);
            }
        }
    }

    public void doShow(boolean fromSystem) {
        if (mCurrentShowAnim != null) {
            mCurrentShowAnim.end();
        }
        mContainerView.setVisibility(View.VISIBLE);

        if (mCurWindowVisibility == View.VISIBLE && (mShowHideAnimationEnabled
                || fromSystem)) {
            mContainerView.setTranslationY(0); // because we're about to ask its window loc
            float startingY = -mContainerView.getHeight();
            if (fromSystem) {
                int topLeft[] = {0, 0};
                mContainerView.getLocationInWindow(topLeft);
                startingY -= topLeft[1];
            }
            mContainerView.setTranslationY(startingY);
            AnimatorSet anim = new AnimatorSet();
            ObjectAnimator a = ObjectAnimator.ofFloat(mContainerView, View.TRANSLATION_Y, 0);
            a.addUpdateListener(mUpdateListener);
            AnimatorSet.Builder b = anim.play(a);
            if (mContentAnimations && mContentView != null) {
                b.with(ObjectAnimator.ofFloat(mContentView, View.TRANSLATION_Y,
                        startingY, 0));
            }
            if (mSplitView != null && mContextDisplayMode == CONTEXT_DISPLAY_SPLIT) {
                mSplitView.setTranslationY(mSplitView.getHeight());
                mSplitView.setVisibility(View.VISIBLE);
                b.with(ObjectAnimator.ofFloat(mSplitView, View.TRANSLATION_Y, 0));
            }
            anim.setInterpolator(AnimationUtils.loadInterpolator(mContext,
                    com.android.internal.R.interpolator.decelerate_cubic));
            anim.setDuration(250);
            // If this is being shown from the system, add a small delay.
            // This is because we will also be animating in the status bar,
            // and these two elements can't be done in lock-step.  So we give
            // a little time for the status bar to start its animation before
            // the action bar animates.  (This corresponds to the corresponding
            // case when hiding, where the status bar has a small delay before
            // starting.)
            anim.addListener(mShowListener);
            mCurrentShowAnim = anim;
            anim.start();
        } else {
            mContainerView.setAlpha(1);
            mContainerView.setTranslationY(0);
            if (mContentAnimations && mContentView != null) {
                mContentView.setTranslationY(0);
            }
            if (mSplitView != null && mContextDisplayMode == CONTEXT_DISPLAY_SPLIT) {
                mSplitView.setAlpha(1);
                mSplitView.setTranslationY(0);
                mSplitView.setVisibility(View.VISIBLE);
            }
            mShowListener.onAnimationEnd(null);
        }
        if (mOverlayLayout != null) {
            mOverlayLayout.requestApplyInsets();
        }
    }

    public void doHide(boolean fromSystem) {
        if (mCurrentShowAnim != null) {
            mCurrentShowAnim.end();
        }

        if (mCurWindowVisibility == View.VISIBLE && (mShowHideAnimationEnabled
                || fromSystem)) {
            mContainerView.setAlpha(1);
            mContainerView.setTransitioning(true);
            AnimatorSet anim = new AnimatorSet();
            float endingY = -mContainerView.getHeight();
            if (fromSystem) {
                int topLeft[] = {0, 0};
                mContainerView.getLocationInWindow(topLeft);
                endingY -= topLeft[1];
            }
            ObjectAnimator a = ObjectAnimator.ofFloat(mContainerView, View.TRANSLATION_Y, endingY);
            a.addUpdateListener(mUpdateListener);
            AnimatorSet.Builder b = anim.play(a);
            if (mContentAnimations && mContentView != null) {
                b.with(ObjectAnimator.ofFloat(mContentView, View.TRANSLATION_Y,
                        0, endingY));
            }
            if (mSplitView != null && mSplitView.getVisibility() == View.VISIBLE) {
                mSplitView.setAlpha(1);
                b.with(ObjectAnimator.ofFloat(mSplitView, View.TRANSLATION_Y,
                        mSplitView.getHeight()));
            }
            anim.setInterpolator(AnimationUtils.loadInterpolator(mContext,
                    com.android.internal.R.interpolator.accelerate_cubic));
            anim.setDuration(250);
            anim.addListener(mHideListener);
            mCurrentShowAnim = anim;
            anim.start();
        } else {
            mHideListener.onAnimationEnd(null);
        }
    }

    public boolean isShowing() {
        final int height = getHeight();
        // Take into account the case where the bar has a 0 height due to not being measured yet.
        return mNowShowing && (height == 0 || getHideOffset() < height);
    }

    void animateToMode(boolean toActionMode) {
        if (toActionMode) {
            showForActionMode();
        } else {
            hideForActionMode();
        }

        mDecorToolbar.animateToVisibility(toActionMode ? View.GONE : View.VISIBLE);
        mContextView.animateToVisibility(toActionMode ? View.VISIBLE : View.GONE);
        // mTabScrollView's visibility is not affected by action mode.
    }

    public Context getThemedContext() {
        if (mThemedContext == null) {
            TypedValue outValue = new TypedValue();
            Resources.Theme currentTheme = mContext.getTheme();
            currentTheme.resolveAttribute(com.android.internal.R.attr.actionBarWidgetTheme,
                    outValue, true);
            final int targetThemeRes = outValue.resourceId;
            
            if (targetThemeRes != 0 && mContext.getThemeResId() != targetThemeRes) {
                mThemedContext = new ContextThemeWrapper(mContext, targetThemeRes);
            } else {
                mThemedContext = mContext;
            }
        }
        return mThemedContext;
    }
    
    @Override
    public boolean isTitleTruncated() {
        return mDecorToolbar != null && mDecorToolbar.isTitleTruncated();
    }

    @Override
    public void setHomeAsUpIndicator(Drawable indicator) {
        mDecorToolbar.setNavigationIcon(indicator);
    }

    @Override
    public void setHomeAsUpIndicator(int resId) {
        mDecorToolbar.setNavigationIcon(resId);
    }

    @Override
    public void setHomeActionContentDescription(CharSequence description) {
        mDecorToolbar.setNavigationContentDescription(description);
    }

    @Override
    public void setHomeActionContentDescription(int resId) {
        mDecorToolbar.setNavigationContentDescription(resId);
    }

    @Override
    public void onContentScrollStarted() {
        if (mCurrentShowAnim != null) {
            mCurrentShowAnim.cancel();
            mCurrentShowAnim = null;
        }
    }

    @Override
    public void onContentScrollStopped() {
    }

    @Override
    public boolean collapseActionView() {
        if (mDecorToolbar != null && mDecorToolbar.hasExpandedActionView()) {
            mDecorToolbar.collapseActionView();
            return true;
        }
        return false;
    }

    /**
     * @hide 
     */
    public class ActionModeImpl extends ActionMode implements MenuBuilder.Callback {
        private ActionMode.Callback mCallback;
        private MenuBuilder mMenu;
        private WeakReference<View> mCustomView;
        
        public ActionModeImpl(ActionMode.Callback callback) {
            mCallback = callback;
            mMenu = new MenuBuilder(getThemedContext())
                    .setDefaultShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            mMenu.setCallback(this);
        }

        @Override
        public MenuInflater getMenuInflater() {
            return new MenuInflater(getThemedContext());
        }

        @Override
        public Menu getMenu() {
            return mMenu;
        }

        @Override
        public void finish() {
            if (mActionMode != this) {
                // Not the active action mode - no-op
                return;
            }

            // If this change in state is going to cause the action bar
            // to be hidden, defer the onDestroy callback until the animation
            // is finished and associated relayout is about to happen. This lets
            // apps better anticipate visibility and layout behavior.
            if (!checkShowingFlags(mHiddenByApp, mHiddenBySystem, false)) {
                // With the current state but the action bar hidden, our
                // overall showing state is going to be false.
                mDeferredDestroyActionMode = this;
                mDeferredModeDestroyCallback = mCallback;
            } else {
                mCallback.onDestroyActionMode(this);
            }
            mCallback = null;
            animateToMode(false);

            // Clear out the context mode views after the animation finishes
            mContextView.closeMode();
            mDecorToolbar.getViewGroup().sendAccessibilityEvent(
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            mOverlayLayout.setHideOnContentScrollEnabled(mHideOnContentScroll);

            mActionMode = null;
        }

        @Override
        public void invalidate() {
            mMenu.stopDispatchingItemsChanged();
            try {
                mCallback.onPrepareActionMode(this, mMenu);
            } finally {
                mMenu.startDispatchingItemsChanged();
            }
        }

        public boolean dispatchOnCreate() {
            mMenu.stopDispatchingItemsChanged();
            try {
                return mCallback.onCreateActionMode(this, mMenu);
            } finally {
                mMenu.startDispatchingItemsChanged();
            }
        }

        @Override
        public void setCustomView(View view) {
            mContextView.setCustomView(view);
            mCustomView = new WeakReference<View>(view);
        }

        @Override
        public void setSubtitle(CharSequence subtitle) {
            mContextView.setSubtitle(subtitle);
        }

        @Override
        public void setTitle(CharSequence title) {
            mContextView.setTitle(title);
        }

        @Override
        public void setTitle(int resId) {
            setTitle(mContext.getResources().getString(resId));
        }

        @Override
        public void setSubtitle(int resId) {
            setSubtitle(mContext.getResources().getString(resId));
        }

        @Override
        public CharSequence getTitle() {
            return mContextView.getTitle();
        }

        @Override
        public CharSequence getSubtitle() {
            return mContextView.getSubtitle();
        }
        
        @Override
        public void setTitleOptionalHint(boolean titleOptional) {
            super.setTitleOptionalHint(titleOptional);
            mContextView.setTitleOptional(titleOptional);
        }

        @Override
        public boolean isTitleOptional() {
            return mContextView.isTitleOptional();
        }

        @Override
        public View getCustomView() {
            return mCustomView != null ? mCustomView.get() : null;
        }

        public boolean onMenuItemSelected(MenuBuilder menu, MenuItem item) {
            if (mCallback != null) {
                return mCallback.onActionItemClicked(this, item);
            } else {
                return false;
            }
        }

        public void onCloseMenu(MenuBuilder menu, boolean allMenusAreClosing) {
        }

        public boolean onSubMenuSelected(SubMenuBuilder subMenu) {
            if (mCallback == null) {
                return false;
            }

            if (!subMenu.hasVisibleItems()) {
                return true;
            }

            new MenuPopupHelper(getThemedContext(), subMenu).show();
            return true;
        }

        public void onCloseSubMenu(SubMenuBuilder menu) {
        }

        public void onMenuModeChange(MenuBuilder menu) {
            if (mCallback == null) {
                return;
            }
            invalidate();
            mContextView.showOverflowMenu();
        }
    }

    /**
     * @hide
     */
    public class TabImpl extends ActionBar.Tab {
        private ActionBar.TabListener mCallback;
        private Object mTag;
        private Drawable mIcon;
        private CharSequence mText;
        private CharSequence mContentDesc;
        private int mPosition = -1;
        private View mCustomView;

        @Override
        public Object getTag() {
            return mTag;
        }

        @Override
        public Tab setTag(Object tag) {
            mTag = tag;
            return this;
        }

        public ActionBar.TabListener getCallback() {
            return mCallback;
        }

        @Override
        public Tab setTabListener(ActionBar.TabListener callback) {
            mCallback = callback;
            return this;
        }

        @Override
        public View getCustomView() {
            return mCustomView;
        }

        @Override
        public Tab setCustomView(View view) {
            mCustomView = view;
            if (mPosition >= 0) {
                mTabScrollView.updateTab(mPosition);
            }
            return this;
        }

        @Override
        public Tab setCustomView(int layoutResId) {
            return setCustomView(LayoutInflater.from(getThemedContext())
                    .inflate(layoutResId, null));
        }

        @Override
        public Drawable getIcon() {
            return mIcon;
        }

        @Override
        public int getPosition() {
            return mPosition;
        }

        public void setPosition(int position) {
            mPosition = position;
        }

        @Override
        public CharSequence getText() {
            return mText;
        }

        @Override
        public Tab setIcon(Drawable icon) {
            mIcon = icon;
            if (mPosition >= 0) {
                mTabScrollView.updateTab(mPosition);
            }
            return this;
        }

        @Override
        public Tab setIcon(int resId) {
            return setIcon(mContext.getDrawable(resId));
        }

        @Override
        public Tab setText(CharSequence text) {
            mText = text;
            if (mPosition >= 0) {
                mTabScrollView.updateTab(mPosition);
            }
            return this;
        }

        @Override
        public Tab setText(int resId) {
            return setText(mContext.getResources().getText(resId));
        }

        @Override
        public void select() {
            selectTab(this);
        }

        @Override
        public Tab setContentDescription(int resId) {
            return setContentDescription(mContext.getResources().getText(resId));
        }

        @Override
        public Tab setContentDescription(CharSequence contentDesc) {
            mContentDesc = contentDesc;
            if (mPosition >= 0) {
                mTabScrollView.updateTab(mPosition);
            }
            return this;
        }

        @Override
        public CharSequence getContentDescription() {
            return mContentDesc;
        }
    }

    @Override
    public void setCustomView(View view) {
        mDecorToolbar.setCustomView(view);
    }

    @Override
    public void setCustomView(View view, LayoutParams layoutParams) {
        view.setLayoutParams(layoutParams);
        mDecorToolbar.setCustomView(view);
    }

    @Override
    public void setListNavigationCallbacks(SpinnerAdapter adapter, OnNavigationListener callback) {
        mDecorToolbar.setDropdownParams(adapter, new NavItemSelectedListener(callback));
    }

    @Override
    public int getSelectedNavigationIndex() {
        switch (mDecorToolbar.getNavigationMode()) {
            case NAVIGATION_MODE_TABS:
                return mSelectedTab != null ? mSelectedTab.getPosition() : -1;
            case NAVIGATION_MODE_LIST:
                return mDecorToolbar.getDropdownSelectedPosition();
            default:
                return -1;
        }
    }

    @Override
    public int getNavigationItemCount() {
        switch (mDecorToolbar.getNavigationMode()) {
            case NAVIGATION_MODE_TABS:
                return mTabs.size();
            case NAVIGATION_MODE_LIST:
                return mDecorToolbar.getDropdownItemCount();
            default:
                return 0;
        }
    }

    @Override
    public int getTabCount() {
        return mTabs.size();
    }

    @Override
    public void setNavigationMode(int mode) {
        final int oldMode = mDecorToolbar.getNavigationMode();
        switch (oldMode) {
            case NAVIGATION_MODE_TABS:
                mSavedTabPosition = getSelectedNavigationIndex();
                selectTab(null);
                mTabScrollView.setVisibility(View.GONE);
                break;
        }
        if (oldMode != mode && !mHasEmbeddedTabs) {
            if (mOverlayLayout != null) {
                mOverlayLayout.requestFitSystemWindows();
            }
        }
        mDecorToolbar.setNavigationMode(mode);
        switch (mode) {
            case NAVIGATION_MODE_TABS:
                ensureTabsExist();
                mTabScrollView.setVisibility(View.VISIBLE);
                if (mSavedTabPosition != INVALID_POSITION) {
                    setSelectedNavigationItem(mSavedTabPosition);
                    mSavedTabPosition = INVALID_POSITION;
                }
                break;
        }
        mDecorToolbar.setCollapsible(mode == NAVIGATION_MODE_TABS && !mHasEmbeddedTabs);
        mOverlayLayout.setHasNonEmbeddedTabs(mode == NAVIGATION_MODE_TABS && !mHasEmbeddedTabs);
    }

    @Override
    public Tab getTabAt(int index) {
        return mTabs.get(index);
    }


    @Override
    public void setIcon(int resId) {
        mDecorToolbar.setIcon(resId);
    }

    @Override
    public void setIcon(Drawable icon) {
        mDecorToolbar.setIcon(icon);
    }

    public boolean hasIcon() {
        return mDecorToolbar.hasIcon();
    }

    @Override
    public void setLogo(int resId) {
        mDecorToolbar.setLogo(resId);
    }

    @Override
    public void setLogo(Drawable logo) {
        mDecorToolbar.setLogo(logo);
    }

    public boolean hasLogo() {
        return mDecorToolbar.hasLogo();
    }

    public void setDefaultDisplayHomeAsUpEnabled(boolean enable) {
        if (!mDisplayHomeAsUpSet) {
            setDisplayHomeAsUpEnabled(enable);
        }
    }

}
