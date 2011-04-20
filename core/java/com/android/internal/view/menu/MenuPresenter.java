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

import android.content.Context;
import android.view.Menu;
import android.view.ViewGroup;

/**
 * A MenuPresenter is responsible for building views for a Menu object.
 * It takes over some responsibility from the old style monolithic MenuBuilder class.
 */
public interface MenuPresenter {
    /**
     * Called by menu implementation to notify another component of open/close events.
     */
    public interface Callback {
        /**
         * Called when a menu is closing.
         * @param menu
         * @param allMenusAreClosing
         */
        public void onCloseMenu(MenuBuilder menu, boolean allMenusAreClosing);

        /**
         * Called when a submenu opens. Useful for notifying the application
         * of menu state so that it does not attempt to hide the action bar
         * while a submenu is open or similar.
         *
         * @param subMenu Submenu currently being opened
         * @return true if the Callback will handle presenting the submenu, false if
         *         the presenter should attempt to do so.
         */
        public boolean onOpenSubMenu(MenuBuilder subMenu);
    }

    /**
     * Initialize this presenter for the given context and menu.
     * This method is called by MenuBuilder when a presenter is
     * added. See {@link MenuBuilder#addMenuPresenter(MenuPresenter)}
     *
     * @param context Context for this presenter; used for view creation and resource management
     * @param menu Menu to host
     */
    public void initForMenu(Context context, MenuBuilder menu);

    /**
     * Retrieve a MenuView to display the menu specified in
     * {@link #initForMenu(Context, Menu)}.
     *
     * @param root Intended parent of the MenuView.
     * @return A freshly created MenuView.
     */
    public MenuView getMenuView(ViewGroup root);

    /**
     * Update the menu UI in response to a change. Called by
     * MenuBuilder during the normal course of operation.
     *
     * @param cleared true if the menu was entirely cleared
     */
    public void updateMenuView(boolean cleared);

    /**
     * Set a callback object that will be notified of menu events
     * related to this specific presentation.
     * @param cb Callback that will be notified of future events
     */
    public void setCallback(Callback cb);

    /**
     * Called by Menu implementations to indicate that a submenu item
     * has been selected. An active Callback should be notified, and
     * if applicable the presenter should present the submenu.
     *
     * @param subMenu SubMenu being opened
     * @return true if the the event was handled, false otherwise.
     */
    public boolean onSubMenuSelected(SubMenuBuilder subMenu);

    /**
     * Called by Menu implementations to indicate that a menu or submenu is
     * closing. Presenter implementations should close the representation
     * of the menu indicated as necessary and notify a registered callback.
     *
     * @param menu Menu or submenu that is closing.
     * @param allMenusAreClosing True if all associated menus are closing.
     */
    public void onCloseMenu(MenuBuilder menu, boolean allMenusAreClosing);

    /**
     * Called by Menu implementations to flag items that will be shown as actions.
     * @return true if this presenter changed the action status of any items.
     */
    public boolean flagActionItems();
}
