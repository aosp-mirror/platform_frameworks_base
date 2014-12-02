/*
 * Copyright (C) 2014 The Android Open Source Project
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


package com.android.internal.widget;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.ActionBar;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ActionMenuPresenter;
import android.widget.AdapterView;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.Toolbar;
import com.android.internal.R;
import com.android.internal.view.menu.ActionMenuItem;
import com.android.internal.view.menu.MenuBuilder;
import com.android.internal.view.menu.MenuPresenter;

/**
 * Internal class used to interact with the Toolbar widget without
 * exposing interface methods to the public API.
 *
 * <p>ToolbarWidgetWrapper manages the differences between Toolbar and ActionBarView
 * so that either variant acting as a
 * {@link com.android.internal.app.WindowDecorActionBar WindowDecorActionBar} can behave
 * in the same way.</p>
 *
 * @hide
 */
public class ToolbarWidgetWrapper implements DecorToolbar {
    private static final String TAG = "ToolbarWidgetWrapper";

    private static final int AFFECTS_LOGO_MASK =
            ActionBar.DISPLAY_SHOW_HOME | ActionBar.DISPLAY_USE_LOGO;

    private Toolbar mToolbar;

    private int mDisplayOpts;
    private View mTabView;
    private Spinner mSpinner;
    private View mCustomView;

    private Drawable mIcon;
    private Drawable mLogo;
    private Drawable mNavIcon;

    private boolean mTitleSet;
    private CharSequence mTitle;
    private CharSequence mSubtitle;
    private CharSequence mHomeDescription;

    private Window.Callback mWindowCallback;
    private boolean mMenuPrepared;
    private ActionMenuPresenter mActionMenuPresenter;

    private int mNavigationMode = ActionBar.NAVIGATION_MODE_STANDARD;
    private int mDefaultNavigationContentDescription = 0;
    private Drawable mDefaultNavigationIcon;

    public ToolbarWidgetWrapper(Toolbar toolbar, boolean style) {
        this(toolbar, style, R.string.action_bar_up_description);
    }

    public ToolbarWidgetWrapper(Toolbar toolbar, boolean style,
            int defaultNavigationContentDescription) {
        mToolbar = toolbar;

        mTitle = toolbar.getTitle();
        mSubtitle = toolbar.getSubtitle();
        mTitleSet = mTitle != null;
        mNavIcon = mToolbar.getNavigationIcon();
        final TypedArray a = toolbar.getContext().obtainStyledAttributes(null,
                R.styleable.ActionBar, R.attr.actionBarStyle, 0);
        mDefaultNavigationIcon = a.getDrawable(R.styleable.ActionBar_homeAsUpIndicator);
        if (style) {
            final CharSequence title = a.getText(R.styleable.ActionBar_title);
            if (!TextUtils.isEmpty(title)) {
                setTitle(title);
            }

            final CharSequence subtitle = a.getText(R.styleable.ActionBar_subtitle);
            if (!TextUtils.isEmpty(subtitle)) {
                setSubtitle(subtitle);
            }

            final Drawable logo = a.getDrawable(R.styleable.ActionBar_logo);
            if (logo != null) {
                setLogo(logo);
            }

            final Drawable icon = a.getDrawable(R.styleable.ActionBar_icon);
            if (icon != null) {
                setIcon(icon);
            }
            if (mNavIcon == null && mDefaultNavigationIcon != null) {
                setNavigationIcon(mDefaultNavigationIcon);
            }
            setDisplayOptions(a.getInt(R.styleable.ActionBar_displayOptions, 0));

            final int customNavId = a.getResourceId(
                    R.styleable.ActionBar_customNavigationLayout, 0);
            if (customNavId != 0) {
                setCustomView(LayoutInflater.from(mToolbar.getContext()).inflate(customNavId,
                        mToolbar, false));
                setDisplayOptions(mDisplayOpts | ActionBar.DISPLAY_SHOW_CUSTOM);
            }

            final int height = a.getLayoutDimension(R.styleable.ActionBar_height, 0);
            if (height > 0) {
                final ViewGroup.LayoutParams lp = mToolbar.getLayoutParams();
                lp.height = height;
                mToolbar.setLayoutParams(lp);
            }

            final int contentInsetStart = a.getDimensionPixelOffset(
                    R.styleable.ActionBar_contentInsetStart, -1);
            final int contentInsetEnd = a.getDimensionPixelOffset(
                    R.styleable.ActionBar_contentInsetEnd, -1);
            if (contentInsetStart >= 0 || contentInsetEnd >= 0) {
                mToolbar.setContentInsetsRelative(Math.max(contentInsetStart, 0),
                        Math.max(contentInsetEnd, 0));
            }

            final int titleTextStyle = a.getResourceId(R.styleable.ActionBar_titleTextStyle, 0);
            if (titleTextStyle != 0) {
                mToolbar.setTitleTextAppearance(mToolbar.getContext(), titleTextStyle);
            }

            final int subtitleTextStyle = a.getResourceId(
                    R.styleable.ActionBar_subtitleTextStyle, 0);
            if (subtitleTextStyle != 0) {
                mToolbar.setSubtitleTextAppearance(mToolbar.getContext(), subtitleTextStyle);
            }

            final int popupTheme = a.getResourceId(R.styleable.ActionBar_popupTheme, 0);
            if (popupTheme != 0) {
                mToolbar.setPopupTheme(popupTheme);
            }
        } else {
            mDisplayOpts = detectDisplayOptions();
        }
        a.recycle();

        setDefaultNavigationContentDescription(defaultNavigationContentDescription);
        mHomeDescription = mToolbar.getNavigationContentDescription();

        mToolbar.setNavigationOnClickListener(new View.OnClickListener() {
            final ActionMenuItem mNavItem = new ActionMenuItem(mToolbar.getContext(),
                    0, android.R.id.home, 0, 0, mTitle);
            @Override
            public void onClick(View v) {
                if (mWindowCallback != null && mMenuPrepared) {
                    mWindowCallback.onMenuItemSelected(Window.FEATURE_OPTIONS_PANEL, mNavItem);
                }
            }
        });
    }

    @Override
    public void setDefaultNavigationContentDescription(int defaultNavigationContentDescription) {
        if (defaultNavigationContentDescription == mDefaultNavigationContentDescription) {
            return;
        }
        mDefaultNavigationContentDescription = defaultNavigationContentDescription;
        if (TextUtils.isEmpty(mToolbar.getNavigationContentDescription())) {
            setNavigationContentDescription(mDefaultNavigationContentDescription);
        }
    }

    private int detectDisplayOptions() {
        int opts = ActionBar.DISPLAY_SHOW_TITLE | ActionBar.DISPLAY_SHOW_HOME |
                ActionBar.DISPLAY_USE_LOGO;
        if (mToolbar.getNavigationIcon() != null) {
            opts |= ActionBar.DISPLAY_HOME_AS_UP;
            mDefaultNavigationIcon = mToolbar.getNavigationIcon();
        }
        return opts;
    }

    @Override
    public ViewGroup getViewGroup() {
        return mToolbar;
    }

    @Override
    public Context getContext() {
        return mToolbar.getContext();
    }

    @Override
    public boolean isSplit() {
        return false;
    }

    @Override
    public boolean hasExpandedActionView() {
        return mToolbar.hasExpandedActionView();
    }

    @Override
    public void collapseActionView() {
        mToolbar.collapseActionView();
    }

    @Override
    public void setWindowCallback(Window.Callback cb) {
        mWindowCallback = cb;
    }

    @Override
    public void setWindowTitle(CharSequence title) {
        // "Real" title always trumps window title.
        if (!mTitleSet) {
            setTitleInt(title);
        }
    }

    @Override
    public CharSequence getTitle() {
        return mToolbar.getTitle();
    }

    @Override
    public void setTitle(CharSequence title) {
        mTitleSet = true;
        setTitleInt(title);
    }

    private void setTitleInt(CharSequence title) {
        mTitle = title;
        if ((mDisplayOpts & ActionBar.DISPLAY_SHOW_TITLE) != 0) {
            mToolbar.setTitle(title);
        }
    }

    @Override
    public CharSequence getSubtitle() {
        return mToolbar.getSubtitle();
    }

    @Override
    public void setSubtitle(CharSequence subtitle) {
        mSubtitle = subtitle;
        if ((mDisplayOpts & ActionBar.DISPLAY_SHOW_TITLE) != 0) {
            mToolbar.setSubtitle(subtitle);
        }
    }

    @Override
    public void initProgress() {
        Log.i(TAG, "Progress display unsupported");
    }

    @Override
    public void initIndeterminateProgress() {
        Log.i(TAG, "Progress display unsupported");
    }

    @Override
    public boolean canSplit() {
        return false;
    }

    @Override
    public void setSplitView(ViewGroup splitView) {
    }

    @Override
    public void setSplitToolbar(boolean split) {
        if (split) {
            throw new UnsupportedOperationException("Cannot split an android.widget.Toolbar");
        }
    }

    @Override
    public void setSplitWhenNarrow(boolean splitWhenNarrow) {
        // Ignore.
    }

    @Override
    public boolean hasIcon() {
        return mIcon != null;
    }

    @Override
    public boolean hasLogo() {
        return mLogo != null;
    }

    @Override
    public void setIcon(int resId) {
        setIcon(resId != 0 ? getContext().getDrawable(resId) : null);
    }

    @Override
    public void setIcon(Drawable d) {
        mIcon = d;
        updateToolbarLogo();
    }

    @Override
    public void setLogo(int resId) {
        setLogo(resId != 0 ? getContext().getDrawable(resId) : null);
    }

    @Override
    public void setLogo(Drawable d) {
        mLogo = d;
        updateToolbarLogo();
    }

    private void updateToolbarLogo() {
        Drawable logo = null;
        if ((mDisplayOpts & ActionBar.DISPLAY_SHOW_HOME) != 0) {
            if ((mDisplayOpts & ActionBar.DISPLAY_USE_LOGO) != 0) {
                logo = mLogo != null ? mLogo : mIcon;
            } else {
                logo = mIcon;
            }
        }
        mToolbar.setLogo(logo);
    }

    @Override
    public boolean canShowOverflowMenu() {
        return mToolbar.canShowOverflowMenu();
    }

    @Override
    public boolean isOverflowMenuShowing() {
        return mToolbar.isOverflowMenuShowing();
    }

    @Override
    public boolean isOverflowMenuShowPending() {
        return mToolbar.isOverflowMenuShowPending();
    }

    @Override
    public boolean showOverflowMenu() {
        return mToolbar.showOverflowMenu();
    }

    @Override
    public boolean hideOverflowMenu() {
        return mToolbar.hideOverflowMenu();
    }

    @Override
    public void setMenuPrepared() {
        mMenuPrepared = true;
    }

    @Override
    public void setMenu(Menu menu, MenuPresenter.Callback cb) {
        if (mActionMenuPresenter == null) {
            mActionMenuPresenter = new ActionMenuPresenter(mToolbar.getContext());
            mActionMenuPresenter.setId(com.android.internal.R.id.action_menu_presenter);
        }
        mActionMenuPresenter.setCallback(cb);
        mToolbar.setMenu((MenuBuilder) menu, mActionMenuPresenter);
    }

    @Override
    public void dismissPopupMenus() {
        mToolbar.dismissPopupMenus();
    }

    @Override
    public int getDisplayOptions() {
        return mDisplayOpts;
    }

    @Override
    public void setDisplayOptions(int newOpts) {
        final int oldOpts = mDisplayOpts;
        final int changed = oldOpts ^ newOpts;
        mDisplayOpts = newOpts;
        if (changed != 0) {
            if ((changed & ActionBar.DISPLAY_HOME_AS_UP) != 0) {
                if ((newOpts & ActionBar.DISPLAY_HOME_AS_UP) != 0) {
                    updateHomeAccessibility();
                }
                updateNavigationIcon();
            }

            if ((changed & AFFECTS_LOGO_MASK) != 0) {
                updateToolbarLogo();
            }

            if ((changed & ActionBar.DISPLAY_SHOW_TITLE) != 0) {
                if ((newOpts & ActionBar.DISPLAY_SHOW_TITLE) != 0) {
                    mToolbar.setTitle(mTitle);
                    mToolbar.setSubtitle(mSubtitle);
                } else {
                    mToolbar.setTitle(null);
                    mToolbar.setSubtitle(null);
                }
            }

            if ((changed & ActionBar.DISPLAY_SHOW_CUSTOM) != 0 && mCustomView != null) {
                if ((newOpts & ActionBar.DISPLAY_SHOW_CUSTOM) != 0) {
                    mToolbar.addView(mCustomView);
                } else {
                    mToolbar.removeView(mCustomView);
                }
            }
        }
    }

    @Override
    public void setEmbeddedTabView(ScrollingTabContainerView tabView) {
        if (mTabView != null && mTabView.getParent() == mToolbar) {
            mToolbar.removeView(mTabView);
        }
        mTabView = tabView;
        if (tabView != null && mNavigationMode == ActionBar.NAVIGATION_MODE_TABS) {
            mToolbar.addView(mTabView, 0);
            Toolbar.LayoutParams lp = (Toolbar.LayoutParams) mTabView.getLayoutParams();
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.START | Gravity.BOTTOM;
            tabView.setAllowCollapse(true);
        }
    }

    @Override
    public boolean hasEmbeddedTabs() {
        return mTabView != null;
    }

    @Override
    public boolean isTitleTruncated() {
        return mToolbar.isTitleTruncated();
    }

    @Override
    public void setCollapsible(boolean collapsible) {
        mToolbar.setCollapsible(collapsible);
    }

    @Override
    public void setHomeButtonEnabled(boolean enable) {
        // Ignore
    }

    @Override
    public int getNavigationMode() {
        return mNavigationMode;
    }

    @Override
    public void setNavigationMode(int mode) {
        final int oldMode = mNavigationMode;
        if (mode != oldMode) {
            switch (oldMode) {
                case ActionBar.NAVIGATION_MODE_LIST:
                    if (mSpinner != null && mSpinner.getParent() == mToolbar) {
                        mToolbar.removeView(mSpinner);
                    }
                    break;
                case ActionBar.NAVIGATION_MODE_TABS:
                    if (mTabView != null && mTabView.getParent() == mToolbar) {
                        mToolbar.removeView(mTabView);
                    }
                    break;
            }

            mNavigationMode = mode;

            switch (mode) {
                case ActionBar.NAVIGATION_MODE_STANDARD:
                    break;
                case ActionBar.NAVIGATION_MODE_LIST:
                    ensureSpinner();
                    mToolbar.addView(mSpinner, 0);
                    break;
                case ActionBar.NAVIGATION_MODE_TABS:
                    if (mTabView != null) {
                        mToolbar.addView(mTabView, 0);
                        Toolbar.LayoutParams lp = (Toolbar.LayoutParams) mTabView.getLayoutParams();
                        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                        lp.gravity = Gravity.START | Gravity.BOTTOM;
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Invalid navigation mode " + mode);
            }
        }
    }

    private void ensureSpinner() {
        if (mSpinner == null) {
            mSpinner = new Spinner(getContext(), null, R.attr.actionDropDownStyle);
            Toolbar.LayoutParams lp = new Toolbar.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL);
            mSpinner.setLayoutParams(lp);
        }
    }

    @Override
    public void setDropdownParams(SpinnerAdapter adapter,
            AdapterView.OnItemSelectedListener listener) {
        ensureSpinner();
        mSpinner.setAdapter(adapter);
        mSpinner.setOnItemSelectedListener(listener);
    }

    @Override
    public void setDropdownSelectedPosition(int position) {
        if (mSpinner == null) {
            throw new IllegalStateException(
                    "Can't set dropdown selected position without an adapter");
        }
        mSpinner.setSelection(position);
    }

    @Override
    public int getDropdownSelectedPosition() {
        return mSpinner != null ? mSpinner.getSelectedItemPosition() : 0;
    }

    @Override
    public int getDropdownItemCount() {
        return mSpinner != null ? mSpinner.getCount() : 0;
    }

    @Override
    public void setCustomView(View view) {
        if (mCustomView != null && (mDisplayOpts & ActionBar.DISPLAY_SHOW_CUSTOM) != 0) {
            mToolbar.removeView(mCustomView);
        }
        mCustomView = view;
        if (view != null && (mDisplayOpts & ActionBar.DISPLAY_SHOW_CUSTOM) != 0) {
            mToolbar.addView(mCustomView);
        }
    }

    @Override
    public View getCustomView() {
        return mCustomView;
    }

    @Override
    public void animateToVisibility(int visibility) {
        if (visibility == View.GONE) {
            mToolbar.animate().alpha(0)
                    .setListener(new AnimatorListenerAdapter() {
                        private boolean mCanceled = false;
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            if (!mCanceled) {
                                mToolbar.setVisibility(View.GONE);
                            }
                        }

                        @Override
                        public void onAnimationCancel(Animator animation) {
                            mCanceled = true;
                        }
                    });
        } else if (visibility == View.VISIBLE) {
            mToolbar.animate().alpha(1)
                    .setListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationStart(Animator animation) {
                            mToolbar.setVisibility(View.VISIBLE);
                        }
                    });
        }
    }

    @Override
    public void setNavigationIcon(Drawable icon) {
        mNavIcon = icon;
        updateNavigationIcon();
    }

    @Override
    public void setNavigationIcon(int resId) {
        setNavigationIcon(resId != 0 ? mToolbar.getContext().getDrawable(resId) : null);
    }

    @Override
    public void setDefaultNavigationIcon(Drawable defaultNavigationIcon) {
        if (mDefaultNavigationIcon != defaultNavigationIcon) {
            mDefaultNavigationIcon = defaultNavigationIcon;
            updateNavigationIcon();
        }
    }

    private void updateNavigationIcon() {
        if ((mDisplayOpts & ActionBar.DISPLAY_HOME_AS_UP) != 0) {
            mToolbar.setNavigationIcon(mNavIcon != null ? mNavIcon : mDefaultNavigationIcon);
        } else {
            mToolbar.setNavigationIcon(null);
        }
    }

    @Override
    public void setNavigationContentDescription(CharSequence description) {
        mHomeDescription = description;
        updateHomeAccessibility();
    }

    @Override
    public void setNavigationContentDescription(int resId) {
        setNavigationContentDescription(resId == 0 ? null : getContext().getString(resId));
    }

    private void updateHomeAccessibility() {
        if ((mDisplayOpts & ActionBar.DISPLAY_HOME_AS_UP) != 0) {
            if (TextUtils.isEmpty(mHomeDescription)) {
                mToolbar.setNavigationContentDescription(mDefaultNavigationContentDescription);
            } else {
                mToolbar.setNavigationContentDescription(mHomeDescription);
            }
        }
    }

    @Override
    public void saveHierarchyState(SparseArray<Parcelable> toolbarStates) {
        mToolbar.saveHierarchyState(toolbarStates);
    }

    @Override
    public void restoreHierarchyState(SparseArray<Parcelable> toolbarStates) {
        mToolbar.restoreHierarchyState(toolbarStates);
    }

    @Override
    public void setBackgroundDrawable(Drawable d) {
        //noinspection deprecation
        mToolbar.setBackgroundDrawable(d);
    }

    @Override
    public int getHeight() {
        return mToolbar.getHeight();
    }

    @Override
    public void setVisibility(int visible) {
        mToolbar.setVisibility(visible);
    }

    @Override
    public int getVisibility() {
        return mToolbar.getVisibility();
    }

    @Override
    public void setMenuCallbacks(MenuPresenter.Callback presenterCallback,
            MenuBuilder.Callback menuBuilderCallback) {
        mToolbar.setMenuCallbacks(presenterCallback, menuBuilderCallback);
    }

    @Override
    public Menu getMenu() {
        return mToolbar.getMenu();
    }

}
