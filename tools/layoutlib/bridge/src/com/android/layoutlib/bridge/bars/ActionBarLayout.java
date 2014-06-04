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

package com.android.layoutlib.bridge.bars;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.ide.common.rendering.api.ActionBarCallback;
import com.android.ide.common.rendering.api.ActionBarCallback.HomeButtonStyle;
import com.android.ide.common.rendering.api.RenderResources;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.rendering.api.SessionParams;
import com.android.internal.R;
import com.android.internal.app.WindowDecorActionBar;
import com.android.internal.view.menu.MenuBuilder;
import com.android.internal.view.menu.MenuItemImpl;
import com.android.internal.widget.ActionBarAccessor;
import com.android.internal.widget.ActionBarContainer;
import com.android.internal.widget.ActionBarView;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.impl.ResourceHelper;
import com.android.resources.ResourceType;

import android.app.ActionBar;
import android.app.ActionBar.Tab;
import android.app.ActionBar.TabListener;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.RelativeLayout;

import java.util.ArrayList;

/**
 * A layout representing the action bar.
 */
public class ActionBarLayout extends LinearLayout {

    // Store another reference to the context so that we don't have to cast it repeatedly.
    @NonNull private final BridgeContext mBridgeContext;
    @NonNull private final Context mThemedContext;

    @NonNull private final ActionBar mActionBar;

    // Data for Action Bar.
    @Nullable private final String mIcon;
    @Nullable private final String mTitle;
    @Nullable private final String mSubTitle;
    private final boolean mSplit;
    private final boolean mShowHomeAsUp;
    private final int mNavMode;

    // Helper fields.
    @NonNull private final MenuBuilder mMenuBuilder;
    private final int mPopupMaxWidth;
    @NonNull private final RenderResources res;
    @Nullable private final ActionBarView mActionBarView;
    @Nullable private FrameLayout mContentRoot;
    @NonNull private final ActionBarCallback mCallback;

    // A fake parent for measuring views.
    @Nullable private ViewGroup mMeasureParent;

    public ActionBarLayout(@NonNull BridgeContext context, @NonNull SessionParams params) {

        super(context);
        setOrientation(LinearLayout.HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        // Inflate action bar layout.
        LayoutInflater.from(context).inflate(R.layout.screen_action_bar, this,
                true /*attachToRoot*/);
        mActionBar = new WindowDecorActionBar(this);

        // Set contexts.
        mBridgeContext = context;
        mThemedContext = mActionBar.getThemedContext();

        // Set data for action bar.
        mCallback = params.getProjectCallback().getActionBarCallback();
        mIcon = params.getAppIcon();
        mTitle = params.getAppLabel();
        // Split Action Bar when the screen size is narrow and the application requests split action
        // bar when narrow.
        mSplit = context.getResources().getBoolean(R.bool.split_action_bar_is_narrow) &&
                mCallback.getSplitActionBarWhenNarrow();
        mNavMode = mCallback.getNavigationMode();
        // TODO: Support Navigation Drawer Indicator.
        mShowHomeAsUp = mCallback.getHomeButtonStyle() == HomeButtonStyle.SHOW_HOME_AS_UP;
        mSubTitle = mCallback.getSubTitle();


        // Set helper fields.
        mMenuBuilder = new MenuBuilder(mThemedContext);
        res = mBridgeContext.getRenderResources();
        mPopupMaxWidth = Math.max(mBridgeContext.getMetrics().widthPixels / 2,
                mThemedContext.getResources().getDimensionPixelSize(
                        R.dimen.config_prefDialogWidth));
        mActionBarView = (ActionBarView) findViewById(R.id.action_bar);
        mContentRoot = (FrameLayout) findViewById(android.R.id.content);

        setupActionBar();
    }

    /**
     * Sets up the action bar by filling the appropriate data.
     */
    private void setupActionBar() {
        // Add title and sub title.
        ResourceValue titleValue = res.findResValue(mTitle, false /*isFramework*/);
        if (titleValue != null && titleValue.getValue() != null) {
            mActionBar.setTitle(titleValue.getValue());
        } else {
            mActionBar.setTitle(mTitle);
        }
        if (mSubTitle != null) {
            mActionBar.setSubtitle(mSubTitle);
        }

        // Add show home as up icon.
        if (mShowHomeAsUp) {
            mActionBar.setDisplayOptions(0xFF, ActionBar.DISPLAY_HOME_AS_UP);
        }

        // Set the navigation mode.
        mActionBar.setNavigationMode(mNavMode);
        if (mNavMode == ActionBar.NAVIGATION_MODE_TABS) {
            setupTabs(3);
        }

        if (mActionBarView != null) {
            // If the action bar style doesn't specify an icon, set the icon obtained from the session
            // params.
            if (!mActionBarView.hasIcon() && mIcon != null) {
                Drawable iconDrawable = getDrawable(mIcon, false /*isFramework*/);
                if (iconDrawable != null) {
                    mActionBar.setIcon(iconDrawable);
                }
            }

            // Set action bar to be split, if needed.
            ActionBarContainer splitView = (ActionBarContainer) findViewById(R.id.split_action_bar);
            mActionBarView.setSplitView(splitView);
            mActionBarView.setSplitToolbar(mSplit);

            inflateMenus();
        }
    }

    /**
     * Gets the menus to add to the action bar from the callback, resolves them, inflates them and
     * adds them to the action bar.
     */
    private void inflateMenus() {
        if (mActionBarView == null) {
            return;
        }
        final MenuInflater inflater = new MenuInflater(mThemedContext);
        for (String name : mCallback.getMenuIdNames()) {
            if (mBridgeContext.getRenderResources().getProjectResource(ResourceType.MENU, name)
                    != null) {
                int id = mBridgeContext.getProjectResourceValue(ResourceType.MENU, name, -1);
                if (id > -1) {
                    inflater.inflate(id, mMenuBuilder);
                }
            }
        }
        mActionBarView.setMenu(mMenuBuilder, null /*callback*/);
    }

    // TODO: Use an adapter, like List View to set up tabs.
    private void setupTabs(int num) {
        for (int i = 1; i <= num; i++) {
            Tab tab = mActionBar.newTab().setText("Tab" + i).setTabListener(new TabListener() {
                @Override
                public void onTabUnselected(Tab t, FragmentTransaction ft) {
                    // pass
                }
                @Override
                public void onTabSelected(Tab t, FragmentTransaction ft) {
                    // pass
                }
                @Override
                public void onTabReselected(Tab t, FragmentTransaction ft) {
                    // pass
                }
            });
            mActionBar.addTab(tab);
        }
    }

    @Nullable
    private Drawable getDrawable(@NonNull String name, boolean isFramework) {
        ResourceValue value = res.findResValue(name, isFramework);
        value = res.resolveResValue(value);
        if (value != null) {
            return ResourceHelper.getDrawable(value, mBridgeContext);
        }
        return null;
    }

    /**
     * Creates a Popup and adds it to the content frame. It also adds another {@link FrameLayout} to
     * the content frame which shall serve as the new content root.
     */
    public void createMenuPopup() {
        assert mContentRoot != null && findViewById(android.R.id.content) == mContentRoot
                : "Action Bar Menus have already been created.";

        if (!isOverflowPopupNeeded()) {
            return;
        }

        // Create a layout to hold the menus and the user's content.
        RelativeLayout layout = new RelativeLayout(mThemedContext);
        layout.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));
        mContentRoot.addView(layout);
        // Create a layout for the user's content.
        FrameLayout contentRoot = new FrameLayout(mBridgeContext);
        contentRoot.setLayoutParams(new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        // Add contentRoot and menus to the layout.
        layout.addView(contentRoot);
        layout.addView(createMenuView());
        // ContentRoot is now the view we just created.
        mContentRoot = contentRoot;
    }

    /**
     * Returns a {@link LinearLayout} containing the menu list view to be embedded in a
     * {@link RelativeLayout}
     */
    @NonNull
    private View createMenuView() {
        DisplayMetrics metrics = mBridgeContext.getMetrics();
        OverflowMenuAdapter adapter = new OverflowMenuAdapter(mMenuBuilder, mThemedContext);

        LinearLayout layout = new LinearLayout(mThemedContext);
        RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(
                measureContentWidth(adapter), LayoutParams.WRAP_CONTENT);
        layoutParams.addRule(RelativeLayout.ALIGN_PARENT_END);
        if (mSplit) {
            layoutParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            // TODO: Find correct value instead of hardcoded 10dp.
            layoutParams.bottomMargin = getPixelValue("-10dp", metrics);
        } else {
            layoutParams.topMargin = getPixelValue("-10dp", metrics);
        }
        layout.setLayoutParams(layoutParams);
        final TypedArray a = mThemedContext.obtainStyledAttributes(null,
                R.styleable.PopupWindow, R.attr.popupMenuStyle, 0);
        layout.setBackground(a.getDrawable(R.styleable.PopupWindow_popupBackground));
        layout.setDividerDrawable(a.getDrawable(R.attr.actionBarDivider));
        a.recycle();
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setDividerPadding(getPixelValue("12dp", metrics));
        layout.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);

        ListView listView = new ListView(mThemedContext, null, R.attr.dropDownListViewStyle);
        listView.setAdapter(adapter);
        layout.addView(listView);
        return layout;
    }

    private boolean isOverflowPopupNeeded() {
        boolean needed = mCallback.isOverflowPopupNeeded();
        if (!needed) {
            return false;
        }
        // Copied from android.widget.ActionMenuPresenter.updateMenuView()
        ArrayList<MenuItemImpl> menus = mMenuBuilder.getNonActionItems();
        if (ActionBarAccessor.getActionMenuPresenter(mActionBarView).isOverflowReserved() &&
                menus != null) {
            final int count = menus.size();
            if (count == 1) {
                needed = !menus.get(0).isActionViewExpanded();
            } else {
                needed = count > 0;
            }
        }
        return needed;
    }

    @Nullable
    public FrameLayout getContentRoot() {
        return mContentRoot;
    }

    // Copied from com.android.internal.view.menu.MenuPopHelper.measureContentWidth()
    private int measureContentWidth(@NonNull ListAdapter adapter) {
        // Menus don't tend to be long, so this is more sane than it looks.
        int maxWidth = 0;
        View itemView = null;
        int itemType = 0;

        final int widthMeasureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        final int heightMeasureSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        final int count = adapter.getCount();
        for (int i = 0; i < count; i++) {
            final int positionType = adapter.getItemViewType(i);
            if (positionType != itemType) {
                itemType = positionType;
                itemView = null;
            }

            if (mMeasureParent == null) {
                mMeasureParent = new FrameLayout(mThemedContext);
            }

            itemView = adapter.getView(i, itemView, mMeasureParent);
            itemView.measure(widthMeasureSpec, heightMeasureSpec);

            final int itemWidth = itemView.getMeasuredWidth();
            if (itemWidth >= mPopupMaxWidth) {
                return mPopupMaxWidth;
            } else if (itemWidth > maxWidth) {
                maxWidth = itemWidth;
            }
        }

        return maxWidth;
    }

    private int getPixelValue(@NonNull String value, @NonNull DisplayMetrics metrics) {
        TypedValue typedValue = ResourceHelper.getValue(null, value, false /*requireUnit*/);
        return (int) typedValue.getDimension(metrics);
    }

}
