/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ListAdapter;

/**
 * Helper for menus that appear as Dialogs (context and submenus).
 * 
 * @hide
 */
public class MenuDialogHelper implements DialogInterface.OnKeyListener, DialogInterface.OnClickListener {
    private MenuBuilder mMenu;
    private ListAdapter mAdapter;
    private AlertDialog mDialog;
    
    public MenuDialogHelper(MenuBuilder menu) {
        mMenu = menu;
    }

    /**
     * Shows menu as a dialog. 
     * 
     * @param windowToken Optional token to assign to the window.
     */
    public void show(IBinder windowToken) {
        // Many references to mMenu, create local reference
        final MenuBuilder menu = mMenu;
        
        // Get an adapter for the menu item views
        mAdapter = menu.getMenuAdapter(MenuBuilder.TYPE_DIALOG);
        
        // Get the builder for the dialog
        final AlertDialog.Builder builder = new AlertDialog.Builder(menu.getContext())
                .setAdapter(mAdapter, this); 

        // Set the title
        final View headerView = menu.getHeaderView();
        if (headerView != null) {
            // Menu's client has given a custom header view, use it
            builder.setCustomTitle(headerView);
        } else {
            // Otherwise use the (text) title and icon
            builder.setIcon(menu.getHeaderIcon()).setTitle(menu.getHeaderTitle());
        }
        
        // Set the key listener
        builder.setOnKeyListener(this);
        
        // Show the menu
        mDialog = builder.create();
        
        WindowManager.LayoutParams lp = mDialog.getWindow().getAttributes();
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
        if (windowToken != null) {
            lp.token = windowToken;
        }
        lp.flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        
        mDialog.show();
    }
    
    public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
        /*
         * Close menu on key down (more responsive, and there's no way to cancel
         * a key press so no point having it on key up. Note: This is also
         * needed because when a top-level menu item that shows a submenu is
         * invoked by chording, this onKey method will be called with the menu
         * up event.
         */
        if (event.getAction() == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_MENU)
                || (keyCode == KeyEvent.KEYCODE_BACK)) {
            mMenu.close(true);
            dialog.dismiss();
            return true;
        }

        // Menu shortcut matching
        if (mMenu.performShortcut(keyCode, event, 0)) {
            return true;
        }
        
        return false;
    }

    /**
     * Dismisses the menu's dialog.
     * 
     * @see Dialog#dismiss()
     */
    public void dismiss() {
        if (mDialog != null) {
            mDialog.dismiss();
        }
    }
    
    public void onClick(DialogInterface dialog, int which) {
        mMenu.performItemAction((MenuItemImpl) mAdapter.getItem(which), 0);
    }
    
}
