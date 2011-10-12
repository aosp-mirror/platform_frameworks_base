/*
 * Copyright (C) 2011 The Android Open Source Project
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
package com.android.internal.view.menu;

import com.android.internal.view.menu.MenuView.ItemView;

import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.util.SparseArray;
import android.view.ContextThemeWrapper;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;

/**
 * MenuPresenter for the classic "six-pack" icon menu.
 */
public class IconMenuPresenter extends BaseMenuPresenter {
    private IconMenuItemView mMoreView;
    private int mMaxItems = -1;

    int mOpenSubMenuId;
    SubMenuPresenterCallback mSubMenuPresenterCallback = new SubMenuPresenterCallback();
    MenuDialogHelper mOpenSubMenu;

    private static final String VIEWS_TAG = "android:menu:icon";
    private static final String OPEN_SUBMENU_KEY = "android:menu:icon:submenu";

    public IconMenuPresenter(Context context) {
        super(new ContextThemeWrapper(context, com.android.internal.R.style.Theme_IconMenu),
                com.android.internal.R.layout.icon_menu_layout,
                com.android.internal.R.layout.icon_menu_item_layout);
    }

    @Override
    public void initForMenu(Context context, MenuBuilder menu) {
        super.initForMenu(context, menu);
        mMaxItems = -1;
    }

    @Override
    public void bindItemView(MenuItemImpl item, ItemView itemView) {
        final IconMenuItemView view = (IconMenuItemView) itemView;
        view.setItemData(item);

        view.initialize(item.getTitleForItemView(view), item.getIcon());

        view.setVisibility(item.isVisible() ? View.VISIBLE : View.GONE);
        view.setEnabled(view.isEnabled());
        view.setLayoutParams(view.getTextAppropriateLayoutParams());
    }

    @Override
    public boolean shouldIncludeItem(int childIndex, MenuItemImpl item) {
        final ArrayList<MenuItemImpl> itemsToShow = mMenu.getNonActionItems();
        boolean fits = (itemsToShow.size() == mMaxItems && childIndex < mMaxItems) ||
                childIndex < mMaxItems - 1;
        return fits && !item.isActionButton();
    }

    @Override
    protected void addItemView(View itemView, int childIndex) {
        final IconMenuItemView v = (IconMenuItemView) itemView;
        final IconMenuView parent = (IconMenuView) mMenuView;

        v.setIconMenuView(parent);
        v.setItemInvoker(parent);
        v.setBackgroundDrawable(parent.getItemBackgroundDrawable());
        super.addItemView(itemView, childIndex);
    }

    @Override
    public boolean onSubMenuSelected(SubMenuBuilder subMenu) {
        if (!subMenu.hasVisibleItems()) return false;

        // The window manager will give us a token.
        MenuDialogHelper helper = new MenuDialogHelper(subMenu);
        helper.setPresenterCallback(mSubMenuPresenterCallback);
        helper.show(null);
        mOpenSubMenu = helper;
        mOpenSubMenuId = subMenu.getItem().getItemId();
        super.onSubMenuSelected(subMenu);
        return true;
    }

    @Override
    public void updateMenuView(boolean cleared) {
        final IconMenuView menuView = (IconMenuView) mMenuView;
        if (mMaxItems < 0) mMaxItems = menuView.getMaxItems();
        final ArrayList<MenuItemImpl> itemsToShow = mMenu.getNonActionItems();
        final boolean needsMore = itemsToShow.size() > mMaxItems;
        super.updateMenuView(cleared);

        if (needsMore && (mMoreView == null || mMoreView.getParent() != menuView)) {
            if (mMoreView == null) {
                mMoreView = menuView.createMoreItemView();
                mMoreView.setBackgroundDrawable(menuView.getItemBackgroundDrawable());
            }
            menuView.addView(mMoreView);
        } else if (!needsMore && mMoreView != null) {
            menuView.removeView(mMoreView);
        }

        menuView.setNumActualItemsShown(needsMore ? mMaxItems - 1 : itemsToShow.size());
    }

    @Override
    protected boolean filterLeftoverView(ViewGroup parent, int childIndex) {
        if (parent.getChildAt(childIndex) != mMoreView) {
            return super.filterLeftoverView(parent, childIndex);
        }
        return false;
    }

    public int getNumActualItemsShown() {
        return ((IconMenuView) mMenuView).getNumActualItemsShown();
    }

    public void saveHierarchyState(Bundle outState) {
        SparseArray<Parcelable> viewStates = new SparseArray<Parcelable>();
        if (mMenuView != null) {
            ((View) mMenuView).saveHierarchyState(viewStates);
        }
        outState.putSparseParcelableArray(VIEWS_TAG, viewStates);
    }

    public void restoreHierarchyState(Bundle inState) {
        SparseArray<Parcelable> viewStates = inState.getSparseParcelableArray(VIEWS_TAG);
        if (viewStates != null) {
            ((View) mMenuView).restoreHierarchyState(viewStates);
        }
        int subMenuId = inState.getInt(OPEN_SUBMENU_KEY, 0);
        if (subMenuId > 0 && mMenu != null) {
            MenuItem item = mMenu.findItem(subMenuId);
            if (item != null) {
                onSubMenuSelected((SubMenuBuilder) item.getSubMenu());
            }
        }
    }

    @Override
    public Parcelable onSaveInstanceState() {
        if (mMenuView == null) {
            return null;
        }

        Bundle state = new Bundle();
        saveHierarchyState(state);
        if (mOpenSubMenuId > 0) {
            state.putInt(OPEN_SUBMENU_KEY, mOpenSubMenuId);
        }
        return state;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        restoreHierarchyState((Bundle) state);
    }

    class SubMenuPresenterCallback implements MenuPresenter.Callback {
        @Override
        public void onCloseMenu(MenuBuilder menu, boolean allMenusAreClosing) {
            mOpenSubMenuId = 0;
            if (mOpenSubMenu != null) {
                mOpenSubMenu.dismiss();
                mOpenSubMenu = null;
            }
        }

        @Override
        public boolean onOpenSubMenu(MenuBuilder subMenu) {
            if (subMenu != null) {
                mOpenSubMenuId = ((SubMenuBuilder) subMenu).getItem().getItemId();
            }
            return false;
        }

    }
}
