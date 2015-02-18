/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.annotation.DrawableRes;
import android.annotation.StringRes;
import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.widget.AdapterView;

/**
 * Extension of {@link Menu} for context menus providing functionality to modify
 * the header of the context menu.
 * <p>
 * Context menus do not support item shortcuts and item icons.
 * <p>
 * To show a context menu on long click, most clients will want to call
 * {@link Activity#registerForContextMenu} and override
 * {@link Activity#onCreateContextMenu}.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For information about creating menus, read the
 * <a href="{@docRoot}guide/topics/ui/menus.html">Menus</a> developer guide.</p>
 * </div>
 */
public interface ContextMenu extends Menu {
    /**
     * Sets the context menu header's title to the title given in <var>titleRes</var>
     * resource identifier.
     * 
     * @param titleRes The string resource identifier used for the title.
     * @return This ContextMenu so additional setters can be called.
     */
    public ContextMenu setHeaderTitle(@StringRes int titleRes);

    /**
     * Sets the context menu header's title to the title given in <var>title</var>.
     * 
     * @param title The character sequence used for the title.
     * @return This ContextMenu so additional setters can be called.
     */
    public ContextMenu setHeaderTitle(CharSequence title);
    
    /**
     * Sets the context menu header's icon to the icon given in <var>iconRes</var>
     * resource id.
     * 
     * @param iconRes The resource identifier used for the icon.
     * @return This ContextMenu so additional setters can be called.
     */
    public ContextMenu setHeaderIcon(@DrawableRes int iconRes);

    /**
     * Sets the context menu header's icon to the icon given in <var>icon</var>
     * {@link Drawable}.
     * 
     * @param icon The {@link Drawable} used for the icon.
     * @return This ContextMenu so additional setters can be called.
     */
    public ContextMenu setHeaderIcon(Drawable icon);
    
    /**
     * Sets the header of the context menu to the {@link View} given in
     * <var>view</var>. This replaces the header title and icon (and those
     * replace this).
     * 
     * @param view The {@link View} used for the header.
     * @return This ContextMenu so additional setters can be called.
     */
    public ContextMenu setHeaderView(View view);
    
    /**
     * Clears the header of the context menu.
     */
    public void clearHeader();
    
    /**
     * Additional information regarding the creation of the context menu.  For example,
     * {@link AdapterView}s use this to pass the exact item position within the adapter
     * that initiated the context menu.
     */
    public interface ContextMenuInfo {
    }
}
