/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.content.Context;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ListAdapter;
import android.widget.PopupWindow;

/**
 * Base class for a menu popup abstraction - i.e., some type of menu, housed in a popup window
 * environment.
 *
 * @hide
 */
public abstract class MenuPopup implements ShowableListMenu, MenuPresenter {

    public abstract void setForceShowIcon(boolean forceShow);

    /**
     * Adds the given menu to the popup. If this is the first menu shown it'll be displayed; if it's
     * a submenu it will be displayed adjacent to the most recent menu (if supported by the
     * implementation).
     *
     * @param menu
     */
    public abstract void addMenu(MenuBuilder menu);

    public abstract void setGravity(int dropDownGravity);

    public abstract void setAnchorView(View anchor);

    /**
     * Set a listener to receive a callback when the popup is dismissed.
     *
     * @param listener Listener that will be notified when the popup is dismissed.
     */
    public abstract void setOnDismissListener(PopupWindow.OnDismissListener listener);

    @Override
    public void initForMenu(Context context, MenuBuilder menu) {
        // Don't need to do anything; we added as a presenter in the constructor.
    }

    @Override
    public MenuView getMenuView(ViewGroup root) {
        throw new UnsupportedOperationException("MenuPopups manage their own views");
    }

    @Override
    public boolean expandItemActionView(MenuBuilder menu, MenuItemImpl item) {
        return false;
    }

    @Override
    public boolean collapseItemActionView(MenuBuilder menu, MenuItemImpl item) {
        return false;
    }

    @Override
    public int getId() {
        return 0;
    }

    /**
     * Measures the width of the given menu view.
     *
     * @param view The view to measure.
     * @return The width.
     */
    protected static int measureIndividualMenuWidth(ListAdapter adapter, ViewGroup parent,
            Context context, int maxAllowedWidth) {
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

            if (parent == null) {
                parent = new FrameLayout(context);
            }

            itemView = adapter.getView(i, itemView, parent);
            itemView.measure(widthMeasureSpec, heightMeasureSpec);

            final int itemWidth = itemView.getMeasuredWidth();
            if (itemWidth >= maxAllowedWidth) {
                return maxAllowedWidth;
            } else if (itemWidth > maxWidth) {
                maxWidth = itemWidth;
            }
        }

        return maxWidth;
    }
}