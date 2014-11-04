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
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.widget.ActionMenuPresenter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.RelativeLayout;

import java.util.ArrayList;

public class ActionBarLayout {

    private static final String LAYOUT_ATTR_NAME = "windowActionBarFullscreenDecorLayout";

    // The Action Bar
    @NonNull private CustomActionBarWrapper mActionBar;

    // Store another reference to the context so that we don't have to cast it repeatedly.
    @NonNull private final BridgeContext mBridgeContext;

    @NonNull private FrameLayout mContentRoot;

    // A fake parent for measuring views.
    @Nullable private ViewGroup mMeasureParent;

    /**
     * Inflate the action bar and attach it to {@code parentView}
     */
    public ActionBarLayout(@NonNull BridgeContext context, @NonNull SessionParams params,
            @NonNull ViewGroup parentView) {

        mBridgeContext = context;

        ResourceValue layoutName = context.getRenderResources()
                .findItemInTheme(LAYOUT_ATTR_NAME, true);
        if (layoutName != null) {
            // We may need to resolve the reference obtained.
            layoutName = context.getRenderResources().findResValue(layoutName.getValue(),
                    layoutName.isFramework());
        }
        int layoutId = 0;
        String error = null;
        if (layoutName == null) {
            error = "Unable to find action bar layout (" + LAYOUT_ATTR_NAME
                    + ") in the current theme.";
        } else {
            layoutId = context.getFrameworkResourceValue(layoutName.getResourceType(),
                    layoutName.getName(), 0);
            if (layoutId == 0) {
                error = String.format("Unable to resolve attribute \"%s\" of type \"%s\"",
                        layoutName.getName(), layoutName.getResourceType());
            }
        }
        if (layoutId == 0) {
            throw new RuntimeException(error);
        }
        // Inflate action bar layout.
        View decorContent = LayoutInflater.from(context).inflate(layoutId, parentView, true);

        mActionBar = CustomActionBarWrapper.getActionBarWrapper(context, params, decorContent);

        FrameLayout contentRoot = (FrameLayout) parentView.findViewById(android.R.id.content);

        // If something went wrong and we were not able to initialize the content root,
        // just add a frame layout inside this and return.
        if (contentRoot == null) {
            contentRoot = new FrameLayout(context);
            contentRoot.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT,
                    LayoutParams.MATCH_PARENT));
            parentView.addView(contentRoot);
            mContentRoot = contentRoot;
        } else {
            mContentRoot = contentRoot;
            mActionBar.setupActionBar();
            mActionBar.inflateMenus();
        }
    }

    /**
     * Creates a Popup and adds it to the content frame. It also adds another {@link FrameLayout} to
     * the content frame which shall serve as the new content root.
     */
    public void createMenuPopup() {
        assert mContentRoot.getId() == android.R.id.content
                : "Action Bar Menus have already been created.";

        if (!isOverflowPopupNeeded()) {
            return;
        }

        // Create a layout to hold the menus and the user's content.
        RelativeLayout layout = new RelativeLayout(mActionBar.getPopupContext());
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
        MenuBuilder menu = mActionBar.getMenuBuilder();
        OverflowMenuAdapter adapter = new OverflowMenuAdapter(menu, mActionBar.getPopupContext());

        LinearLayout layout = new LinearLayout(mActionBar.getPopupContext());
        RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(
                measureContentWidth(adapter), LayoutParams.WRAP_CONTENT);
        layoutParams.addRule(RelativeLayout.ALIGN_PARENT_END);
        if (mActionBar.isSplit()) {
            layoutParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            // TODO: Find correct value instead of hardcoded 10dp.
            layoutParams.bottomMargin = getPixelValue("-10dp", metrics);
        } else {
            layoutParams.topMargin = getPixelValue("-10dp", metrics);
        }
        layout.setLayoutParams(layoutParams);
        final TypedArray a = mActionBar.getPopupContext().obtainStyledAttributes(null,
                R.styleable.PopupWindow, R.attr.popupMenuStyle, 0);
        layout.setBackground(a.getDrawable(R.styleable.PopupWindow_popupBackground));
        layout.setDividerDrawable(a.getDrawable(R.attr.actionBarDivider));
        a.recycle();
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setDividerPadding(getPixelValue("12dp", metrics));
        layout.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);

        ListView listView = new ListView(mActionBar.getPopupContext(), null,
                R.attr.dropDownListViewStyle);
        listView.setAdapter(adapter);
        layout.addView(listView);
        return layout;
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

    @NonNull
    public FrameLayout getContentRoot() {
        return mContentRoot;
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

    private int getPixelValue(@NonNull String value, @NonNull DisplayMetrics metrics) {
        TypedValue typedValue = ResourceHelper.getValue(null, value, false /*requireUnit*/);
        return (int) typedValue.getDimension(metrics);
    }

}
