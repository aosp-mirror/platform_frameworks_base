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

import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

/**
 * Delegate used to provide new implementation of a select few methods of {@link MenuBuilder}
 * <p/>
 * Through the layoutlib_create tool, the original  methods of {@code MenuBuilder} have been
 * replaced by calls to methods of the same name in this delegate class.
 */
public class MenuBuilder_Delegate {
    /**
     * The method overrides the instantiation of the {@link MenuItemImpl} with an instance of
     * {@link BridgeMenuItemImpl} so that view cookies may be stored.
     */
    @LayoutlibDelegate
    /*package*/ static MenuItemImpl createNewMenuItem(MenuBuilder thisMenu, int group, int id,
            int categoryOrder, int ordering, CharSequence title, int defaultShowAsAction) {
        return new BridgeMenuItemImpl(thisMenu, group, id, categoryOrder, ordering, title,
                defaultShowAsAction);
    }
}
