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

package com.android.internal.view.menu;

import com.android.layoutlib.bridge.android.BridgeContext;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;

/**
 * An extension of the {@link MenuItemImpl} to store the view cookie also.
 */
public class BridgeMenuItemImpl extends MenuItemImpl {

    /**
     * An object returned by the IDE that helps mapping each View to the corresponding XML tag in
     * the layout. For Menus, we store this cookie here and attach it to the corresponding view
     * at the time of rendering.
     */
    private Object viewCookie;
    private BridgeContext mContext;

    /**
     * Instantiates this menu item.
     */
    BridgeMenuItemImpl(MenuBuilder menu, int group, int id, int categoryOrder, int ordering,
            CharSequence title, int showAsAction) {
        super(menu, group, id, categoryOrder, ordering, title, showAsAction);
        Context context = menu.getContext();
        while (context instanceof ContextThemeWrapper) {
            context = ((ContextThemeWrapper) context).getBaseContext();
        }
        if (context instanceof BridgeContext) {
            mContext = ((BridgeContext) context);
        }
    }

    public Object getViewCookie() {
        return viewCookie;
    }

    public void setViewCookie(Object viewCookie) {
        // If the menu item has an associated action provider view,
        // directly set the cookie in the view to cookie map stored in BridgeContext.
        View actionView = getActionView();
        if (actionView != null && mContext != null) {
            mContext.addViewKey(actionView, viewCookie);
            // We don't need to add the view cookie to the this item now. But there's no harm in
            // storing it, in case we need it in the future.
        }
        this.viewCookie = viewCookie;
    }
}
