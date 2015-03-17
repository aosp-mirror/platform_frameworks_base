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

package android.view;

import android.content.Context;
import com.android.ide.common.rendering.api.LayoutLog;
import com.android.ide.common.rendering.api.ViewInfo;
import com.android.internal.view.menu.BridgeMenuItemImpl;
import com.android.internal.view.menu.MenuView;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.util.AttributeSet;

/**
 * Delegate used to provide new implementation of a select few methods of {@link MenuInflater}
 * <p/>
 * Through the layoutlib_create tool, the original  methods of MenuInflater have been
 * replaced by calls to methods of the same name in this delegate class.
 * <p/>
 * The main purpose of the class is to get the view key from the menu xml parser and add it to
 * the menu item. The view key is used by the IDE to match the individual view elements to the
 * corresponding xml tag in the menu/layout file.
 * <p/>
 * For Menus, the views may be reused and the {@link MenuItem} is a better object to hold the
 * view key than the {@link MenuView.ItemView}. At the time of computation of the rest of {@link
 * ViewInfo}, we check the corresponding view key in the menu item for the view and add it
 */
public class MenuInflater_Delegate {

    @LayoutlibDelegate
    /*package*/ static void registerMenu(MenuInflater thisInflater, MenuItem menuItem,
            AttributeSet attrs) {
        if (menuItem instanceof BridgeMenuItemImpl) {
            Context context = thisInflater.getContext();
            context = BridgeContext.getBaseContext(context);
            if (context instanceof BridgeContext) {
                Object viewKey = BridgeInflater.getViewKeyFromParser(
                        attrs, ((BridgeContext) context), null, false);
                ((BridgeMenuItemImpl) menuItem).setViewCookie(viewKey);
                return;
            }
        }
        // This means that Bridge did not take over the instantiation of some object properly.
        // This is most likely a bug in the LayoutLib code.
        Bridge.getLog().warning(LayoutLog.TAG_BROKEN,
                "Action Bar Menu rendering may be incorrect.", null);

    }

    @LayoutlibDelegate
    /*package*/ static void registerMenu(MenuInflater thisInflater, SubMenu subMenu,
            AttributeSet parser) {
        registerMenu(thisInflater, subMenu.getItem(), parser);
    }

}
