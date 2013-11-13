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

package com.android.internal.widget;

import android.animation.LayoutTransition;
import android.app.ActionBar;
import android.app.ActionBar.OnNavigationListener;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.Layout;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.CollapsibleActionView;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.accessibility.AccessibilityEvent;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import com.android.internal.R;
import com.android.internal.transition.ActionBarTransition;
import com.android.internal.view.menu.ActionMenuItem;
import com.android.internal.view.menu.ActionMenuPresenter;
import com.android.internal.view.menu.ActionMenuView;
import com.android.internal.view.menu.MenuBuilder;
import com.android.internal.view.menu.MenuItemImpl;
import com.android.internal.view.menu.MenuPresenter;
import com.android.internal.view.menu.MenuView;
import com.android.internal.view.menu.SubMenuBuilder;

/**
 * @hide
 */
public class ActionBarView extends AbsActionBarView {
    private static final String TAG = "ActionBarView";

    /**
     * Display options applied by default
     */
    public static final int DISPLAY_DEFAULT = 0;

    /**
     * Display options that require re-layout as opposed to a simple invalidate
     */
    private static final int DISPLAY_RELAYOUT_MASK =
            ActionBar.DISPLAY_SHOW_HOME |
            ActionBar.DISPLAY_USE_LOGO |
            ActionBar.DISPLAY_HOME_AS_UP |
            ActionBar.DISPLAY_SHOW_CUSTOM |
            ActionBar.DISPLAY_SHOW_TITLE |
            ActionBar.DISPLAY_TITLE_MULTIPLE_LINES;

    private static final int DEFAULT_CUSTOM_GRAVITY = Gravity.START | Gravity.CENTER_VERTICAL;

    private int mNavigationMode;
    private int mDisplayOptions = -1;
    private CharSequence mTitle;
    private CharSequence mSubtitle;
    private Drawable mIcon;
    private Drawable mLogo;
    private CharSequence mHomeDescription;
    private int mHomeDescriptionRes;

    private HomeView mHomeLayout;
    private HomeView mExpandedHomeLayout;
    private LinearLayout mTitleLayout;
    private TextView mTitleView;
    private TextView mSubtitleView;
    private ViewGroup mUpGoerFive;

    private Spinner mSpinner;
    private LinearLayout mListNavLayout;
    private ScrollingTabContainerView mTabScrollView;
    private View mCustomNavView;
    private ProgressBar mProgressView;
    private ProgressBar mIndeterminateProgressView;

    private int mProgressBarPadding;
    private int mItemPadding;

    private int mTitleStyleRes;
    private int mSubtitleStyleRes;
    private int mProgressStyle;
    private int mIndeterminateProgressStyle;

    private boolean mUserTitle;
    private boolean mIncludeTabs;
    private boolean mIsCollapsable;
    private boolean mIsCollapsed;
    private boolean mWasHomeEnabled; // Was it enabled before action view expansion?

    private MenuBuilder mOptionsMenu;
    private boolean mMenuPrepared;

    private ActionBarContextView mContextView;

    private ActionMenuItem mLogoNavItem;

    private SpinnerAdapter mSpinnerAdapter;
    private OnNavigationListener mCallback;

    private Runnable mTabSelector;

    private ExpandedActionViewMenuPresenter mExpandedMenuPresenter;
    View mExpandedActionView;

    Window.Callback mWindowCallback;

    private final AdapterView.OnItemSelectedListener mNavItemSelectedListener =
            new AdapterView.OnItemSelectedListener() {
        public void onItemSelected(AdapterView parent, View view, int position, long id) {
            if (mCallback != null) {
                mCallback.onNavigationItemSelected(position, id);
            }
        }
        public void onNothingSelected(AdapterView parent) {
            // Do nothing
        }
    };

    private final OnClickListener mExpandedActionViewUpListener = new OnClickListener() {
        @Override
        public void onClick(View v) {
            final MenuItemImpl item = mExpandedMenuPresenter.mCurrentExpandedItem;
            if (item != null) {
                item.collapseActionView();
            }
        }
    };

    private final OnClickListener mUpClickListener = new OnClickListener() {
        public void onClick(View v) {
            if (mMenuPrepared) {
                // Only invoke the window callback if the options menu has been initialized.
                mWindowCallback.onMenuItemSelected(Window.FEATURE_OPTIONS_PANEL, mLogoNavItem);
            }
        }
    };

    public ActionBarView(Context context, AttributeSet attrs) {
        super(context, attrs);

        // Background is always provided by the container.
        setBackgroundResource(0);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ActionBar,
                com.android.internal.R.attr.actionBarStyle, 0);

        ApplicationInfo appInfo = context.getApplicationInfo();
        PackageManager pm = context.getPackageManager();
        mNavigationMode = a.getInt(R.styleable.ActionBar_navigationMode,
                ActionBar.NAVIGATION_MODE_STANDARD);
        mTitle = a.getText(R.styleable.ActionBar_title);
        mSubtitle = a.getText(R.styleable.ActionBar_subtitle);
        mLogo = a.getDrawable(R.styleable.ActionBar_logo);
        mIcon = a.getDrawable(R.styleable.ActionBar_icon);

        final LayoutInflater inflater = LayoutInflater.from(context);

        final int homeResId = a.getResourceId(
                com.android.internal.R.styleable.ActionBar_homeLayout,
                com.android.internal.R.layout.action_bar_home);

        mUpGoerFive = (ViewGroup) inflater.inflate(
                com.android.internal.R.layout.action_bar_up_container, this, false);
        mHomeLayout = (HomeView) inflater.inflate(homeResId, mUpGoerFive, false);

        mExpandedHomeLayout = (HomeView) inflater.inflate(homeResId, mUpGoerFive, false);
        mExpandedHomeLayout.setShowUp(true);
        mExpandedHomeLayout.setOnClickListener(mExpandedActionViewUpListener);
        mExpandedHomeLayout.setContentDescription(getResources().getText(
                R.string.action_bar_up_description));

        // This needs to highlight/be focusable on its own.
        // TODO: Clean up the handoff between expanded/normal.
        final Drawable upBackground = mUpGoerFive.getBackground();
        if (upBackground != null) {
            mExpandedHomeLayout.setBackground(upBackground.getConstantState().newDrawable());
        }
        mExpandedHomeLayout.setEnabled(true);
        mExpandedHomeLayout.setFocusable(true);

        mTitleStyleRes = a.getResourceId(R.styleable.ActionBar_titleTextStyle, 0);
        mSubtitleStyleRes = a.getResourceId(R.styleable.ActionBar_subtitleTextStyle, 0);
        mProgressStyle = a.getResourceId(R.styleable.ActionBar_progressBarStyle, 0);
        mIndeterminateProgressStyle = a.getResourceId(
                R.styleable.ActionBar_indeterminateProgressStyle, 0);

        mProgressBarPadding = a.getDimensionPixelOffset(R.styleable.ActionBar_progressBarPadding, 0);
        mItemPadding = a.getDimensionPixelOffset(R.styleable.ActionBar_itemPadding, 0);

        setDisplayOptions(a.getInt(R.styleable.ActionBar_displayOptions, DISPLAY_DEFAULT));

        final int customNavId = a.getResourceId(R.styleable.ActionBar_customNavigationLayout, 0);
        if (customNavId != 0) {
            mCustomNavView = (View) inflater.inflate(customNavId, this, false);
            mNavigationMode = ActionBar.NAVIGATION_MODE_STANDARD;
            setDisplayOptions(mDisplayOptions | ActionBar.DISPLAY_SHOW_CUSTOM);
        }

        mContentHeight = a.getLayoutDimension(R.styleable.ActionBar_height, 0);

        a.recycle();

        mLogoNavItem = new ActionMenuItem(context, 0, android.R.id.home, 0, 0, mTitle);

        mUpGoerFive.setOnClickListener(mUpClickListener);
        mUpGoerFive.setClickable(true);
        mUpGoerFive.setFocusable(true);

        if (getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_AUTO) {
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        mTitleView = null;
        mSubtitleView = null;
        if (mTitleLayout != null && mTitleLayout.getParent() == mUpGoerFive) {
            mUpGoerFive.removeView(mTitleLayout);
        }
        mTitleLayout = null;
        if ((mDisplayOptions & ActionBar.DISPLAY_SHOW_TITLE) != 0) {
            initTitle();
        }

        if (mHomeDescriptionRes != 0) {
            setHomeActionContentDescription(mHomeDescriptionRes);
        }

        if (mTabScrollView != null && mIncludeTabs) {
            ViewGroup.LayoutParams lp = mTabScrollView.getLayoutParams();
            if (lp != null) {
                lp.width = LayoutParams.WRAP_CONTENT;
                lp.height = LayoutParams.MATCH_PARENT;
            }
            mTabScrollView.setAllowCollapse(true);
        }
    }

    /**
     * Set the window callback used to invoke menu items; used for dispatching home button presses.
     * @param cb Window callback to dispatch to
     */
    public void setWindowCallback(Window.Callback cb) {
        mWindowCallback = cb;
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(mTabSelector);
        if (mActionMenuPresenter != null) {
            mActionMenuPresenter.hideOverflowMenu();
            mActionMenuPresenter.hideSubMenus();
        }
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return false;
    }

    public void initProgress() {
        mProgressView = new ProgressBar(mContext, null, 0, mProgressStyle);
        mProgressView.setId(R.id.progress_horizontal);
        mProgressView.setMax(10000);
        mProgressView.setVisibility(GONE);
        addView(mProgressView);
    }

    public void initIndeterminateProgress() {
        mIndeterminateProgressView = new ProgressBar(mContext, null, 0,
                mIndeterminateProgressStyle);
        mIndeterminateProgressView.setId(R.id.progress_circular);
        mIndeterminateProgressView.setVisibility(GONE);
        addView(mIndeterminateProgressView);
    }

    @Override
    public void setSplitActionBar(boolean splitActionBar) {
        if (mSplitActionBar != splitActionBar) {
            if (mMenuView != null) {
                final ViewGroup oldParent = (ViewGroup) mMenuView.getParent();
                if (oldParent != null) {
                    oldParent.removeView(mMenuView);
                }
                if (splitActionBar) {
                    if (mSplitView != null) {
                        mSplitView.addView(mMenuView);
                    }
                    mMenuView.getLayoutParams().width = LayoutParams.MATCH_PARENT;
                } else {
                    addView(mMenuView);
                    mMenuView.getLayoutParams().width = LayoutParams.WRAP_CONTENT;
                }
                mMenuView.requestLayout();
            }
            if (mSplitView != null) {
                mSplitView.setVisibility(splitActionBar ? VISIBLE : GONE);
            }

            if (mActionMenuPresenter != null) {
                if (!splitActionBar) {
                    mActionMenuPresenter.setExpandedActionViewsExclusive(
                            getResources().getBoolean(
                                    com.android.internal.R.bool.action_bar_expanded_action_views_exclusive));
                } else {
                    mActionMenuPresenter.setExpandedActionViewsExclusive(false);
                    // Allow full screen width in split mode.
                    mActionMenuPresenter.setWidthLimit(
                            getContext().getResources().getDisplayMetrics().widthPixels, true);
                    // No limit to the item count; use whatever will fit.
                    mActionMenuPresenter.setItemLimit(Integer.MAX_VALUE);
                }
            }
            super.setSplitActionBar(splitActionBar);
        }
    }

    public boolean isSplitActionBar() {
        return mSplitActionBar;
    }

    public boolean hasEmbeddedTabs() {
        return mIncludeTabs;
    }

    public void setEmbeddedTabView(ScrollingTabContainerView tabs) {
        if (mTabScrollView != null) {
            removeView(mTabScrollView);
        }
        mTabScrollView = tabs;
        mIncludeTabs = tabs != null;
        if (mIncludeTabs && mNavigationMode == ActionBar.NAVIGATION_MODE_TABS) {
            addView(mTabScrollView);
            ViewGroup.LayoutParams lp = mTabScrollView.getLayoutParams();
            lp.width = LayoutParams.WRAP_CONTENT;
            lp.height = LayoutParams.MATCH_PARENT;
            tabs.setAllowCollapse(true);
        }
    }

    public void setCallback(OnNavigationListener callback) {
        mCallback = callback;
    }

    public void setMenuPrepared() {
        mMenuPrepared = true;
    }

    public void setMenu(Menu menu, MenuPresenter.Callback cb) {
        if (menu == mOptionsMenu) return;

        if (mOptionsMenu != null) {
            mOptionsMenu.removeMenuPresenter(mActionMenuPresenter);
            mOptionsMenu.removeMenuPresenter(mExpandedMenuPresenter);
        }

        MenuBuilder builder = (MenuBuilder) menu;
        mOptionsMenu = builder;
        if (mMenuView != null) {
            final ViewGroup oldParent = (ViewGroup) mMenuView.getParent();
            if (oldParent != null) {
                oldParent.removeView(mMenuView);
            }
        }
        if (mActionMenuPresenter == null) {
            mActionMenuPresenter = new ActionMenuPresenter(mContext);
            mActionMenuPresenter.setCallback(cb);
            mActionMenuPresenter.setId(com.android.internal.R.id.action_menu_presenter);
            mExpandedMenuPresenter = new ExpandedActionViewMenuPresenter();
        }

        ActionMenuView menuView;
        final LayoutParams layoutParams = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT);
        if (!mSplitActionBar) {
            mActionMenuPresenter.setExpandedActionViewsExclusive(
                    getResources().getBoolean(
                    com.android.internal.R.bool.action_bar_expanded_action_views_exclusive));
            configPresenters(builder);
            menuView = (ActionMenuView) mActionMenuPresenter.getMenuView(this);
            final ViewGroup oldParent = (ViewGroup) menuView.getParent();
            if (oldParent != null && oldParent != this) {
                oldParent.removeView(menuView);
            }
            addView(menuView, layoutParams);
        } else {
            mActionMenuPresenter.setExpandedActionViewsExclusive(false);
            // Allow full screen width in split mode.
            mActionMenuPresenter.setWidthLimit(
                    getContext().getResources().getDisplayMetrics().widthPixels, true);
            // No limit to the item count; use whatever will fit.
            mActionMenuPresenter.setItemLimit(Integer.MAX_VALUE);
            // Span the whole width
            layoutParams.width = LayoutParams.MATCH_PARENT;
            configPresenters(builder);
            menuView = (ActionMenuView) mActionMenuPresenter.getMenuView(this);
            if (mSplitView != null) {
                final ViewGroup oldParent = (ViewGroup) menuView.getParent();
                if (oldParent != null && oldParent != mSplitView) {
                    oldParent.removeView(menuView);
                }
                menuView.setVisibility(getAnimatedVisibility());
                mSplitView.addView(menuView, layoutParams);
            } else {
                // We'll add this later if we missed it this time.
                menuView.setLayoutParams(layoutParams);
            }
        }
        mMenuView = menuView;
    }

    private void configPresenters(MenuBuilder builder) {
        if (builder != null) {
            builder.addMenuPresenter(mActionMenuPresenter);
            builder.addMenuPresenter(mExpandedMenuPresenter);
        } else {
            mActionMenuPresenter.initForMenu(mContext, null);
            mExpandedMenuPresenter.initForMenu(mContext, null);
            mActionMenuPresenter.updateMenuView(true);
            mExpandedMenuPresenter.updateMenuView(true);
        }
    }

    public boolean hasExpandedActionView() {
        return mExpandedMenuPresenter != null &&
                mExpandedMenuPresenter.mCurrentExpandedItem != null;
    }

    public void collapseActionView() {
        final MenuItemImpl item = mExpandedMenuPresenter == null ? null :
                mExpandedMenuPresenter.mCurrentExpandedItem;
        if (item != null) {
            item.collapseActionView();
        }
    }

    public void setCustomNavigationView(View view) {
        final boolean showCustom = (mDisplayOptions & ActionBar.DISPLAY_SHOW_CUSTOM) != 0;
        if (showCustom) {
            ActionBarTransition.beginDelayedTransition(this);
        }
        if (mCustomNavView != null && showCustom) {
            removeView(mCustomNavView);
        }
        mCustomNavView = view;
        if (mCustomNavView != null && showCustom) {
            addView(mCustomNavView);
        }
    }

    public CharSequence getTitle() {
        return mTitle;
    }

    /**
     * Set the action bar title. This will always replace or override window titles.
     * @param title Title to set
     *
     * @see #setWindowTitle(CharSequence)
     */
    public void setTitle(CharSequence title) {
        mUserTitle = true;
        setTitleImpl(title);
    }

    /**
     * Set the window title. A window title will always be replaced or overridden by a user title.
     * @param title Title to set
     *
     * @see #setTitle(CharSequence)
     */
    public void setWindowTitle(CharSequence title) {
        if (!mUserTitle) {
            setTitleImpl(title);
        }
    }

    private void setTitleImpl(CharSequence title) {
        ActionBarTransition.beginDelayedTransition(this);
        mTitle = title;
        if (mTitleView != null) {
            mTitleView.setText(title);
            final boolean visible = mExpandedActionView == null &&
                    (mDisplayOptions & ActionBar.DISPLAY_SHOW_TITLE) != 0 &&
                    (!TextUtils.isEmpty(mTitle) || !TextUtils.isEmpty(mSubtitle));
            mTitleLayout.setVisibility(visible ? VISIBLE : GONE);
        }
        if (mLogoNavItem != null) {
            mLogoNavItem.setTitle(title);
        }
        updateHomeAccessibility(mUpGoerFive.isEnabled());
    }

    public CharSequence getSubtitle() {
        return mSubtitle;
    }

    public void setSubtitle(CharSequence subtitle) {
        ActionBarTransition.beginDelayedTransition(this);
        mSubtitle = subtitle;
        if (mSubtitleView != null) {
            mSubtitleView.setText(subtitle);
            mSubtitleView.setVisibility(subtitle != null ? VISIBLE : GONE);
            final boolean visible = mExpandedActionView == null &&
                    (mDisplayOptions & ActionBar.DISPLAY_SHOW_TITLE) != 0 &&
                    (!TextUtils.isEmpty(mTitle) || !TextUtils.isEmpty(mSubtitle));
            mTitleLayout.setVisibility(visible ? VISIBLE : GONE);
        }
        updateHomeAccessibility(mUpGoerFive.isEnabled());
    }

    public void setHomeButtonEnabled(boolean enable) {
        setHomeButtonEnabled(enable, true);
    }

    private void setHomeButtonEnabled(boolean enable, boolean recordState) {
        if (recordState) {
            mWasHomeEnabled = enable;
        }

        if (mExpandedActionView != null) {
            // There's an action view currently showing and we want to keep the state
            // configured for the action view at the moment. If we needed to record the
            // new state for later we will have done so above.
            return;
        }

        mUpGoerFive.setEnabled(enable);
        mUpGoerFive.setFocusable(enable);
        // Make sure the home button has an accurate content description for accessibility.
        updateHomeAccessibility(enable);
    }

    private void updateHomeAccessibility(boolean homeEnabled) {
        if (!homeEnabled) {
            mUpGoerFive.setContentDescription(null);
            mUpGoerFive.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        } else {
            mUpGoerFive.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            mUpGoerFive.setContentDescription(buildHomeContentDescription());
        }
    }

    /**
     * Compose a content description for the Home/Up affordance.
     *
     * <p>As this encompasses the icon/logo, title and subtitle all in one, we need
     * a description for the whole wad of stuff that can be localized properly.</p>
     */
    private CharSequence buildHomeContentDescription() {
        final CharSequence homeDesc;
        if (mHomeDescription != null) {
            homeDesc = mHomeDescription;
        } else {
            if ((mDisplayOptions & ActionBar.DISPLAY_HOME_AS_UP) != 0) {
                homeDesc = mContext.getResources().getText(R.string.action_bar_up_description);
            } else {
                homeDesc = mContext.getResources().getText(R.string.action_bar_home_description);
            }
        }

        final CharSequence title = getTitle();
        final CharSequence subtitle = getSubtitle();
        if (!TextUtils.isEmpty(title)) {
            final String result;
            if (!TextUtils.isEmpty(subtitle)) {
                result = getResources().getString(
                        R.string.action_bar_home_subtitle_description_format,
                        title, subtitle, homeDesc);
            } else {
                result = getResources().getString(R.string.action_bar_home_description_format,
                        title, homeDesc);
            }
            return result;
        }
        return homeDesc;
    }

    public void setDisplayOptions(int options) {
        final int flagsChanged = mDisplayOptions == -1 ? -1 : options ^ mDisplayOptions;
        mDisplayOptions = options;

        if ((flagsChanged & DISPLAY_RELAYOUT_MASK) != 0) {
            ActionBarTransition.beginDelayedTransition(this);

            if ((flagsChanged & ActionBar.DISPLAY_HOME_AS_UP) != 0) {
                final boolean setUp = (options & ActionBar.DISPLAY_HOME_AS_UP) != 0;
                mHomeLayout.setShowUp(setUp);

                // Showing home as up implicitly enables interaction with it.
                // In honeycomb it was always enabled, so make this transition
                // a bit easier for developers in the common case.
                // (It would be silly to show it as up without responding to it.)
                if (setUp) {
                    setHomeButtonEnabled(true);
                }
            }

            if ((flagsChanged & ActionBar.DISPLAY_USE_LOGO) != 0) {
                final boolean logoVis = mLogo != null && (options & ActionBar.DISPLAY_USE_LOGO) != 0;
                mHomeLayout.setIcon(logoVis ? mLogo : mIcon);
            }

            if ((flagsChanged & ActionBar.DISPLAY_SHOW_TITLE) != 0) {
                if ((options & ActionBar.DISPLAY_SHOW_TITLE) != 0) {
                    initTitle();
                } else {
                    mUpGoerFive.removeView(mTitleLayout);
                }
            }

            final boolean showHome = (options & ActionBar.DISPLAY_SHOW_HOME) != 0;
            final boolean homeAsUp = (mDisplayOptions & ActionBar.DISPLAY_HOME_AS_UP) != 0;
            final boolean titleUp = !showHome && homeAsUp;
            mHomeLayout.setShowIcon(showHome);

            final int homeVis = (showHome || titleUp) && mExpandedActionView == null ?
                    VISIBLE : GONE;
            mHomeLayout.setVisibility(homeVis);

            if ((flagsChanged & ActionBar.DISPLAY_SHOW_CUSTOM) != 0 && mCustomNavView != null) {
                if ((options & ActionBar.DISPLAY_SHOW_CUSTOM) != 0) {
                    addView(mCustomNavView);
                } else {
                    removeView(mCustomNavView);
                }
            }

            if (mTitleLayout != null &&
                    (flagsChanged & ActionBar.DISPLAY_TITLE_MULTIPLE_LINES) != 0) {
                if ((options & ActionBar.DISPLAY_TITLE_MULTIPLE_LINES) != 0) {
                    mTitleView.setSingleLine(false);
                    mTitleView.setMaxLines(2);
                } else {
                    mTitleView.setMaxLines(1);
                    mTitleView.setSingleLine(true);
                }
            }

            requestLayout();
        } else {
            invalidate();
        }

        // Make sure the home button has an accurate content description for accessibility.
        updateHomeAccessibility(mUpGoerFive.isEnabled());
    }

    public void setIcon(Drawable icon) {
        mIcon = icon;
        if (icon != null &&
                ((mDisplayOptions & ActionBar.DISPLAY_USE_LOGO) == 0 || mLogo == null)) {
            mHomeLayout.setIcon(icon);
        }
        if (mExpandedActionView != null) {
            mExpandedHomeLayout.setIcon(mIcon.getConstantState().newDrawable(getResources()));
        }
    }

    public void setIcon(int resId) {
        setIcon(resId != 0 ? mContext.getResources().getDrawable(resId) : null);
    }

    public boolean hasIcon() {
        return mIcon != null;
    }

    public void setLogo(Drawable logo) {
        mLogo = logo;
        if (logo != null && (mDisplayOptions & ActionBar.DISPLAY_USE_LOGO) != 0) {
            mHomeLayout.setIcon(logo);
        }
    }

    public void setLogo(int resId) {
        setLogo(resId != 0 ? mContext.getResources().getDrawable(resId) : null);
    }

    public boolean hasLogo() {
        return mLogo != null;
    }

    public void setNavigationMode(int mode) {
        final int oldMode = mNavigationMode;
        if (mode != oldMode) {
            ActionBarTransition.beginDelayedTransition(this);
            switch (oldMode) {
            case ActionBar.NAVIGATION_MODE_LIST:
                if (mListNavLayout != null) {
                    removeView(mListNavLayout);
                }
                break;
            case ActionBar.NAVIGATION_MODE_TABS:
                if (mTabScrollView != null && mIncludeTabs) {
                    removeView(mTabScrollView);
                }
            }

            switch (mode) {
            case ActionBar.NAVIGATION_MODE_LIST:
                if (mSpinner == null) {
                    mSpinner = new Spinner(mContext, null,
                            com.android.internal.R.attr.actionDropDownStyle);
                    mSpinner.setId(com.android.internal.R.id.action_bar_spinner);
                    mListNavLayout = new LinearLayout(mContext, null,
                            com.android.internal.R.attr.actionBarTabBarStyle);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                            LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT);
                    params.gravity = Gravity.CENTER;
                    mListNavLayout.addView(mSpinner, params);
                }
                if (mSpinner.getAdapter() != mSpinnerAdapter) {
                    mSpinner.setAdapter(mSpinnerAdapter);
                }
                mSpinner.setOnItemSelectedListener(mNavItemSelectedListener);
                addView(mListNavLayout);
                break;
            case ActionBar.NAVIGATION_MODE_TABS:
                if (mTabScrollView != null && mIncludeTabs) {
                    addView(mTabScrollView);
                }
                break;
            }
            mNavigationMode = mode;
            requestLayout();
        }
    }

    public void setDropdownAdapter(SpinnerAdapter adapter) {
        mSpinnerAdapter = adapter;
        if (mSpinner != null) {
            mSpinner.setAdapter(adapter);
        }
    }

    public SpinnerAdapter getDropdownAdapter() {
        return mSpinnerAdapter;
    }

    public void setDropdownSelectedPosition(int position) {
        mSpinner.setSelection(position);
    }

    public int getDropdownSelectedPosition() {
        return mSpinner.getSelectedItemPosition();
    }

    public View getCustomNavigationView() {
        return mCustomNavView;
    }

    public int getNavigationMode() {
        return mNavigationMode;
    }

    public int getDisplayOptions() {
        return mDisplayOptions;
    }

    @Override
    protected ViewGroup.LayoutParams generateDefaultLayoutParams() {
        // Used by custom nav views if they don't supply layout params. Everything else
        // added to an ActionBarView should have them already.
        return new ActionBar.LayoutParams(DEFAULT_CUSTOM_GRAVITY);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mUpGoerFive.addView(mHomeLayout, 0);
        addView(mUpGoerFive);

        if (mCustomNavView != null && (mDisplayOptions & ActionBar.DISPLAY_SHOW_CUSTOM) != 0) {
            final ViewParent parent = mCustomNavView.getParent();
            if (parent != this) {
                if (parent instanceof ViewGroup) {
                    ((ViewGroup) parent).removeView(mCustomNavView);
                }
                addView(mCustomNavView);
            }
        }
    }

    private void initTitle() {
        if (mTitleLayout == null) {
            LayoutInflater inflater = LayoutInflater.from(getContext());
            mTitleLayout = (LinearLayout) inflater.inflate(R.layout.action_bar_title_item,
                    this, false);
            mTitleView = (TextView) mTitleLayout.findViewById(R.id.action_bar_title);
            mSubtitleView = (TextView) mTitleLayout.findViewById(R.id.action_bar_subtitle);

            if (mTitleStyleRes != 0) {
                mTitleView.setTextAppearance(mContext, mTitleStyleRes);
            }
            if (mTitle != null) {
                mTitleView.setText(mTitle);
            }

            if (mSubtitleStyleRes != 0) {
                mSubtitleView.setTextAppearance(mContext, mSubtitleStyleRes);
            }
            if (mSubtitle != null) {
                mSubtitleView.setText(mSubtitle);
                mSubtitleView.setVisibility(VISIBLE);
            }
        }

        ActionBarTransition.beginDelayedTransition(this);
        mUpGoerFive.addView(mTitleLayout);
        if (mExpandedActionView != null ||
                (TextUtils.isEmpty(mTitle) && TextUtils.isEmpty(mSubtitle))) {
            // Don't show while in expanded mode or with empty text
            mTitleLayout.setVisibility(GONE);
        } else {
            mTitleLayout.setVisibility(VISIBLE);
        }
    }

    public void setContextView(ActionBarContextView view) {
        mContextView = view;
    }

    public void setCollapsable(boolean collapsable) {
        mIsCollapsable = collapsable;
    }

    public boolean isCollapsed() {
        return mIsCollapsed;
    }

    /**
     * @return True if any characters in the title were truncated
     */
    public boolean isTitleTruncated() {
        if (mTitleView == null) {
            return false;
        }

        final Layout titleLayout = mTitleView.getLayout();
        if (titleLayout == null) {
            return false;
        }

        final int lineCount = titleLayout.getLineCount();
        for (int i = 0; i < lineCount; i++) {
            if (titleLayout.getEllipsisCount(i) > 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int childCount = getChildCount();
        if (mIsCollapsable) {
            int visibleChildren = 0;
            for (int i = 0; i < childCount; i++) {
                final View child = getChildAt(i);
                if (child.getVisibility() != GONE &&
                        !(child == mMenuView && mMenuView.getChildCount() == 0) &&
                        child != mUpGoerFive) {
                    visibleChildren++;
                }
            }

            final int upChildCount = mUpGoerFive.getChildCount();
            for (int i = 0; i < upChildCount; i++) {
                final View child = mUpGoerFive.getChildAt(i);
                if (child.getVisibility() != GONE) {
                    visibleChildren++;
                }
            }

            if (visibleChildren == 0) {
                // No size for an empty action bar when collapsable.
                setMeasuredDimension(0, 0);
                mIsCollapsed = true;
                return;
            }
        }
        mIsCollapsed = false;

        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        if (widthMode != MeasureSpec.EXACTLY) {
            throw new IllegalStateException(getClass().getSimpleName() + " can only be used " +
                    "with android:layout_width=\"match_parent\" (or fill_parent)");
        }

        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        if (heightMode != MeasureSpec.AT_MOST) {
            throw new IllegalStateException(getClass().getSimpleName() + " can only be used " +
                    "with android:layout_height=\"wrap_content\"");
        }

        int contentWidth = MeasureSpec.getSize(widthMeasureSpec);

        int maxHeight = mContentHeight >= 0 ?
                mContentHeight : MeasureSpec.getSize(heightMeasureSpec);

        final int verticalPadding = getPaddingTop() + getPaddingBottom();
        final int paddingLeft = getPaddingLeft();
        final int paddingRight = getPaddingRight();
        final int height = maxHeight - verticalPadding;
        final int childSpecHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST);
        final int exactHeightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);

        int availableWidth = contentWidth - paddingLeft - paddingRight;
        int leftOfCenter = availableWidth / 2;
        int rightOfCenter = leftOfCenter;

        final boolean showTitle = mTitleLayout != null && mTitleLayout.getVisibility() != GONE &&
                (mDisplayOptions & ActionBar.DISPLAY_SHOW_TITLE) != 0;

        HomeView homeLayout = mExpandedActionView != null ? mExpandedHomeLayout : mHomeLayout;

        final ViewGroup.LayoutParams homeLp = homeLayout.getLayoutParams();
        int homeWidthSpec;
        if (homeLp.width < 0) {
            homeWidthSpec = MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST);
        } else {
            homeWidthSpec = MeasureSpec.makeMeasureSpec(homeLp.width, MeasureSpec.EXACTLY);
        }

        /*
         * This is a little weird.
         * We're only measuring the *home* affordance within the Up container here
         * on purpose, because we want to give the available space to all other views before
         * the title text. We'll remeasure the whole up container again later.
         * We need to measure this container so we know the right offset for the up affordance
         * no matter what.
         */
        homeLayout.measure(homeWidthSpec, exactHeightSpec);

        int homeWidth = 0;
        if ((homeLayout.getVisibility() != GONE && homeLayout.getParent() == mUpGoerFive)
                || showTitle) {
            homeWidth = homeLayout.getMeasuredWidth();
            final int homeOffsetWidth = homeWidth + homeLayout.getStartOffset();
            availableWidth = Math.max(0, availableWidth - homeOffsetWidth);
            leftOfCenter = Math.max(0, availableWidth - homeOffsetWidth);
        }

        if (mMenuView != null && mMenuView.getParent() == this) {
            availableWidth = measureChildView(mMenuView, availableWidth, exactHeightSpec, 0);
            rightOfCenter = Math.max(0, rightOfCenter - mMenuView.getMeasuredWidth());
        }

        if (mIndeterminateProgressView != null &&
                mIndeterminateProgressView.getVisibility() != GONE) {
            availableWidth = measureChildView(mIndeterminateProgressView, availableWidth,
                    childSpecHeight, 0);
            rightOfCenter = Math.max(0,
                    rightOfCenter - mIndeterminateProgressView.getMeasuredWidth());
        }

        if (mExpandedActionView == null) {
            switch (mNavigationMode) {
                case ActionBar.NAVIGATION_MODE_LIST:
                    if (mListNavLayout != null) {
                        final int itemPaddingSize = showTitle ? mItemPadding * 2 : mItemPadding;
                        availableWidth = Math.max(0, availableWidth - itemPaddingSize);
                        leftOfCenter = Math.max(0, leftOfCenter - itemPaddingSize);
                        mListNavLayout.measure(
                                MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST),
                                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
                        final int listNavWidth = mListNavLayout.getMeasuredWidth();
                        availableWidth = Math.max(0, availableWidth - listNavWidth);
                        leftOfCenter = Math.max(0, leftOfCenter - listNavWidth);
                    }
                    break;
                case ActionBar.NAVIGATION_MODE_TABS:
                    if (mTabScrollView != null) {
                        final int itemPaddingSize = showTitle ? mItemPadding * 2 : mItemPadding;
                        availableWidth = Math.max(0, availableWidth - itemPaddingSize);
                        leftOfCenter = Math.max(0, leftOfCenter - itemPaddingSize);
                        mTabScrollView.measure(
                                MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST),
                                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
                        final int tabWidth = mTabScrollView.getMeasuredWidth();
                        availableWidth = Math.max(0, availableWidth - tabWidth);
                        leftOfCenter = Math.max(0, leftOfCenter - tabWidth);
                    }
                    break;
            }
        }

        View customView = null;
        if (mExpandedActionView != null) {
            customView = mExpandedActionView;
        } else if ((mDisplayOptions & ActionBar.DISPLAY_SHOW_CUSTOM) != 0 &&
                mCustomNavView != null) {
            customView = mCustomNavView;
        }

        if (customView != null) {
            final ViewGroup.LayoutParams lp = generateLayoutParams(customView.getLayoutParams());
            final ActionBar.LayoutParams ablp = lp instanceof ActionBar.LayoutParams ?
                    (ActionBar.LayoutParams) lp : null;

            int horizontalMargin = 0;
            int verticalMargin = 0;
            if (ablp != null) {
                horizontalMargin = ablp.leftMargin + ablp.rightMargin;
                verticalMargin = ablp.topMargin + ablp.bottomMargin;
            }

            // If the action bar is wrapping to its content height, don't allow a custom
            // view to MATCH_PARENT.
            int customNavHeightMode;
            if (mContentHeight <= 0) {
                customNavHeightMode = MeasureSpec.AT_MOST;
            } else {
                customNavHeightMode = lp.height != LayoutParams.WRAP_CONTENT ?
                        MeasureSpec.EXACTLY : MeasureSpec.AT_MOST;
            }
            final int customNavHeight = Math.max(0,
                    (lp.height >= 0 ? Math.min(lp.height, height) : height) - verticalMargin);

            final int customNavWidthMode = lp.width != LayoutParams.WRAP_CONTENT ?
                    MeasureSpec.EXACTLY : MeasureSpec.AT_MOST;
            int customNavWidth = Math.max(0,
                    (lp.width >= 0 ? Math.min(lp.width, availableWidth) : availableWidth)
                    - horizontalMargin);
            final int hgrav = (ablp != null ? ablp.gravity : DEFAULT_CUSTOM_GRAVITY) &
                    Gravity.HORIZONTAL_GRAVITY_MASK;

            // Centering a custom view is treated specially; we try to center within the whole
            // action bar rather than in the available space.
            if (hgrav == Gravity.CENTER_HORIZONTAL && lp.width == LayoutParams.MATCH_PARENT) {
                customNavWidth = Math.min(leftOfCenter, rightOfCenter) * 2;
            }

            customView.measure(
                    MeasureSpec.makeMeasureSpec(customNavWidth, customNavWidthMode),
                    MeasureSpec.makeMeasureSpec(customNavHeight, customNavHeightMode));
            availableWidth -= horizontalMargin + customView.getMeasuredWidth();
        }

        /*
         * Measure the whole up container now, allowing for the full home+title sections.
         * (This will re-measure the home view.)
         */
        availableWidth = measureChildView(mUpGoerFive, availableWidth + homeWidth,
                MeasureSpec.makeMeasureSpec(mContentHeight, MeasureSpec.EXACTLY), 0);
        if (mTitleLayout != null) {
            leftOfCenter = Math.max(0, leftOfCenter - mTitleLayout.getMeasuredWidth());
        }

        if (mContentHeight <= 0) {
            int measuredHeight = 0;
            for (int i = 0; i < childCount; i++) {
                View v = getChildAt(i);
                int paddedViewHeight = v.getMeasuredHeight() + verticalPadding;
                if (paddedViewHeight > measuredHeight) {
                    measuredHeight = paddedViewHeight;
                }
            }
            setMeasuredDimension(contentWidth, measuredHeight);
        } else {
            setMeasuredDimension(contentWidth, maxHeight);
        }

        if (mContextView != null) {
            mContextView.setContentHeight(getMeasuredHeight());
        }

        if (mProgressView != null && mProgressView.getVisibility() != GONE) {
            mProgressView.measure(MeasureSpec.makeMeasureSpec(
                    contentWidth - mProgressBarPadding * 2, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.AT_MOST));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int contentHeight = b - t - getPaddingTop() - getPaddingBottom();

        if (contentHeight <= 0) {
            // Nothing to do if we can't see anything.
            return;
        }

        final boolean isLayoutRtl = isLayoutRtl();
        final int direction = isLayoutRtl ? 1 : -1;
        int menuStart = isLayoutRtl ? getPaddingLeft() : r - l - getPaddingRight();
        // In LTR mode, we start from left padding and go to the right; in RTL mode, we start
        // from the padding right and go to the left (in reverse way)
        int x = isLayoutRtl ? r - l - getPaddingRight() : getPaddingLeft();
        final int y = getPaddingTop();

        HomeView homeLayout = mExpandedActionView != null ? mExpandedHomeLayout : mHomeLayout;
        final boolean showTitle = mTitleLayout != null && mTitleLayout.getVisibility() != GONE &&
                (mDisplayOptions & ActionBar.DISPLAY_SHOW_TITLE) != 0;
        int startOffset = 0;
        if (homeLayout.getParent() == mUpGoerFive) {
            if (homeLayout.getVisibility() != GONE) {
                startOffset = homeLayout.getStartOffset();
            } else if (showTitle) {
                startOffset = homeLayout.getUpWidth();
            }
        }

        // Position the up container based on where the edge of the home layout should go.
        x += positionChild(mUpGoerFive,
                next(x, startOffset, isLayoutRtl), y, contentHeight, isLayoutRtl);
        x = next(x, startOffset, isLayoutRtl);

        if (mExpandedActionView == null) {
            switch (mNavigationMode) {
                case ActionBar.NAVIGATION_MODE_STANDARD:
                    break;
                case ActionBar.NAVIGATION_MODE_LIST:
                    if (mListNavLayout != null) {
                        if (showTitle) {
                            x = next(x, mItemPadding, isLayoutRtl);
                        }
                        x += positionChild(mListNavLayout, x, y, contentHeight, isLayoutRtl);
                        x = next(x, mItemPadding, isLayoutRtl);
                    }
                    break;
                case ActionBar.NAVIGATION_MODE_TABS:
                    if (mTabScrollView != null) {
                        if (showTitle) x = next(x, mItemPadding, isLayoutRtl);
                        x += positionChild(mTabScrollView, x, y, contentHeight, isLayoutRtl);
                        x = next(x, mItemPadding, isLayoutRtl);
                    }
                    break;
            }
        }

        if (mMenuView != null && mMenuView.getParent() == this) {
            positionChild(mMenuView, menuStart, y, contentHeight, !isLayoutRtl);
            menuStart += direction * mMenuView.getMeasuredWidth();
        }

        if (mIndeterminateProgressView != null &&
                mIndeterminateProgressView.getVisibility() != GONE) {
            positionChild(mIndeterminateProgressView, menuStart, y, contentHeight, !isLayoutRtl);
            menuStart += direction * mIndeterminateProgressView.getMeasuredWidth();
        }

        View customView = null;
        if (mExpandedActionView != null) {
            customView = mExpandedActionView;
        } else if ((mDisplayOptions & ActionBar.DISPLAY_SHOW_CUSTOM) != 0 &&
                mCustomNavView != null) {
            customView = mCustomNavView;
        }
        if (customView != null) {
            final int layoutDirection = getLayoutDirection();
            ViewGroup.LayoutParams lp = customView.getLayoutParams();
            final ActionBar.LayoutParams ablp = lp instanceof ActionBar.LayoutParams ?
                    (ActionBar.LayoutParams) lp : null;
            final int gravity = ablp != null ? ablp.gravity : DEFAULT_CUSTOM_GRAVITY;
            final int navWidth = customView.getMeasuredWidth();

            int topMargin = 0;
            int bottomMargin = 0;
            if (ablp != null) {
                x = next(x, ablp.getMarginStart(), isLayoutRtl);
                menuStart += direction * ablp.getMarginEnd();
                topMargin = ablp.topMargin;
                bottomMargin = ablp.bottomMargin;
            }

            int hgravity = gravity & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK;
            // See if we actually have room to truly center; if not push against left or right.
            if (hgravity == Gravity.CENTER_HORIZONTAL) {
                final int centeredLeft = ((mRight - mLeft) - navWidth) / 2;
                if (isLayoutRtl) {
                    final int centeredStart = centeredLeft + navWidth;
                    final int centeredEnd = centeredLeft;
                    if (centeredStart > x) {
                        hgravity = Gravity.RIGHT;
                    } else if (centeredEnd < menuStart) {
                        hgravity = Gravity.LEFT;
                    }
                } else {
                    final int centeredStart = centeredLeft;
                    final int centeredEnd = centeredLeft + navWidth;
                    if (centeredStart < x) {
                        hgravity = Gravity.LEFT;
                    } else if (centeredEnd > menuStart) {
                        hgravity = Gravity.RIGHT;
                    }
                }
            } else if (gravity == Gravity.NO_GRAVITY) {
                hgravity = Gravity.START;
            }

            int xpos = 0;
            switch (Gravity.getAbsoluteGravity(hgravity, layoutDirection)) {
                case Gravity.CENTER_HORIZONTAL:
                    xpos = ((mRight - mLeft) - navWidth) / 2;
                    break;
                case Gravity.LEFT:
                    xpos = isLayoutRtl ? menuStart : x;
                    break;
                case Gravity.RIGHT:
                    xpos = isLayoutRtl ? x - navWidth : menuStart - navWidth;
                    break;
            }

            int vgravity = gravity & Gravity.VERTICAL_GRAVITY_MASK;

            if (gravity == Gravity.NO_GRAVITY) {
                vgravity = Gravity.CENTER_VERTICAL;
            }

            int ypos = 0;
            switch (vgravity) {
                case Gravity.CENTER_VERTICAL:
                    final int paddedTop = getPaddingTop();
                    final int paddedBottom = mBottom - mTop - getPaddingBottom();
                    ypos = ((paddedBottom - paddedTop) - customView.getMeasuredHeight()) / 2;
                    break;
                case Gravity.TOP:
                    ypos = getPaddingTop() + topMargin;
                    break;
                case Gravity.BOTTOM:
                    ypos = getHeight() - getPaddingBottom() - customView.getMeasuredHeight()
                            - bottomMargin;
                    break;
            }
            final int customWidth = customView.getMeasuredWidth();
            customView.layout(xpos, ypos, xpos + customWidth,
                    ypos + customView.getMeasuredHeight());
            x = next(x, customWidth, isLayoutRtl);
        }

        if (mProgressView != null) {
            mProgressView.bringToFront();
            final int halfProgressHeight = mProgressView.getMeasuredHeight() / 2;
            mProgressView.layout(mProgressBarPadding, -halfProgressHeight,
                    mProgressBarPadding + mProgressView.getMeasuredWidth(), halfProgressHeight);
        }
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new ActionBar.LayoutParams(getContext(), attrs);
    }

    @Override
    public ViewGroup.LayoutParams generateLayoutParams(ViewGroup.LayoutParams lp) {
        if (lp == null) {
            lp = generateDefaultLayoutParams();
        }
        return lp;
    }

    @Override
    public Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        SavedState state = new SavedState(superState);

        if (mExpandedMenuPresenter != null && mExpandedMenuPresenter.mCurrentExpandedItem != null) {
            state.expandedMenuItemId = mExpandedMenuPresenter.mCurrentExpandedItem.getItemId();
        }

        state.isOverflowOpen = isOverflowMenuShowing();

        return state;
    }

    @Override
    public void onRestoreInstanceState(Parcelable p) {
        SavedState state = (SavedState) p;

        super.onRestoreInstanceState(state.getSuperState());

        if (state.expandedMenuItemId != 0 &&
                mExpandedMenuPresenter != null && mOptionsMenu != null) {
            final MenuItem item = mOptionsMenu.findItem(state.expandedMenuItemId);
            if (item != null) {
                item.expandActionView();
            }
        }

        if (state.isOverflowOpen) {
            postShowOverflowMenu();
        }
    }

    public void setHomeAsUpIndicator(Drawable indicator) {
        mHomeLayout.setUpIndicator(indicator);
    }

    public void setHomeAsUpIndicator(int resId) {
        mHomeLayout.setUpIndicator(resId);
    }

    public void setHomeActionContentDescription(CharSequence description) {
        mHomeDescription = description;
        updateHomeAccessibility(mUpGoerFive.isEnabled());
    }

    public void setHomeActionContentDescription(int resId) {
        mHomeDescriptionRes = resId;
        mHomeDescription = resId != 0 ? getResources().getText(resId) : null;
        updateHomeAccessibility(mUpGoerFive.isEnabled());
    }

    static class SavedState extends BaseSavedState {
        int expandedMenuItemId;
        boolean isOverflowOpen;

        SavedState(Parcelable superState) {
            super(superState);
        }

        private SavedState(Parcel in) {
            super(in);
            expandedMenuItemId = in.readInt();
            isOverflowOpen = in.readInt() != 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(expandedMenuItemId);
            out.writeInt(isOverflowOpen ? 1 : 0);
        }

        public static final Parcelable.Creator<SavedState> CREATOR =
                new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }

    private static class HomeView extends FrameLayout {
        private ImageView mUpView;
        private ImageView mIconView;
        private int mUpWidth;
        private int mStartOffset;
        private int mUpIndicatorRes;
        private Drawable mDefaultUpIndicator;

        private static final long DEFAULT_TRANSITION_DURATION = 150;

        public HomeView(Context context) {
            this(context, null);
        }

        public HomeView(Context context, AttributeSet attrs) {
            super(context, attrs);
            LayoutTransition t = getLayoutTransition();
            if (t != null) {
                // Set a lower duration than the default
                t.setDuration(DEFAULT_TRANSITION_DURATION);
            }
        }

        public void setShowUp(boolean isUp) {
            mUpView.setVisibility(isUp ? VISIBLE : GONE);
        }

        public void setShowIcon(boolean showIcon) {
            mIconView.setVisibility(showIcon ? VISIBLE : GONE);
        }

        public void setIcon(Drawable icon) {
            mIconView.setImageDrawable(icon);
        }

        public void setUpIndicator(Drawable d) {
            mUpView.setImageDrawable(d != null ? d : mDefaultUpIndicator);
            mUpIndicatorRes = 0;
        }

        public void setUpIndicator(int resId) {
            mUpIndicatorRes = resId;
            mUpView.setImageDrawable(resId != 0 ? getResources().getDrawable(resId) : null);
        }

        @Override
        protected void onConfigurationChanged(Configuration newConfig) {
            super.onConfigurationChanged(newConfig);
            if (mUpIndicatorRes != 0) {
                // Reload for config change
                setUpIndicator(mUpIndicatorRes);
            }
        }

        @Override
        public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
            onPopulateAccessibilityEvent(event);
            return true;
        }

        @Override
        public void onPopulateAccessibilityEvent(AccessibilityEvent event) {
            super.onPopulateAccessibilityEvent(event);
            final CharSequence cdesc = getContentDescription();
            if (!TextUtils.isEmpty(cdesc)) {
                event.getText().add(cdesc);
            }
        }

        @Override
        public boolean dispatchHoverEvent(MotionEvent event) {
            // Don't allow children to hover; we want this to be treated as a single component.
            return onHoverEvent(event);
        }

        @Override
        protected void onFinishInflate() {
            mUpView = (ImageView) findViewById(com.android.internal.R.id.up);
            mIconView = (ImageView) findViewById(com.android.internal.R.id.home);
            mDefaultUpIndicator = mUpView.getDrawable();
        }

        public int getStartOffset() {
            return mUpView.getVisibility() == GONE ? mStartOffset : 0;
        }

        public int getUpWidth() {
            return mUpWidth;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            measureChildWithMargins(mUpView, widthMeasureSpec, 0, heightMeasureSpec, 0);
            final LayoutParams upLp = (LayoutParams) mUpView.getLayoutParams();
            final int upMargins = upLp.leftMargin + upLp.rightMargin;
            mUpWidth = mUpView.getMeasuredWidth();
            mStartOffset = mUpWidth + upMargins;
            int width = mUpView.getVisibility() == GONE ? 0 : mStartOffset;
            int height = upLp.topMargin + mUpView.getMeasuredHeight() + upLp.bottomMargin;

            if (mIconView.getVisibility() != GONE) {
                measureChildWithMargins(mIconView, widthMeasureSpec, width, heightMeasureSpec, 0);
                final LayoutParams iconLp = (LayoutParams) mIconView.getLayoutParams();
                width += iconLp.leftMargin + mIconView.getMeasuredWidth() + iconLp.rightMargin;
                height = Math.max(height,
                        iconLp.topMargin + mIconView.getMeasuredHeight() + iconLp.bottomMargin);
            } else if (upMargins < 0) {
                // Remove the measurement effects of negative margins used for offsets
                width -= upMargins;
            }

            final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
            final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
            final int widthSize = MeasureSpec.getSize(widthMeasureSpec);
            final int heightSize = MeasureSpec.getSize(heightMeasureSpec);

            switch (widthMode) {
                case MeasureSpec.AT_MOST:
                    width = Math.min(width, widthSize);
                    break;
                case MeasureSpec.EXACTLY:
                    width = widthSize;
                    break;
                case MeasureSpec.UNSPECIFIED:
                default:
                    break;
            }
            switch (heightMode) {
                case MeasureSpec.AT_MOST:
                    height = Math.min(height, heightSize);
                    break;
                case MeasureSpec.EXACTLY:
                    height = heightSize;
                    break;
                case MeasureSpec.UNSPECIFIED:
                default:
                    break;
            }
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            final int vCenter = (b - t) / 2;
            final boolean isLayoutRtl = isLayoutRtl();
            final int width = getWidth();
            int upOffset = 0;
            if (mUpView.getVisibility() != GONE) {
                final LayoutParams upLp = (LayoutParams) mUpView.getLayoutParams();
                final int upHeight = mUpView.getMeasuredHeight();
                final int upWidth = mUpView.getMeasuredWidth();
                upOffset = upLp.leftMargin + upWidth + upLp.rightMargin;
                final int upTop = vCenter - upHeight / 2;
                final int upBottom = upTop + upHeight;
                final int upRight;
                final int upLeft;
                if (isLayoutRtl) {
                    upRight = width;
                    upLeft = upRight - upWidth;
                    r -= upOffset;
                } else {
                    upRight = upWidth;
                    upLeft = 0;
                    l += upOffset;
                }
                mUpView.layout(upLeft, upTop, upRight, upBottom);
            }

            final LayoutParams iconLp = (LayoutParams) mIconView.getLayoutParams();
            final int iconHeight = mIconView.getMeasuredHeight();
            final int iconWidth = mIconView.getMeasuredWidth();
            final int hCenter = (r - l) / 2;
            final int iconTop = Math.max(iconLp.topMargin, vCenter - iconHeight / 2);
            final int iconBottom = iconTop + iconHeight;
            final int iconLeft;
            final int iconRight;
            int marginStart = iconLp.getMarginStart();
            final int delta = Math.max(marginStart, hCenter - iconWidth / 2);
            if (isLayoutRtl) {
                iconRight = width - upOffset - delta;
                iconLeft = iconRight - iconWidth;
            } else {
                iconLeft = upOffset + delta;
                iconRight = iconLeft + iconWidth;
            }
            mIconView.layout(iconLeft, iconTop, iconRight, iconBottom);
        }
    }

    private class ExpandedActionViewMenuPresenter implements MenuPresenter {
        MenuBuilder mMenu;
        MenuItemImpl mCurrentExpandedItem;

        @Override
        public void initForMenu(Context context, MenuBuilder menu) {
            // Clear the expanded action view when menus change.
            if (mMenu != null && mCurrentExpandedItem != null) {
                mMenu.collapseItemActionView(mCurrentExpandedItem);
            }
            mMenu = menu;
        }

        @Override
        public MenuView getMenuView(ViewGroup root) {
            return null;
        }

        @Override
        public void updateMenuView(boolean cleared) {
            // Make sure the expanded item we have is still there.
            if (mCurrentExpandedItem != null) {
                boolean found = false;

                if (mMenu != null) {
                    final int count = mMenu.size();
                    for (int i = 0; i < count; i++) {
                        final MenuItem item = mMenu.getItem(i);
                        if (item == mCurrentExpandedItem) {
                            found = true;
                            break;
                        }
                    }
                }

                if (!found) {
                    // The item we had expanded disappeared. Collapse.
                    collapseItemActionView(mMenu, mCurrentExpandedItem);
                }
            }
        }

        @Override
        public void setCallback(Callback cb) {
        }

        @Override
        public boolean onSubMenuSelected(SubMenuBuilder subMenu) {
            return false;
        }

        @Override
        public void onCloseMenu(MenuBuilder menu, boolean allMenusAreClosing) {
        }

        @Override
        public boolean flagActionItems() {
            return false;
        }

        @Override
        public boolean expandItemActionView(MenuBuilder menu, MenuItemImpl item) {
            ActionBarTransition.beginDelayedTransition(ActionBarView.this);

            mExpandedActionView = item.getActionView();
            mExpandedHomeLayout.setIcon(mIcon.getConstantState().newDrawable(getResources()));
            mCurrentExpandedItem = item;
            if (mExpandedActionView.getParent() != ActionBarView.this) {
                addView(mExpandedActionView);
            }
            if (mExpandedHomeLayout.getParent() != mUpGoerFive) {
                mUpGoerFive.addView(mExpandedHomeLayout);
            }
            mHomeLayout.setVisibility(GONE);
            if (mTitleLayout != null) mTitleLayout.setVisibility(GONE);
            if (mTabScrollView != null) mTabScrollView.setVisibility(GONE);
            if (mSpinner != null) mSpinner.setVisibility(GONE);
            if (mCustomNavView != null) mCustomNavView.setVisibility(GONE);
            setHomeButtonEnabled(false, false);
            requestLayout();
            item.setActionViewExpanded(true);

            if (mExpandedActionView instanceof CollapsibleActionView) {
                ((CollapsibleActionView) mExpandedActionView).onActionViewExpanded();
            }

            return true;
        }

        @Override
        public boolean collapseItemActionView(MenuBuilder menu, MenuItemImpl item) {
            ActionBarTransition.beginDelayedTransition(ActionBarView.this);

            // Do this before detaching the actionview from the hierarchy, in case
            // it needs to dismiss the soft keyboard, etc.
            if (mExpandedActionView instanceof CollapsibleActionView) {
                ((CollapsibleActionView) mExpandedActionView).onActionViewCollapsed();
            }

            removeView(mExpandedActionView);
            mUpGoerFive.removeView(mExpandedHomeLayout);
            mExpandedActionView = null;
            if ((mDisplayOptions & ActionBar.DISPLAY_SHOW_HOME) != 0) {
                mHomeLayout.setVisibility(VISIBLE);
            }
            if ((mDisplayOptions & ActionBar.DISPLAY_SHOW_TITLE) != 0) {
                if (mTitleLayout == null) {
                    initTitle();
                } else {
                    mTitleLayout.setVisibility(VISIBLE);
                }
            }
            if (mTabScrollView != null) mTabScrollView.setVisibility(VISIBLE);
            if (mSpinner != null) mSpinner.setVisibility(VISIBLE);
            if (mCustomNavView != null) mCustomNavView.setVisibility(VISIBLE);

            mExpandedHomeLayout.setIcon(null);
            mCurrentExpandedItem = null;
            setHomeButtonEnabled(mWasHomeEnabled); // Set by expandItemActionView above
            requestLayout();
            item.setActionViewExpanded(false);

            return true;
        }

        @Override
        public int getId() {
            return 0;
        }

        @Override
        public Parcelable onSaveInstanceState() {
            return null;
        }

        @Override
        public void onRestoreInstanceState(Parcelable state) {
        }
    }
}
