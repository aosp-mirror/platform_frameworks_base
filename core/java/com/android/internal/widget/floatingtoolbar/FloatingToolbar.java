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

package com.android.internal.widget.floatingtoolbar;

import android.annotation.Nullable;
import android.graphics.Rect;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnLayoutChangeListener;
import android.view.Window;
import android.widget.PopupWindow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * A floating toolbar for showing contextual menu items.
 * This view shows as many menu item buttons as can fit in the horizontal toolbar and the
 * the remaining menu items in a vertical overflow view when the overflow button is clicked.
 * The horizontal toolbar morphs into the vertical overflow view.
 */
public final class FloatingToolbar {

    // This class is responsible for the public API of the floating toolbar.
    // It delegates rendering operations to the FloatingToolbarPopup.

    public static final String FLOATING_TOOLBAR_TAG = "floating_toolbar";

    private static final MenuItem.OnMenuItemClickListener NO_OP_MENUITEM_CLICK_LISTENER =
            item -> false;

    private final Window mWindow;
    private final FloatingToolbarPopup mPopup;

    private final Rect mContentRect = new Rect();

    private Menu mMenu;
    private MenuItem.OnMenuItemClickListener mMenuItemClickListener = NO_OP_MENUITEM_CLICK_LISTENER;

    private final OnLayoutChangeListener mOrientationChangeHandler = new OnLayoutChangeListener() {

        private final Rect mNewRect = new Rect();
        private final Rect mOldRect = new Rect();

        @Override
        public void onLayoutChange(
                View view,
                int newLeft, int newRight, int newTop, int newBottom,
                int oldLeft, int oldRight, int oldTop, int oldBottom) {
            mNewRect.set(newLeft, newRight, newTop, newBottom);
            mOldRect.set(oldLeft, oldRight, oldTop, oldBottom);
            if (mPopup.isShowing() && !mNewRect.equals(mOldRect)) {
                mPopup.setWidthChanged(true);
                updateLayout();
            }
        }
    };

    /**
     * Sorts the list of menu items to conform to certain requirements.
     */
    private final Comparator<MenuItem> mMenuItemComparator = (menuItem1, menuItem2) -> {
        // Ensure the assist menu item is always the first item:
        if (menuItem1.getItemId() == android.R.id.textAssist) {
            return menuItem2.getItemId() == android.R.id.textAssist ? 0 : -1;
        }
        if (menuItem2.getItemId() == android.R.id.textAssist) {
            return 1;
        }

        // Order by SHOW_AS_ACTION type:
        if (menuItem1.requiresActionButton()) {
            return menuItem2.requiresActionButton() ? 0 : -1;
        }
        if (menuItem2.requiresActionButton()) {
            return 1;
        }
        if (menuItem1.requiresOverflow()) {
            return menuItem2.requiresOverflow() ? 0 : 1;
        }
        if (menuItem2.requiresOverflow()) {
            return -1;
        }

        // Order by order value:
        return menuItem1.getOrder() - menuItem2.getOrder();
    };

    /**
     * Initializes a floating toolbar.
     */
    public FloatingToolbar(Window window) {
        // TODO(b/65172902): Pass context in constructor when DecorView (and other callers)
        // supports multi-display.
        mWindow = Objects.requireNonNull(window);
        mPopup = FloatingToolbarPopup.createInstance(window.getContext(), window.getDecorView());
    }

    /**
     * Sets the menu to be shown in this floating toolbar.
     * NOTE: Call {@link #updateLayout()} or {@link #show()} to effect visual changes to the
     * toolbar.
     */
    public FloatingToolbar setMenu(Menu menu) {
        mMenu = Objects.requireNonNull(menu);
        return this;
    }

    /**
     * Sets the custom listener for invocation of menu items in this floating toolbar.
     */
    public FloatingToolbar setOnMenuItemClickListener(
            MenuItem.OnMenuItemClickListener menuItemClickListener) {
        if (menuItemClickListener != null) {
            mMenuItemClickListener = menuItemClickListener;
        } else {
            mMenuItemClickListener = NO_OP_MENUITEM_CLICK_LISTENER;
        }
        return this;
    }

    /**
     * Sets the content rectangle. This is the area of the interesting content that this toolbar
     * should avoid obstructing.
     * NOTE: Call {@link #updateLayout()} or {@link #show()} to effect visual changes to the
     * toolbar.
     */
    public FloatingToolbar setContentRect(Rect rect) {
        mContentRect.set(Objects.requireNonNull(rect));
        return this;
    }

    /**
     * Sets the suggested width of this floating toolbar.
     * The actual width will be about this size but there are no guarantees that it will be exactly
     * the suggested width.
     * NOTE: Call {@link #updateLayout()} or {@link #show()} to effect visual changes to the
     * toolbar.
     */
    public FloatingToolbar setSuggestedWidth(int suggestedWidth) {
        mPopup.setSuggestedWidth(suggestedWidth);
        return this;
    }

    /**
     * Shows this floating toolbar.
     */
    public FloatingToolbar show() {
        registerOrientationHandler();
        doShow();
        return this;
    }

    /**
     * Updates this floating toolbar to reflect recent position and view updates.
     * NOTE: This method is a no-op if the toolbar isn't showing.
     */
    public FloatingToolbar updateLayout() {
        if (mPopup.isShowing()) {
            doShow();
        }
        return this;
    }

    /**
     * Dismisses this floating toolbar.
     */
    public void dismiss() {
        unregisterOrientationHandler();
        mPopup.dismiss();
    }

    /**
     * Hides this floating toolbar. This is a no-op if the toolbar is not showing.
     * Use {@link #isHidden()} to distinguish between a hidden and a dismissed toolbar.
     */
    public void hide() {
        mPopup.hide();
    }

    /**
     * Returns {@code true} if this toolbar is currently showing. {@code false} otherwise.
     */
    public boolean isShowing() {
        return mPopup.isShowing();
    }

    /**
     * Returns {@code true} if this toolbar is currently hidden. {@code false} otherwise.
     */
    public boolean isHidden() {
        return mPopup.isHidden();
    }

    /**
     * If this is set to true, the action mode view will dismiss itself on touch events outside of
     * its window. The setting takes effect immediately.
     *
     * @param outsideTouchable whether or not this action mode is "outside touchable"
     * @param onDismiss optional. Sets a callback for when this action mode popup dismisses itself
     */
    public void setOutsideTouchable(
            boolean outsideTouchable, @Nullable PopupWindow.OnDismissListener onDismiss) {
        mPopup.setOutsideTouchable(outsideTouchable, onDismiss);
    }

    private void doShow() {
        List<MenuItem> menuItems = getVisibleAndEnabledMenuItems(mMenu);
        menuItems.sort(mMenuItemComparator);
        mPopup.show(menuItems, mMenuItemClickListener, mContentRect);
    }

    /**
     * Returns the visible and enabled menu items in the specified menu.
     * This method is recursive.
     */
    private static List<MenuItem> getVisibleAndEnabledMenuItems(Menu menu) {
        List<MenuItem> menuItems = new ArrayList<>();
        for (int i = 0; (menu != null) && (i < menu.size()); i++) {
            MenuItem menuItem = menu.getItem(i);
            if (menuItem.isVisible() && menuItem.isEnabled()) {
                Menu subMenu = menuItem.getSubMenu();
                if (subMenu != null) {
                    menuItems.addAll(getVisibleAndEnabledMenuItems(subMenu));
                } else {
                    menuItems.add(menuItem);
                }
            }
        }
        return menuItems;
    }

    private void registerOrientationHandler() {
        unregisterOrientationHandler();
        mWindow.getDecorView().addOnLayoutChangeListener(mOrientationChangeHandler);
    }

    private void unregisterOrientationHandler() {
        mWindow.getDecorView().removeOnLayoutChangeListener(mOrientationChangeHandler);
    }
}
