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
import com.android.ide.common.rendering.api.RenderResources;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.rendering.api.SessionParams;
import com.android.internal.R;
import com.android.internal.view.menu.MenuBuilder;
import com.android.internal.view.menu.MenuItemImpl;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.impl.ResourceHelper;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.InflateException;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.ActionMenuPresenter;
import android.widget.FrameLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.RelativeLayout;

import java.util.ArrayList;

/**
 * Creates the ActionBar as done by the framework.
 */
public class FrameworkActionBar extends BridgeActionBar {

    private static final String LAYOUT_ATTR_NAME = "windowActionBarFullscreenDecorLayout";

    // The Action Bar
    @NonNull private FrameworkActionBarWrapper mActionBar;

    // A fake parent for measuring views.
    @Nullable private ViewGroup mMeasureParent;

    /**
     * Inflate the action bar and attach it to {@code parentView}
     */
    public FrameworkActionBar(@NonNull BridgeContext context, @NonNull SessionParams params,
            @NonNull ViewGroup parentView) {
        super(context, params, parentView);

        View decorContent = getDecorContent();

        mActionBar = FrameworkActionBarWrapper.getActionBarWrapper(context, getCallBack(),
                decorContent);

        FrameLayout contentRoot = (FrameLayout) mEnclosingLayout.findViewById(android.R.id.content);

        // If something went wrong and we were not able to initialize the content root,
        // just add a frame layout inside this and return.
        if (contentRoot == null) {
            contentRoot = new FrameLayout(context);
            setMatchParent(contentRoot);
            mEnclosingLayout.addView(contentRoot);
            setContentRoot(contentRoot);
        } else {
            setContentRoot(contentRoot);
            setupActionBar();
            mActionBar.inflateMenus();
        }
    }

    @Override
    protected ResourceValue getLayoutResource(BridgeContext context) {
        ResourceValue layoutName =
                context.getRenderResources().findItemInTheme(LAYOUT_ATTR_NAME, true);
        if (layoutName != null) {
            // We may need to resolve the reference obtained.
            layoutName = context.getRenderResources().findResValue(layoutName.getValue(),
                    layoutName.isFramework());
        }
        if (layoutName == null) {
             throw new InflateException("Unable to find action bar layout (" + LAYOUT_ATTR_NAME
                    + ") in the current theme.");
        }
        return layoutName;
    }

    @Override
    protected void setupActionBar() {
        super.setupActionBar();
        mActionBar.setupActionBar();
    }

    @Override
    protected void setHomeAsUp(boolean homeAsUp) {
        mActionBar.setHomeAsUp(homeAsUp);
    }

    @Override
    protected void setTitle(CharSequence title) {
        mActionBar.setTitle(title);
    }

    @Override
    protected void setSubtitle(CharSequence subtitle) {
        mActionBar.setSubTitle(subtitle);
    }

    @Override
    protected void setIcon(String icon) {
        mActionBar.setIcon(icon);
    }

    /**
     * Creates a Popup and adds it to the content frame. It also adds another {@link FrameLayout} to
     * the content frame which shall serve as the new content root.
     */
    @Override
    public void createMenuPopup() {
        if (!isOverflowPopupNeeded()) {
            return;
        }

        DisplayMetrics metrics = mBridgeContext.getMetrics();
        MenuBuilder menu = mActionBar.getMenuBuilder();
        OverflowMenuAdapter adapter = new OverflowMenuAdapter(menu, mActionBar.getPopupContext());

        ListView listView = new ListView(mActionBar.getPopupContext(), null,
                R.attr.dropDownListViewStyle);
        RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(
                measureContentWidth(adapter), LayoutParams.WRAP_CONTENT);
        layoutParams.addRule(RelativeLayout.ALIGN_PARENT_END);
        if (mActionBar.isSplit()) {
            layoutParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            layoutParams.bottomMargin = getActionBarHeight() + mActionBar.getMenuPopupMargin();
        } else {
            layoutParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            layoutParams.topMargin = getActionBarHeight() + mActionBar.getMenuPopupMargin();
        }
        layoutParams.setMarginEnd(getPixelValue("5dp", metrics));
        listView.setLayoutParams(layoutParams);
        listView.setAdapter(adapter);
        final TypedArray a = mActionBar.getPopupContext().obtainStyledAttributes(null,
                R.styleable.PopupWindow, R.attr.popupMenuStyle, 0);
        listView.setBackground(a.getDrawable(R.styleable.PopupWindow_popupBackground));
        listView.setDivider(a.getDrawable(R.attr.actionBarDivider));
        a.recycle();
        listView.setElevation(mActionBar.getMenuPopupElevation());
        mEnclosingLayout.addView(listView);
    }

    private boolean isOverflowPopupNeeded() {
        boolean needed = mActionBar.isOverflowPopupNeeded();
        if (!needed) {
            return false;
        }
        // Copied from android.widget.ActionMenuPresenter.updateMenuView()
        ArrayList<MenuItemImpl> menus = mActionBar.getMenuBuilder().getNonActionItems();
        ActionMenuPresenter presenter = mActionBar.getActionMenuPresenter();
        if (presenter == null) {
            throw new RuntimeException("Failed to create a Presenter for Action Bar Menus.");
        }
        if (presenter.isOverflowReserved() &&
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

    // Copied from com.android.internal.view.menu.MenuPopHelper.measureContentWidth()
    private int measureContentWidth(@NonNull ListAdapter adapter) {
        // Menus don't tend to be long, so this is more sane than it looks.
        int maxWidth = 0;
        View itemView = null;
        int itemType = 0;

        Context context = mActionBar.getPopupContext();
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
                mMeasureParent = new FrameLayout(context);
            }

            itemView = adapter.getView(i, itemView, mMeasureParent);
            itemView.measure(widthMeasureSpec, heightMeasureSpec);

            final int itemWidth = itemView.getMeasuredWidth();
            int popupMaxWidth = Math.max(mBridgeContext.getMetrics().widthPixels / 2,
                    context.getResources().getDimensionPixelSize(R.dimen.config_prefDialogWidth));
            if (itemWidth >= popupMaxWidth) {
                return popupMaxWidth;
            } else if (itemWidth > maxWidth) {
                maxWidth = itemWidth;
            }
        }

        return maxWidth;
    }

    static int getPixelValue(@NonNull String value, @NonNull DisplayMetrics metrics) {
        TypedValue typedValue = ResourceHelper.getValue(null, value, false /*requireUnit*/);
        return (int) typedValue.getDimension(metrics);
    }

    // TODO: This is duplicated from RenderSessionImpl.
    private int getActionBarHeight() {
        RenderResources resources = mBridgeContext.getRenderResources();
        DisplayMetrics metrics = mBridgeContext.getMetrics();
        ResourceValue value = resources.findItemInTheme("actionBarSize", true);

        // resolve it
        value = resources.resolveResValue(value);

        if (value != null) {
            // get the numerical value, if available
            TypedValue typedValue = ResourceHelper.getValue("actionBarSize", value.getValue(),
                    true);
            if (typedValue != null) {
                // compute the pixel value based on the display metrics
                return (int) typedValue.getDimension(metrics);

            }
        }
        return 0;
    }
}
