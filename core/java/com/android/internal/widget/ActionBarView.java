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

import com.android.internal.R;
import com.android.internal.view.menu.ActionMenuItem;
import com.android.internal.view.menu.ActionMenuPresenter;
import com.android.internal.view.menu.ActionMenuView;
import com.android.internal.view.menu.MenuBuilder;
import com.android.internal.view.menu.MenuPresenter;

import android.app.ActionBar;
import android.app.ActionBar.OnNavigationListener;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.text.TextUtils.TruncateAt;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;

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
            ActionBar.DISPLAY_SHOW_TITLE;

    private static final int DEFAULT_CUSTOM_GRAVITY = Gravity.LEFT | Gravity.CENTER_VERTICAL;
    
    private final int mContentHeight;

    private int mNavigationMode;
    private int mDisplayOptions = ActionBar.DISPLAY_SHOW_HOME | ActionBar.DISPLAY_HOME_AS_UP;
    private CharSequence mTitle;
    private CharSequence mSubtitle;
    private Drawable mIcon;
    private Drawable mLogo;

    private View mHomeLayout;
    private View mHomeAsUpView;
    private ImageView mIconView;
    private LinearLayout mTitleLayout;
    private TextView mTitleView;
    private TextView mSubtitleView;
    private Spinner mSpinner;
    private LinearLayout mListNavLayout;
    private HorizontalScrollView mTabScrollView;
    private ViewGroup mTabLayout;
    private View mCustomNavView;
    private ProgressBar mProgressView;
    private ProgressBar mIndeterminateProgressView;

    private int mProgressBarPadding;
    private int mItemPadding;
    
    private int mTitleStyleRes;
    private int mSubtitleStyleRes;
    private int mProgressStyle;
    private int mIndeterminateProgressStyle;

    private boolean mSplitActionBar;
    private boolean mUserTitle;
    private boolean mIncludeTabs;

    private MenuBuilder mOptionsMenu;
    
    private ActionBarContextView mContextView;

    private ActionMenuItem mLogoNavItem;

    private SpinnerAdapter mSpinnerAdapter;
    private OnNavigationListener mCallback;

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

    private OnClickListener mTabClickListener = null;

    public ActionBarView(Context context, AttributeSet attrs) {
        super(context, attrs);

        // Background is always provided by the container.
        setBackgroundResource(0);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ActionBar);

        ApplicationInfo appInfo = context.getApplicationInfo();
        PackageManager pm = context.getPackageManager();
        mNavigationMode = a.getInt(R.styleable.ActionBar_navigationMode,
                ActionBar.NAVIGATION_MODE_STANDARD);
        mTitle = a.getText(R.styleable.ActionBar_title);
        mSubtitle = a.getText(R.styleable.ActionBar_subtitle);
        
        mLogo = a.getDrawable(R.styleable.ActionBar_logo);
        if (mLogo == null) {
            if (context instanceof Activity) {
                try {
                    mLogo = pm.getActivityLogo(((Activity) context).getComponentName());
                } catch (NameNotFoundException e) {
                    Log.e(TAG, "Activity component name not found!", e);
                }
            }
            if (mLogo == null) {
                mLogo = appInfo.loadLogo(pm);
            }
        }

        mIcon = a.getDrawable(R.styleable.ActionBar_icon);
        if (mIcon == null) {
            if (context instanceof Activity) {
                try {
                    mIcon = pm.getActivityIcon(((Activity) context).getComponentName());
                } catch (NameNotFoundException e) {
                    Log.e(TAG, "Activity component name not found!", e);
                }
            }
            if (mIcon == null) {
                mIcon = appInfo.loadIcon(pm);
            }
        }

        final LayoutInflater inflater = LayoutInflater.from(context);

        final int homeResId = a.getResourceId(
                com.android.internal.R.styleable.ActionBar_homeLayout,
                com.android.internal.R.layout.action_bar_home);

        mHomeLayout = inflater.inflate(homeResId, this, false);

        mHomeAsUpView = mHomeLayout.findViewById(com.android.internal.R.id.up);
        mIconView = (ImageView) mHomeLayout.findViewById(com.android.internal.R.id.home);
        
        mTitleStyleRes = a.getResourceId(R.styleable.ActionBar_titleTextStyle, 0);
        mSubtitleStyleRes = a.getResourceId(R.styleable.ActionBar_subtitleTextStyle, 0);
        mProgressStyle = a.getResourceId(R.styleable.ActionBar_progressBarStyle, 0);
        mIndeterminateProgressStyle = a.getResourceId(
                R.styleable.ActionBar_indeterminateProgressStyle, 0);

        mProgressBarPadding = a.getDimensionPixelOffset(R.styleable.ActionBar_progressBarPadding, 0);
        mItemPadding = a.getDimensionPixelOffset(R.styleable.ActionBar_itemPadding, 0);

        mIncludeTabs = a.getBoolean(R.styleable.ActionBar_embeddedTabs, true);

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
        mHomeLayout.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                Context context = getContext();
                if (context instanceof Activity) {
                    Activity activity = (Activity) context;
                    activity.onMenuItemSelected(Window.FEATURE_OPTIONS_PANEL, mLogoNavItem);
                }
            }
        });
        mHomeLayout.setClickable(true);
        mHomeLayout.setFocusable(true);
    }

    public void initProgress() {
        mProgressView = new ProgressBar(mContext, null, 0, mProgressStyle);
        mProgressView.setId(R.id.progress_horizontal);
        mProgressView.setMax(10000);
        addView(mProgressView);
    }

    public void initIndeterminateProgress() {
        mIndeterminateProgressView = new ProgressBar(mContext, null, 0,
                mIndeterminateProgressStyle);
        mIndeterminateProgressView.setId(R.id.progress_circular);
        addView(mIndeterminateProgressView);
    }

    public void setSplitActionBar(boolean splitActionBar) {
        if (mSplitActionBar != splitActionBar) {
            if (mMenuView != null) {
                if (splitActionBar) {
                    removeView(mMenuView);
                    if (mSplitView != null) {
                        mSplitView.addView(mMenuView);
                    }
                } else {
                    addView(mMenuView);
                }
            }
            mSplitActionBar = splitActionBar;
        }
    }

    public boolean isSplitActionBar() {
        return mSplitActionBar;
    }

    public boolean hasEmbeddedTabs() {
        return mIncludeTabs;
    }

    public void setExternalTabLayout(ViewGroup tabLayout) {
        mTabLayout = tabLayout;
    }

    public void setCallback(OnNavigationListener callback) {
        mCallback = callback;
    }

    public void setMenu(Menu menu, MenuPresenter.Callback cb) {
        if (menu == mOptionsMenu) return;

        if (mOptionsMenu != null) {
            mOptionsMenu.removeMenuPresenter(mMenuPresenter);
        }

        MenuBuilder builder = (MenuBuilder) menu;
        mOptionsMenu = builder;
        if (mMenuView != null) {
            removeView(mMenuView);
        }
        if (mMenuPresenter == null) {
            mMenuPresenter = new ActionMenuPresenter();
            mMenuPresenter.setCallback(cb);
            builder.addMenuPresenter(mMenuPresenter);
        }
        final ActionMenuView menuView = (ActionMenuView) mMenuPresenter.getMenuView(this);
        final LayoutParams layoutParams = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT);
        menuView.setLayoutParams(layoutParams);
        if (!mSplitActionBar) {
            addView(menuView);
        } else {
            // Allow full screen width in split mode.
            mMenuPresenter.setWidthLimit(
                    getContext().getResources().getDisplayMetrics().widthPixels, true);
            // No limit to the item count; use whatever will fit.
            mMenuPresenter.setItemLimit(Integer.MAX_VALUE);
            // Span the whole width
            layoutParams.width = LayoutParams.MATCH_PARENT;
            if (mSplitView != null) {
                mSplitView.addView(menuView);
            } // We'll add this later if we missed it this time.
        }
        mMenuView = menuView;
    }

    public void setCustomNavigationView(View view) {
        final boolean showCustom = (mDisplayOptions & ActionBar.DISPLAY_SHOW_CUSTOM) != 0;
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
        mTitle = title;
        if (mTitleView != null) {
            mTitleView.setText(title);
            mTitleLayout.setVisibility(TextUtils.isEmpty(mTitle) && TextUtils.isEmpty(mSubtitle) ?
                    GONE : VISIBLE);
        }
        if (mLogoNavItem != null) {
            mLogoNavItem.setTitle(title);
        }
    }

    public CharSequence getSubtitle() {
        return mSubtitle;
    }

    public void setSubtitle(CharSequence subtitle) {
        mSubtitle = subtitle;
        if (mSubtitleView != null) {
            mSubtitleView.setText(subtitle);
            mSubtitleView.setVisibility(subtitle != null ? VISIBLE : GONE);
            mTitleLayout.setVisibility(TextUtils.isEmpty(mTitle) && TextUtils.isEmpty(mSubtitle) ?
                    GONE : VISIBLE);
        }
    }

    public void setDisplayOptions(int options) {
        final int flagsChanged = options ^ mDisplayOptions;
        mDisplayOptions = options;

        if ((flagsChanged & ActionBar.DISPLAY_DISABLE_HOME) != 0) {
            final boolean disableHome = (options & ActionBar.DISPLAY_DISABLE_HOME) != 0;
            mHomeLayout.setEnabled(!disableHome);
        }

        if ((flagsChanged & DISPLAY_RELAYOUT_MASK) != 0) {
            final int vis = (options & ActionBar.DISPLAY_SHOW_HOME) != 0 ? VISIBLE : GONE;
            mHomeLayout.setVisibility(vis);

            if ((flagsChanged & ActionBar.DISPLAY_HOME_AS_UP) != 0) {
                final boolean isUp = (options & ActionBar.DISPLAY_HOME_AS_UP) != 0;
                mHomeAsUpView.setVisibility(isUp ? VISIBLE : GONE);
            }

            if ((flagsChanged & ActionBar.DISPLAY_USE_LOGO) != 0) {
                final boolean logoVis = mLogo != null && (options & ActionBar.DISPLAY_USE_LOGO) != 0;
                mIconView.setImageDrawable(logoVis ? mLogo : mIcon);
            }

            if ((flagsChanged & ActionBar.DISPLAY_SHOW_TITLE) != 0) {
                if ((options & ActionBar.DISPLAY_SHOW_TITLE) != 0) {
                    initTitle();
                } else {
                    removeView(mTitleLayout);
                }
            }

            if ((flagsChanged & ActionBar.DISPLAY_SHOW_CUSTOM) != 0 && mCustomNavView != null) {
                if ((options & ActionBar.DISPLAY_SHOW_CUSTOM) != 0) {
                    addView(mCustomNavView);
                } else {
                    removeView(mCustomNavView);
                }
            }
            
            requestLayout();
        } else {
            invalidate();
        }

        // Make sure the home button has an accurate content description for accessibility.
        if ((options & ActionBar.DISPLAY_DISABLE_HOME) != 0) {
            mHomeLayout.setContentDescription(null);
        } else if ((options & ActionBar.DISPLAY_HOME_AS_UP) != 0) {
            mHomeLayout.setContentDescription(mContext.getResources().getText(
                    R.string.action_bar_up_description));
        } else {
            mHomeLayout.setContentDescription(mContext.getResources().getText(
                    R.string.action_bar_home_description));
        }
    }

    public void setIcon(Drawable icon) {
        mIcon = icon;
        if (icon != null &&
                ((mDisplayOptions & ActionBar.DISPLAY_USE_LOGO) == 0 || mLogo == null)) {
            mIconView.setImageDrawable(icon);
        }
    }

    public void setIcon(int resId) {
        setIcon(mContext.getResources().getDrawableForDensity(resId, getPreferredIconDensity()));
    }

    public void setLogo(Drawable logo) {
        mLogo = logo;
        if (logo != null && (mDisplayOptions & ActionBar.DISPLAY_USE_LOGO) != 0) {
            mIconView.setImageDrawable(logo);
        }
    }

    public void setLogo(int resId) {
        mContext.getResources().getDrawable(resId);
    }

    /**
     * @return Drawable density to load that will best fit the available height.
     */
    private int getPreferredIconDensity() {
        final Resources res = mContext.getResources();
        final int availableHeight = getLayoutParams().height -
                mIconView.getPaddingTop() - mIconView.getPaddingBottom();
        int iconSize = res.getDimensionPixelSize(android.R.dimen.app_icon_size);

        if (iconSize * DisplayMetrics.DENSITY_LOW >= availableHeight) {
            return DisplayMetrics.DENSITY_LOW;
        } else if (iconSize * DisplayMetrics.DENSITY_MEDIUM >= availableHeight) {
            return DisplayMetrics.DENSITY_MEDIUM;
        } else if (iconSize * DisplayMetrics.DENSITY_HIGH >= availableHeight) {
            return DisplayMetrics.DENSITY_HIGH;
        }
        return DisplayMetrics.DENSITY_XHIGH;
    }

    public void setNavigationMode(int mode) {
        final int oldMode = mNavigationMode;
        if (mode != oldMode) {
            switch (oldMode) {
            case ActionBar.NAVIGATION_MODE_LIST:
                if (mSpinner != null) {
                    removeView(mListNavLayout);
                }
                break;
            case ActionBar.NAVIGATION_MODE_TABS:
                if (mTabScrollView != null) {
                    removeView(mTabScrollView);
                }
            }
            
            switch (mode) {
            case ActionBar.NAVIGATION_MODE_LIST:
                if (mSpinner == null) {
                    mSpinner = new Spinner(mContext, null,
                            com.android.internal.R.attr.actionDropDownStyle);
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
                ensureTabsExist();
                if (mTabScrollView != null) {
                    addView(mTabScrollView);
                }
                break;
            }
            mNavigationMode = mode;
            requestLayout();
        }
    }
    
    private void ensureTabsExist() {
        if (!mIncludeTabs) return;

        if (mTabScrollView == null) {
            mTabScrollView = new HorizontalScrollView(getContext());
            mTabScrollView.setHorizontalFadingEdgeEnabled(true);
            mTabLayout = createTabContainer();
            mTabScrollView.addView(mTabLayout);
        }
    }

    public ViewGroup createTabContainer() {
        ViewGroup result = new LinearLayout(getContext(), null,
                com.android.internal.R.attr.actionBarTabBarStyle);
        result.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                mContentHeight));
        return result;
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

    private TabView createTabView(ActionBar.Tab tab) {
        final TabView tabView = new TabView(getContext(), tab);
        tabView.setFocusable(true);

        if (mTabClickListener == null) {
            mTabClickListener = new TabClickListener();
        }
        tabView.setOnClickListener(mTabClickListener);
        return tabView;
    }

    public void addTab(ActionBar.Tab tab, boolean setSelected) {
        ensureTabsExist();
        View tabView = createTabView(tab);
        mTabLayout.addView(tabView);
        if (setSelected) {
            tabView.setSelected(true);
        }
    }

    public void addTab(ActionBar.Tab tab, int position, boolean setSelected) {
        ensureTabsExist();
        final TabView tabView = createTabView(tab);
        mTabLayout.addView(tabView, position);
        if (setSelected) {
            tabView.setSelected(true);
        }
    }

    public void updateTab(int position) {
        ((TabView) mTabLayout.getChildAt(position)).update();
    }

    public void removeTabAt(int position) {
        if (mTabLayout != null) {
            mTabLayout.removeViewAt(position);
        }
    }

    public void removeAllTabs() {
        if (mTabLayout != null) {
            mTabLayout.removeAllViews();
        }
    }

    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        // Used by custom nav views if they don't supply layout params. Everything else
        // added to an ActionBarView should have them already.
        return new ActionBar.LayoutParams(DEFAULT_CUSTOM_GRAVITY);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        addView(mHomeLayout);

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
        LayoutInflater inflater = LayoutInflater.from(getContext());
        mTitleLayout = (LinearLayout) inflater.inflate(R.layout.action_bar_title_item, null);
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

        addView(mTitleLayout);
    }

    public void setTabSelected(int position) {
        ensureTabsExist();
        final int tabCount = mTabLayout.getChildCount();
        for (int i = 0; i < tabCount; i++) {
            final View child = mTabLayout.getChildAt(i);
            child.setSelected(i == position);
        }
    }

    public void setContextView(ActionBarContextView view) {
        mContextView = view;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
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

        int maxHeight = mContentHeight > 0 ?
                mContentHeight : MeasureSpec.getSize(heightMeasureSpec);
        
        final int verticalPadding = getPaddingTop() + getPaddingBottom();
        final int paddingLeft = getPaddingLeft();
        final int paddingRight = getPaddingRight();
        final int height = maxHeight - verticalPadding;
        final int childSpecHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST);

        int availableWidth = contentWidth - paddingLeft - paddingRight;
        int leftOfCenter = availableWidth / 2;
        int rightOfCenter = leftOfCenter;

        if (mHomeLayout.getVisibility() != GONE) {
            mHomeLayout.measure(MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            final int homeWidth = mHomeLayout.getMeasuredWidth();
            availableWidth -= homeWidth;
            leftOfCenter -= homeWidth;
        }
        
        if (mMenuView != null && mMenuView.getParent() == this) {
            availableWidth = measureChildView(mMenuView, availableWidth,
                    childSpecHeight, 0);
            rightOfCenter -= mMenuView.getMeasuredWidth();
        }

        boolean showTitle = mTitleLayout != null && mTitleLayout.getVisibility() != GONE &&
                (mDisplayOptions & ActionBar.DISPLAY_SHOW_TITLE) != 0;
        if (showTitle) {
            availableWidth = measureChildView(mTitleLayout, availableWidth, childSpecHeight, 0);
            leftOfCenter -= mTitleLayout.getMeasuredWidth();
        }

        switch (mNavigationMode) {
        case ActionBar.NAVIGATION_MODE_LIST:
            if (mListNavLayout != null) {
                final int itemPaddingSize = showTitle ? mItemPadding * 2 : mItemPadding;
                availableWidth -= itemPaddingSize;
                leftOfCenter -= itemPaddingSize;
                mListNavLayout.measure(
                        MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
                final int listNavWidth = mListNavLayout.getMeasuredWidth();
                availableWidth -= listNavWidth;
                leftOfCenter -= listNavWidth;
            }
            break;
        case ActionBar.NAVIGATION_MODE_TABS:
            if (mTabScrollView != null) {
                final int itemPaddingSize = showTitle ? mItemPadding * 2 : mItemPadding;
                availableWidth -= itemPaddingSize;
                leftOfCenter -= itemPaddingSize;
                mTabScrollView.measure(
                        MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
                final int tabWidth = mTabScrollView.getMeasuredWidth();
                availableWidth -= tabWidth;
                leftOfCenter -= tabWidth;
            }
            break;
        }

        if (mIndeterminateProgressView != null &&
                mIndeterminateProgressView.getVisibility() != GONE) {
            availableWidth = measureChildView(mIndeterminateProgressView, availableWidth,
                    childSpecHeight, 0);
            rightOfCenter -= mIndeterminateProgressView.getMeasuredWidth();
        }

        if ((mDisplayOptions & ActionBar.DISPLAY_SHOW_CUSTOM) != 0 && mCustomNavView != null) {
            final LayoutParams lp = generateLayoutParams(mCustomNavView.getLayoutParams());
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

            mCustomNavView.measure(
                    MeasureSpec.makeMeasureSpec(customNavWidth, customNavWidthMode),
                    MeasureSpec.makeMeasureSpec(customNavHeight, customNavHeightMode));
        }

        if (mContentHeight <= 0) {
            int measuredHeight = 0;
            final int count = getChildCount();
            for (int i = 0; i < count; i++) {
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
            mContextView.setHeight(getMeasuredHeight());
        }

        if (mProgressView != null && mProgressView.getVisibility() != GONE) {
            mProgressView.measure(MeasureSpec.makeMeasureSpec(
                    contentWidth - mProgressBarPadding * 2, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.AT_MOST));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int x = getPaddingLeft();
        final int y = getPaddingTop();
        final int contentHeight = b - t - getPaddingTop() - getPaddingBottom();

        if (mHomeLayout.getVisibility() != GONE) {
            x += positionChild(mHomeLayout, x, y, contentHeight);
        }
        
        final boolean showTitle = mTitleLayout != null && mTitleLayout.getVisibility() != GONE &&
                (mDisplayOptions & ActionBar.DISPLAY_SHOW_TITLE) != 0;
        if (showTitle) {
            x += positionChild(mTitleLayout, x, y, contentHeight);
        }

        switch (mNavigationMode) {
        case ActionBar.NAVIGATION_MODE_STANDARD:
            break;
        case ActionBar.NAVIGATION_MODE_LIST:
            if (mListNavLayout != null) {
                if (showTitle) x += mItemPadding;
                x += positionChild(mListNavLayout, x, y, contentHeight) + mItemPadding;
            }
            break;
        case ActionBar.NAVIGATION_MODE_TABS:
            if (mTabScrollView != null) {
                if (showTitle) x += mItemPadding;
                x += positionChild(mTabScrollView, x, y, contentHeight) + mItemPadding;
            }
            break;
        }

        int menuLeft = r - l - getPaddingRight();
        if (mMenuView != null && mMenuView.getParent() == this) {
            positionChildInverse(mMenuView, menuLeft, y, contentHeight);
            menuLeft -= mMenuView.getMeasuredWidth();
        }

        if (mIndeterminateProgressView != null &&
                mIndeterminateProgressView.getVisibility() != GONE) {
            positionChildInverse(mIndeterminateProgressView, menuLeft, y, contentHeight);
            menuLeft -= mIndeterminateProgressView.getMeasuredWidth();
        }

        if (mCustomNavView != null && (mDisplayOptions & ActionBar.DISPLAY_SHOW_CUSTOM) != 0) {
            LayoutParams lp = mCustomNavView.getLayoutParams();
            final ActionBar.LayoutParams ablp = lp instanceof ActionBar.LayoutParams ?
                    (ActionBar.LayoutParams) lp : null;

            final int gravity = ablp != null ? ablp.gravity : DEFAULT_CUSTOM_GRAVITY;
            final int navWidth = mCustomNavView.getMeasuredWidth();

            int topMargin = 0;
            int bottomMargin = 0;
            if (ablp != null) {
                x += ablp.leftMargin;
                menuLeft -= ablp.rightMargin;
                topMargin = ablp.topMargin;
                bottomMargin = ablp.bottomMargin;
            }

            int hgravity = gravity & Gravity.HORIZONTAL_GRAVITY_MASK;
            // See if we actually have room to truly center; if not push against left or right.
            if (hgravity == Gravity.CENTER_HORIZONTAL) {
                final int centeredLeft = ((mRight - mLeft) - navWidth) / 2;
                if (centeredLeft < x) {
                    hgravity = Gravity.LEFT;
                } else if (centeredLeft + navWidth > menuLeft) {
                    hgravity = Gravity.RIGHT;
                }
            }

            int xpos = 0;
            switch (hgravity) {
                case Gravity.CENTER_HORIZONTAL:
                    xpos = ((mRight - mLeft) - navWidth) / 2;
                    break;
                case Gravity.LEFT:
                    xpos = x;
                    break;
                case Gravity.RIGHT:
                    xpos = menuLeft - navWidth;
                    break;
            }

            int ypos = 0;
            switch (gravity & Gravity.VERTICAL_GRAVITY_MASK) {
                case Gravity.CENTER_VERTICAL:
                    final int paddedTop = mTop + getPaddingTop();
                    final int paddedBottom = mBottom - getPaddingBottom();
                    ypos = ((paddedBottom - paddedTop) - mCustomNavView.getMeasuredHeight()) / 2;
                    break;
                case Gravity.TOP:
                    ypos = getPaddingTop() + topMargin;
                    break;
                case Gravity.BOTTOM:
                    ypos = getHeight() - getPaddingBottom() - mCustomNavView.getMeasuredHeight()
                            - bottomMargin;
                    break;
            }
            x += positionChild(mCustomNavView, xpos, ypos, contentHeight);
        }

        if (mProgressView != null) {
            mProgressView.bringToFront();
            final int halfProgressHeight = mProgressView.getMeasuredHeight() / 2;
            mProgressView.layout(mProgressBarPadding, -halfProgressHeight,
                    mProgressBarPadding + mProgressView.getMeasuredWidth(), halfProgressHeight);
        }
    }

    private static class TabView extends LinearLayout {
        private ActionBar.Tab mTab;
        private TextView mTextView;
        private ImageView mIconView;
        private View mCustomView;

        public TabView(Context context, ActionBar.Tab tab) {
            super(context, null, com.android.internal.R.attr.actionBarTabStyle);
            mTab = tab;

            update();

            setLayoutParams(new LayoutParams(LayoutParams.WRAP_CONTENT,
                    LayoutParams.MATCH_PARENT, 1));
        }

        public void update() {
            final ActionBar.Tab tab = mTab;
            final View custom = tab.getCustomView();
            if (custom != null) {
                addView(custom);
                mCustomView = custom;
                if (mTextView != null) mTextView.setVisibility(GONE);
                if (mIconView != null) {
                    mIconView.setVisibility(GONE);
                    mIconView.setImageDrawable(null);
                }
            } else {
                if (mCustomView != null) {
                    removeView(mCustomView);
                    mCustomView = null;
                }

                final Drawable icon = tab.getIcon();
                final CharSequence text = tab.getText();

                if (icon != null) {
                    if (mIconView == null) {
                        ImageView iconView = new ImageView(getContext());
                        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT,
                                LayoutParams.WRAP_CONTENT);
                        lp.gravity = Gravity.CENTER_VERTICAL;
                        iconView.setLayoutParams(lp);
                        addView(iconView, 0);
                        mIconView = iconView;
                    }
                    mIconView.setImageDrawable(icon);
                    mIconView.setVisibility(VISIBLE);
                } else if (mIconView != null) {
                    mIconView.setVisibility(GONE);
                    mIconView.setImageDrawable(null);
                }

                if (text != null) {
                    if (mTextView == null) {
                        TextView textView = new TextView(getContext(), null,
                                com.android.internal.R.attr.actionBarTabTextStyle);
                        textView.setSingleLine();
                        textView.setEllipsize(TruncateAt.END);
                        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT,
                                LayoutParams.WRAP_CONTENT);
                        lp.gravity = Gravity.CENTER_VERTICAL;
                        textView.setLayoutParams(lp);
                        addView(textView);
                        mTextView = textView;
                    }
                    mTextView.setText(text);
                    mTextView.setVisibility(VISIBLE);
                } else {
                    mTextView.setVisibility(GONE);
                }
            }
        }

        public ActionBar.Tab getTab() {
            return mTab;
        }
    }

    private class TabClickListener implements OnClickListener {
        public void onClick(View view) {
            TabView tabView = (TabView) view;
            tabView.getTab().select();
            final int tabCount = mTabLayout.getChildCount();
            for (int i = 0; i < tabCount; i++) {
                final View child = mTabLayout.getChildAt(i);
                child.setSelected(child == view);
            }
        }
    }

    private static class HomeView extends FrameLayout {
        private View mUpView;
        private View mIconView;

        public HomeView(Context context) {
            this(context, null);
        }

        public HomeView(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        @Override
        protected void onFinishInflate() {
            mUpView = findViewById(com.android.internal.R.id.up);
            mIconView = (ImageView) findViewById(com.android.internal.R.id.home);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            measureChildWithMargins(mUpView, widthMeasureSpec, 0, heightMeasureSpec, 0);
            final LayoutParams upLp = (LayoutParams) mUpView.getLayoutParams();
            int width = upLp.leftMargin + mUpView.getMeasuredWidth() + upLp.rightMargin;
            int height = upLp.topMargin + mUpView.getMeasuredHeight() + upLp.bottomMargin;
            measureChildWithMargins(mIconView, widthMeasureSpec, width, heightMeasureSpec, 0);
            final LayoutParams iconLp = (LayoutParams) mIconView.getLayoutParams();
            width += iconLp.leftMargin + mIconView.getMeasuredWidth() + iconLp.rightMargin;
            height = Math.max(height,
                    iconLp.topMargin + mIconView.getMeasuredHeight() + iconLp.bottomMargin);
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            final int vCenter = (b - t) / 2;
            int width = r - l;
            if (mUpView.getVisibility() != GONE) {
                final LayoutParams upLp = (LayoutParams) mUpView.getLayoutParams();
                final int upHeight = mUpView.getMeasuredHeight();
                final int upWidth = mUpView.getMeasuredWidth();
                final int upTop = t + vCenter - upHeight / 2;
                mUpView.layout(l, upTop, l + upWidth, upTop + upHeight);
                final int upOffset = upLp.leftMargin + upWidth + upLp.rightMargin;
                width -= upOffset;
                l += upOffset;
            }
            final LayoutParams iconLp = (LayoutParams) mIconView.getLayoutParams();
            final int iconHeight = mIconView.getMeasuredHeight();
            final int iconWidth = mIconView.getMeasuredWidth();
            final int hCenter = (r - l) / 2;
            final int iconLeft = l + iconLp.leftMargin + hCenter - iconWidth / 2;
            final int iconTop = t + iconLp.topMargin + vCenter - iconHeight / 2;
            mIconView.layout(iconLeft, iconTop, iconLeft + iconWidth, iconTop + iconHeight);
        }
    }
}
