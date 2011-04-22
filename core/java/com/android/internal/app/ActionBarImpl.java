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

import com.android.internal.view.menu.MenuBuilder;
import com.android.internal.view.menu.MenuPopupHelper;
import com.android.internal.view.menu.SubMenuBuilder;
import com.android.internal.widget.ActionBarContainer;
import com.android.internal.widget.ActionBarContextView;
import com.android.internal.widget.ActionBarView;

import android.animation.Animator;
import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.app.ActionBar;
import android.app.Activity;
import android.app.Dialog;
import android.app.FragmentTransaction;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.SpinnerAdapter;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * ActionBarImpl is the ActionBar implementation used
 * by devices of all screen sizes. If it detects a compatible decor,
 * it will split contextual modes across both the ActionBarView at
 * the top of the screen and a horizontal LinearLayout at the bottom
 * which is normally hidden.
 */
public class ActionBarImpl extends ActionBar {
    private static final String TAG = "ActionBarImpl";
    private static final int NORMAL_VIEW = 0;
    private static final int CONTEXT_VIEW = 1;

    private Context mContext;
    private Activity mActivity;
    private Dialog mDialog;

    private ActionBarContainer mContainerView;
    private ActionBarView mActionView;
    private ActionBarContextView mUpperContextView;
    private LinearLayout mLowerContextView;
    private View mContentView;

    private ArrayList<TabImpl> mTabs = new ArrayList<TabImpl>();

    private TabImpl mSelectedTab;
    private int mSavedTabPosition = INVALID_POSITION;
    
    private ActionMode mActionMode;
    
    private boolean mLastMenuVisibility;
    private ArrayList<OnMenuVisibilityListener> mMenuVisibilityListeners =
            new ArrayList<OnMenuVisibilityListener>();

    private static final int CONTEXT_DISPLAY_NORMAL = 0;
    private static final int CONTEXT_DISPLAY_SPLIT = 1;
    
    private static final int INVALID_POSITION = -1;

    private int mContextDisplayMode;

    final Handler mHandler = new Handler();

    private Animator mCurrentShowAnim;
    private Animator mCurrentModeAnim;
    private boolean mShowHideAnimationEnabled;
    boolean mWasHiddenBeforeMode;

    private static final TimeInterpolator sFadeOutInterpolator = new DecelerateInterpolator();

    final AnimatorListener[] mAfterAnimation = new AnimatorListener[] {
            new AnimatorListenerAdapter() { // NORMAL_VIEW
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (mLowerContextView != null) {
                        mLowerContextView.removeAllViews();
                    }
                    mCurrentModeAnim = null;
                    hideAllExcept(NORMAL_VIEW);
                }
            },
            new AnimatorListenerAdapter() { // CONTEXT_VIEW
                @Override
                public void onAnimationEnd(Animator animation) {
                    mCurrentModeAnim = null;
                    hideAllExcept(CONTEXT_VIEW);
                }
            }
    };

    final AnimatorListener mHideListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            if (mContentView != null) {
                mContentView.setTranslationY(0);
            }
            mContainerView.setVisibility(View.GONE);
            mContainerView.setTransitioning(false);
            mCurrentShowAnim = null;
        }
    };

    final AnimatorListener mShowListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            mCurrentShowAnim = null;
            mContainerView.requestLayout();
        }
    };

    public ActionBarImpl(Activity activity) {
        mActivity = activity;
        Window window = activity.getWindow();
        View decor = window.getDecorView();
        init(decor);
        if (!mActivity.getWindow().hasFeature(Window.FEATURE_ACTION_BAR_OVERLAY)) {
            mContentView = decor.findViewById(android.R.id.content);
        }
    }

    public ActionBarImpl(Dialog dialog) {
        mDialog = dialog;
        init(dialog.getWindow().getDecorView());
    }

    private void init(View decor) {
        mContext = decor.getContext();
        mActionView = (ActionBarView) decor.findViewById(com.android.internal.R.id.action_bar);
        mUpperContextView = (ActionBarContextView) decor.findViewById(
                com.android.internal.R.id.action_context_bar);
        mLowerContextView = (LinearLayout) decor.findViewById(
                com.android.internal.R.id.lower_action_context_bar);
        mContainerView = (ActionBarContainer) decor.findViewById(
                com.android.internal.R.id.action_bar_container);

        if (mActionView == null || mUpperContextView == null || mContainerView == null) {
            throw new IllegalStateException(getClass().getSimpleName() + " can only be used " +
                    "with a compatible window decor layout");
        }

        mActionView.setContextView(mUpperContextView);
        mContextDisplayMode = mLowerContextView == null ?
                CONTEXT_DISPLAY_NORMAL : CONTEXT_DISPLAY_SPLIT;
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
        setCustomView(LayoutInflater.from(mContext).inflate(resId, mActionView, false));
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
    public void setTitle(int resId) {
        setTitle(mContext.getString(resId));
    }

    @Override
    public void setSubtitle(int resId) {
        setSubtitle(mContext.getString(resId));
    }

    public void setSelectedNavigationItem(int position) {
        switch (mActionView.getNavigationMode()) {
        case NAVIGATION_MODE_TABS:
            selectTab(mTabs.get(position));
            break;
        case NAVIGATION_MODE_LIST:
            mActionView.setDropdownSelectedPosition(position);
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
        mActionView.removeAllTabs();
        mSavedTabPosition = INVALID_POSITION;
    }

    public void setTitle(CharSequence title) {
        mActionView.setTitle(title);
    }

    public void setSubtitle(CharSequence subtitle) {
        mActionView.setSubtitle(subtitle);
    }

    public void setDisplayOptions(int options) {
        mActionView.setDisplayOptions(options);
    }

    public void setDisplayOptions(int options, int mask) {
        final int current = mActionView.getDisplayOptions(); 
        mActionView.setDisplayOptions((options & mask) | (current & ~mask));
    }

    public void setBackgroundDrawable(Drawable d) {
        mContainerView.setBackgroundDrawable(d);
    }

    public View getCustomView() {
        return mActionView.getCustomNavigationView();
    }

    public CharSequence getTitle() {
        return mActionView.getTitle();
    }

    public CharSequence getSubtitle() {
        return mActionView.getSubtitle();
    }

    public int getNavigationMode() {
        return mActionView.getNavigationMode();
    }

    public int getDisplayOptions() {
        return mActionView.getDisplayOptions();
    }

    public ActionMode startActionMode(ActionMode.Callback callback) {
        if (mActionMode != null) {
            mActionMode.finish();
        }

        mUpperContextView.killMode();
        ActionMode mode = new ActionModeImpl(callback);
        if (callback.onCreateActionMode(mode, mode.getMenu())) {
            mWasHiddenBeforeMode = !isShowing();
            mode.invalidate();
            mUpperContextView.initForMode(mode);
            animateTo(CONTEXT_VIEW);
            if (mLowerContextView != null) {
                // TODO animate this
                mLowerContextView.setVisibility(View.VISIBLE);
            }
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
        mActionView.addTab(tab, setSelected);
        configureTab(tab, mTabs.size());
        if (setSelected) {
            selectTab(tab);
        }
    }

    @Override
    public void addTab(Tab tab, int position, boolean setSelected) {
        mActionView.addTab(tab, position, setSelected);
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
        int selectedTabPosition = mSelectedTab != null
                ? mSelectedTab.getPosition() : mSavedTabPosition;
        mActionView.removeTabAt(position);
        mTabs.remove(position);

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

        final FragmentTransaction trans = mActivity.getFragmentManager().beginTransaction()
                .disallowAddToBackStack();

        if (mSelectedTab == tab) {
            if (mSelectedTab != null) {
                mSelectedTab.getCallback().onTabReselected(mSelectedTab, trans);
            }
        } else {
            mActionView.setTabSelected(tab != null ? tab.getPosition() : Tab.INVALID_POSITION);
            if (mSelectedTab != null) {
                mSelectedTab.getCallback().onTabUnselected(mSelectedTab, trans);
            }
            mSelectedTab = (TabImpl) tab;
            if (mSelectedTab != null) {
                mSelectedTab.getCallback().onTabSelected(mSelectedTab, trans);
            }
        }

        if (!trans.isEmpty()) {
            trans.commit();
        }
    }

    @Override
    public Tab getSelectedTab() {
        return mSelectedTab;
    }

    @Override
    public int getHeight() {
        return mActionView.getHeight();
    }

    @Override
    public void show() {
        show(true);
    }

    void show(boolean markHiddenBeforeMode) {
        if (mCurrentShowAnim != null) {
            mCurrentShowAnim.end();
        }
        if (mContainerView.getVisibility() == View.VISIBLE) {
            if (markHiddenBeforeMode) mWasHiddenBeforeMode = false;
            return;
        }
        mContainerView.setVisibility(View.VISIBLE);

        if (mShowHideAnimationEnabled) {
            mContainerView.setAlpha(0);
            AnimatorSet anim = new AnimatorSet();
            AnimatorSet.Builder b = anim.play(ObjectAnimator.ofFloat(mContainerView, "alpha", 1));
            if (mContentView != null) {
                b.with(ObjectAnimator.ofFloat(mContentView, "translationY",
                        -mContainerView.getHeight(), 0));
                mContainerView.setTranslationY(-mContainerView.getHeight());
                b.with(ObjectAnimator.ofFloat(mContainerView, "translationY", 0));
            }
            anim.addListener(mShowListener);
            mCurrentShowAnim = anim;
            anim.start();
        } else {
            mContainerView.setAlpha(1);
            mContainerView.setTranslationY(0);
            mShowListener.onAnimationEnd(null);
        }
    }

    @Override
    public void hide() {
        if (mCurrentShowAnim != null) {
            mCurrentShowAnim.end();
        }
        if (mContainerView.getVisibility() == View.GONE) {
            return;
        }

        if (mShowHideAnimationEnabled) {
            mContainerView.setAlpha(1);
            mContainerView.setTransitioning(true);
            AnimatorSet anim = new AnimatorSet();
            AnimatorSet.Builder b = anim.play(ObjectAnimator.ofFloat(mContainerView, "alpha", 0));
            if (mContentView != null) {
                b.with(ObjectAnimator.ofFloat(mContentView, "translationY",
                        0, -mContainerView.getHeight()));
                b.with(ObjectAnimator.ofFloat(mContainerView, "translationY",
                        -mContainerView.getHeight()));
            }
            anim.addListener(mHideListener);
            mCurrentShowAnim = anim;
            anim.start();
        } else {
            mHideListener.onAnimationEnd(null);
        }
    }

    public boolean isShowing() {
        return mContainerView.getVisibility() == View.VISIBLE;
    }

    long animateTo(int viewIndex) {
        show(false);
        if (mCurrentModeAnim != null) {
            mCurrentModeAnim.end();
        }

        AnimatorSet set = new AnimatorSet();

        final View targetChild = mContainerView.getChildAt(viewIndex);
        targetChild.setVisibility(View.VISIBLE);
        targetChild.setAlpha(0);
        AnimatorSet.Builder b = set.play(ObjectAnimator.ofFloat(targetChild, "alpha", 1));

        final int count = mContainerView.getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = mContainerView.getChildAt(i);
            if (i == viewIndex) {
                continue;
            }

            if (child.getVisibility() != View.GONE) {
                Animator a = ObjectAnimator.ofFloat(child, "alpha", 0);
                a.setInterpolator(sFadeOutInterpolator);
                b.with(a);
            }
        }

        set.addListener(mAfterAnimation[viewIndex]);

        mCurrentModeAnim = set;
        set.start();
        return set.getDuration();
    }

    private void hideAllExcept(int viewIndex) {
        final int count = mContainerView.getChildCount();
        for (int i = 0; i < count; i++) {
            mContainerView.getChildAt(i).setVisibility(i == viewIndex ? View.VISIBLE : View.GONE);
        }
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
            mMenu = new MenuBuilder(mActionView.getContext())
                    .setDefaultShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            mMenu.setCallback(this);
        }

        @Override
        public MenuInflater getMenuInflater() {
            return new MenuInflater(mContext);
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

            mCallback.onDestroyActionMode(this);
            mCallback = null;
            animateTo(NORMAL_VIEW);

            // Clear out the context mode views after the animation finishes
            mUpperContextView.closeMode();
            if (mLowerContextView != null && mLowerContextView.getVisibility() != View.GONE) {
                // TODO Animate this
                mLowerContextView.setVisibility(View.GONE);
            }
            mActionMode = null;

            if (mWasHiddenBeforeMode) {
                hide();
            }
        }

        @Override
        public void invalidate() {
            if (mCallback.onPrepareActionMode(this, mMenu)) {
                // Refresh content in both context views
            }
        }

        @Override
        public void setCustomView(View view) {
            mUpperContextView.setCustomView(view);
            mCustomView = new WeakReference<View>(view);
        }

        @Override
        public void setSubtitle(CharSequence subtitle) {
            mUpperContextView.setSubtitle(subtitle);
        }

        @Override
        public void setTitle(CharSequence title) {
            mUpperContextView.setTitle(title);
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
            return mUpperContextView.getTitle();
        }

        @Override
        public CharSequence getSubtitle() {
            return mUpperContextView.getSubtitle();
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

            new MenuPopupHelper(mContext, subMenu).show();
            return true;
        }

        public void onCloseSubMenu(SubMenuBuilder menu) {
        }

        public void onMenuModeChange(MenuBuilder menu) {
            if (mCallback == null) {
                return;
            }
            invalidate();
            mUpperContextView.showOverflowMenu();
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
        private int mPosition;
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
            return this;
        }

        @Override
        public Tab setCustomView(int layoutResId) {
            return setCustomView(LayoutInflater.from(mContext).inflate(layoutResId, null));
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
            return this;
        }

        @Override
        public Tab setIcon(int resId) {
            return setIcon(mContext.getResources().getDrawable(resId));
        }

        @Override
        public Tab setText(CharSequence text) {
            mText = text;
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
    }

    @Override
    public void setCustomView(View view) {
        mActionView.setCustomNavigationView(view);
    }

    @Override
    public void setCustomView(View view, LayoutParams layoutParams) {
        view.setLayoutParams(layoutParams);
        mActionView.setCustomNavigationView(view);
    }

    @Override
    public void setListNavigationCallbacks(SpinnerAdapter adapter, OnNavigationListener callback) {
        mActionView.setDropdownAdapter(adapter);
        mActionView.setCallback(callback);
    }

    @Override
    public int getSelectedNavigationIndex() {
        switch (mActionView.getNavigationMode()) {
            case NAVIGATION_MODE_TABS:
                return mSelectedTab != null ? mSelectedTab.getPosition() : -1;
            case NAVIGATION_MODE_LIST:
                return mActionView.getDropdownSelectedPosition();
            default:
                return -1;
        }
    }

    @Override
    public int getNavigationItemCount() {
        switch (mActionView.getNavigationMode()) {
            case NAVIGATION_MODE_TABS:
                return mTabs.size();
            case NAVIGATION_MODE_LIST:
                SpinnerAdapter adapter = mActionView.getDropdownAdapter();
                return adapter != null ? adapter.getCount() : 0;
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
        final int oldMode = mActionView.getNavigationMode();
        switch (oldMode) {
            case NAVIGATION_MODE_TABS:
                mSavedTabPosition = getSelectedNavigationIndex();
                selectTab(null);
                break;
        }
        mActionView.setNavigationMode(mode);
        switch (mode) {
            case NAVIGATION_MODE_TABS:
                if (mSavedTabPosition != INVALID_POSITION) {
                    setSelectedNavigationItem(mSavedTabPosition);
                    mSavedTabPosition = INVALID_POSITION;
                }
                break;
        }
    }

    @Override
    public Tab getTabAt(int index) {
        return mTabs.get(index);
    }


    @Override
    public void setIcon(int resId) {
        mActionView.setIcon(resId);
    }

    @Override
    public void setIcon(Drawable icon) {
        mActionView.setIcon(icon);
    }

    @Override
    public void setLogo(int resId) {
        mActionView.setLogo(resId);
    }

    @Override
    public void setLogo(Drawable logo) {
        mActionView.setLogo(logo);
    }
}
