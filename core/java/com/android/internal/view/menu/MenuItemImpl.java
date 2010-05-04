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

import java.lang.ref.WeakReference;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.view.ContextMenu.ContextMenuInfo;

import com.android.internal.view.menu.MenuView.ItemView;

/**
 * @hide
 */
public final class MenuItemImpl implements MenuItem {
    private static final String TAG = "MenuItemImpl";
    
    private final int mId;
    private final int mGroup;
    private final int mCategoryOrder;
    private final int mOrdering;
    private CharSequence mTitle;
    private CharSequence mTitleCondensed;
    private Intent mIntent;
    private char mShortcutNumericChar;
    private char mShortcutAlphabeticChar;

    /** The icon's drawable which is only created as needed */
    private Drawable mIconDrawable;
    /**
     * The icon's resource ID which is used to get the Drawable when it is
     * needed (if the Drawable isn't already obtained--only one of the two is
     * needed).
     */ 
    private int mIconResId = NO_ICON;

    /** The (cached) menu item views for this item */  
    private WeakReference<ItemView> mItemViews[];
    
    /** The menu to which this item belongs */
    private MenuBuilder mMenu;
    /** If this item should launch a sub menu, this is the sub menu to launch */
    private SubMenuBuilder mSubMenu;
    
    private Runnable mItemCallback;
    private MenuItem.OnMenuItemClickListener mClickListener;

    private int mFlags = ENABLED;
    private static final int CHECKABLE      = 0x00000001;
    private static final int CHECKED        = 0x00000002;
    private static final int EXCLUSIVE      = 0x00000004;
    private static final int HIDDEN         = 0x00000008;
    private static final int ENABLED        = 0x00000010;

    /** Used for the icon resource ID if this item does not have an icon */
    static final int NO_ICON = 0;

    /**
     * Current use case is for context menu: Extra information linked to the
     * View that added this item to the context menu.
     */ 
    private ContextMenuInfo mMenuInfo;
    
    private static String sPrependShortcutLabel;
    private static String sEnterShortcutLabel;
    private static String sDeleteShortcutLabel;
    private static String sSpaceShortcutLabel;
    
    
    /**
     * Instantiates this menu item. The constructor
     * {@link #MenuItemData(MenuBuilder, int, int, int, CharSequence, int)} is
     * preferred due to lazy loading of the icon Drawable.
     * 
     * @param menu
     * @param group Item ordering grouping control. The item will be added after
     *            all other items whose order is <= this number, and before any
     *            that are larger than it. This can also be used to define
     *            groups of items for batch state changes. Normally use 0.
     * @param id Unique item ID. Use 0 if you do not need a unique ID.
     * @param categoryOrder The ordering for this item.
     * @param title The text to display for the item.
     */
    MenuItemImpl(MenuBuilder menu, int group, int id, int categoryOrder, int ordering,
            CharSequence title) {

        if (sPrependShortcutLabel == null) {
            // This is instantiated from the UI thread, so no chance of sync issues 
            sPrependShortcutLabel = menu.getContext().getResources().getString(
                    com.android.internal.R.string.prepend_shortcut_label);
            sEnterShortcutLabel = menu.getContext().getResources().getString(
                    com.android.internal.R.string.menu_enter_shortcut_label);
            sDeleteShortcutLabel = menu.getContext().getResources().getString(
                    com.android.internal.R.string.menu_delete_shortcut_label);
            sSpaceShortcutLabel = menu.getContext().getResources().getString(
                    com.android.internal.R.string.menu_space_shortcut_label);
        }
        
        mItemViews = new WeakReference[MenuBuilder.NUM_TYPES];
        mMenu = menu;
        mId = id;
        mGroup = group;
        mCategoryOrder = categoryOrder;
        mOrdering = ordering;
        mTitle = title;
    }
    
    /**
     * Invokes the item by calling various listeners or callbacks.
     * 
     * @return true if the invocation was handled, false otherwise
     */
    public boolean invoke() {
        if (mClickListener != null &&
            mClickListener.onMenuItemClick(this)) {
            return true;
        }

        MenuBuilder.Callback callback = mMenu.getCallback(); 
        if (callback != null &&
            callback.onMenuItemSelected(mMenu.getRootMenu(), this)) {
            return true;
        }

        if (mItemCallback != null) {
            mItemCallback.run();
            return true;
        }
        
        if (mIntent != null) {
            try {
                mMenu.getContext().startActivity(mIntent);
                return true;
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "Can't find activity to handle intent; ignoring", e);
            }
        }
        
        return false;
    }
    
    private boolean hasItemView(int menuType) {
        return mItemViews[menuType] != null && mItemViews[menuType].get() != null;
    }
    
    public boolean isEnabled() {
        return (mFlags & ENABLED) != 0;
    }

    public MenuItem setEnabled(boolean enabled) {
        if (enabled) {
            mFlags |= ENABLED;
        } else {
            mFlags &= ~ENABLED;
        }

        for (int i = MenuBuilder.NUM_TYPES - 1; i >= 0; i--) {
            // If the item view prefers a condensed title, only set this title if there
            // is no condensed title for this item
            if (hasItemView(i)) {
                mItemViews[i].get().setEnabled(enabled);
            }
        }
        
        return this;
    }
    
    public int getGroupId() {
        return mGroup;
    }

    @ViewDebug.CapturedViewProperty
    public int getItemId() {
        return mId;
    }

    public int getOrder() {
        return mCategoryOrder;
    }
    
    public int getOrdering() {
        return mOrdering; 
    }
    
    public Intent getIntent() {
        return mIntent;
    }

    public MenuItem setIntent(Intent intent) {
        mIntent = intent;
        return this;
    }

    Runnable getCallback() {
        return mItemCallback;
    }
    
    public MenuItem setCallback(Runnable callback) {
        mItemCallback = callback;
        return this;
    }
    
    public char getAlphabeticShortcut() {
        return mShortcutAlphabeticChar;
    }

    public MenuItem setAlphabeticShortcut(char alphaChar) {
        if (mShortcutAlphabeticChar == alphaChar) return this;
        
        mShortcutAlphabeticChar = Character.toLowerCase(alphaChar);
        
        refreshShortcutOnItemViews();
        
        return this;
    }

    public char getNumericShortcut() {
        return mShortcutNumericChar;
    }

    public MenuItem setNumericShortcut(char numericChar) {
        if (mShortcutNumericChar == numericChar) return this;
        
        mShortcutNumericChar = numericChar;
        
        refreshShortcutOnItemViews();
        
        return this;
    }

    public MenuItem setShortcut(char numericChar, char alphaChar) {
        mShortcutNumericChar = numericChar;
        mShortcutAlphabeticChar = Character.toLowerCase(alphaChar);
        
        refreshShortcutOnItemViews();
        
        return this;
    }

    /**
     * @return The active shortcut (based on QWERTY-mode of the menu).
     */
    char getShortcut() {
        return (mMenu.isQwertyMode() ? mShortcutAlphabeticChar : mShortcutNumericChar);
    }
    
    /**
     * @return The label to show for the shortcut. This includes the chording
     *         key (for example 'Menu+a'). Also, any non-human readable
     *         characters should be human readable (for example 'Menu+enter').
     */
    String getShortcutLabel() {

        char shortcut = getShortcut();
        if (shortcut == 0) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder(sPrependShortcutLabel);
        switch (shortcut) {
        
            case '\n':
                sb.append(sEnterShortcutLabel);
                break;
            
            case '\b':
                sb.append(sDeleteShortcutLabel);
                break;
            
            case ' ':
                sb.append(sSpaceShortcutLabel);
                break;
            
            default:
                sb.append(shortcut);
                break;
        }
        
        return sb.toString();
    }
    
    /**
     * @return Whether this menu item should be showing shortcuts (depends on
     *         whether the menu should show shortcuts and whether this item has
     *         a shortcut defined)
     */
    boolean shouldShowShortcut() {
        // Show shortcuts if the menu is supposed to show shortcuts AND this item has a shortcut
        return mMenu.isShortcutsVisible() && (getShortcut() != 0);
    }
    
    /**
     * Refreshes the shortcut shown on the ItemViews.  This method retrieves current
     * shortcut state (mode and shown) from the menu that contains this item.
     */
    private void refreshShortcutOnItemViews() {
        refreshShortcutOnItemViews(mMenu.isShortcutsVisible(), mMenu.isQwertyMode());
    }

    /**
     * Refreshes the shortcut shown on the ItemViews. This is usually called by
     * the {@link MenuBuilder} when it is refreshing the shortcuts on all item
     * views, so it passes arguments rather than each item calling a method on the menu to get
     * the same values.
     * 
     * @param menuShortcutShown The menu's shortcut shown mode. In addition,
     *            this method will ensure this item has a shortcut before it
     *            displays the shortcut.
     * @param isQwertyMode Whether the shortcut mode is qwerty mode
     */
    void refreshShortcutOnItemViews(boolean menuShortcutShown, boolean isQwertyMode) {
        final char shortcutKey = (isQwertyMode) ? mShortcutAlphabeticChar : mShortcutNumericChar;

        // Show shortcuts if the menu is supposed to show shortcuts AND this item has a shortcut
        final boolean showShortcut = menuShortcutShown && (shortcutKey != 0);
        
        for (int i = MenuBuilder.NUM_TYPES - 1; i >= 0; i--) {
            if (hasItemView(i)) {
                mItemViews[i].get().setShortcut(showShortcut, shortcutKey);
            }
        }
    }
    
    public SubMenu getSubMenu() {
        return mSubMenu;
    }

    public boolean hasSubMenu() {
        return mSubMenu != null;
    }

    void setSubMenu(SubMenuBuilder subMenu) {
        if ((mMenu != null) && (mMenu instanceof SubMenu)) {
            throw new UnsupportedOperationException(
            "Attempt to add a sub-menu to a sub-menu.");
        }
        
        mSubMenu = subMenu;
        
        subMenu.setHeaderTitle(getTitle());
    }
    
    @ViewDebug.CapturedViewProperty
    public CharSequence getTitle() {
        return mTitle;
    }

    /**
     * Gets the title for a particular {@link ItemView}
     * 
     * @param itemView The ItemView that is receiving the title
     * @return Either the title or condensed title based on what the ItemView
     *         prefers
     */
    CharSequence getTitleForItemView(MenuView.ItemView itemView) {
        return ((itemView != null) && itemView.prefersCondensedTitle())
                ? getTitleCondensed()
                : getTitle();
    }

    public MenuItem setTitle(CharSequence title) {
        mTitle = title;

        for (int i = MenuBuilder.NUM_TYPES - 1; i >= 0; i--) {
            // If the item view prefers a condensed title, only set this title if there
            // is no condensed title for this item
            if (!hasItemView(i)) {
                continue;
            }
            
            ItemView itemView = mItemViews[i].get(); 
            if (!itemView.prefersCondensedTitle() || mTitleCondensed == null) {
                itemView.setTitle(title);
            }
        }
        
        if (mSubMenu != null) {
            mSubMenu.setHeaderTitle(title);
        }
        
        return this;
    }
    
    public MenuItem setTitle(int title) {
        return setTitle(mMenu.getContext().getString(title));
    }
    
    public CharSequence getTitleCondensed() {
        return mTitleCondensed != null ? mTitleCondensed : mTitle;
    }
    
    public MenuItem setTitleCondensed(CharSequence title) {
        mTitleCondensed = title;

        // Could use getTitle() in the loop below, but just cache what it would do here 
        if (title == null) {
            title = mTitle;
        }
        
        for (int i = MenuBuilder.NUM_TYPES - 1; i >= 0; i--) {
            // Refresh those item views that prefer a condensed title
            if (hasItemView(i) && (mItemViews[i].get().prefersCondensedTitle())) {
                mItemViews[i].get().setTitle(title);
            }
        }
        
        return this;
    }

    public Drawable getIcon() {
        
        if (mIconDrawable != null) {
            return mIconDrawable;
        }

        if (mIconResId != NO_ICON) {
            return mMenu.getResources().getDrawable(mIconResId);
        }
        
        return null;
    }
    
    public MenuItem setIcon(Drawable icon) {
        mIconResId = NO_ICON;
        mIconDrawable = icon;
        setIconOnViews(icon);
        
        return this;
    }
    
    public MenuItem setIcon(int iconResId) {
        mIconDrawable = null;
        mIconResId = iconResId;

        // If we have a view, we need to push the Drawable to them
        if (haveAnyOpenedIconCapableItemViews()) {
            Drawable drawable = iconResId != NO_ICON ? mMenu.getResources().getDrawable(iconResId)
                    : null;
            setIconOnViews(drawable);
        }
        
        return this;
    }

    private void setIconOnViews(Drawable icon) {
        for (int i = MenuBuilder.NUM_TYPES - 1; i >= 0; i--) {
            // Refresh those item views that are able to display an icon
            if (hasItemView(i) && mItemViews[i].get().showsIcon()) {
                mItemViews[i].get().setIcon(icon);
            }
        }
    }
    
    private boolean haveAnyOpenedIconCapableItemViews() {
        for (int i = MenuBuilder.NUM_TYPES - 1; i >= 0; i--) {
            if (hasItemView(i) && mItemViews[i].get().showsIcon()) {
                return true;
            }
        }
        
        return false;
    }
    
    public boolean isCheckable() {
        return (mFlags & CHECKABLE) == CHECKABLE;
    }

    public MenuItem setCheckable(boolean checkable) {
        final int oldFlags = mFlags;
        mFlags = (mFlags & ~CHECKABLE) | (checkable ? CHECKABLE : 0);
        if (oldFlags != mFlags) {
            for (int i = MenuBuilder.NUM_TYPES - 1; i >= 0; i--) {
                if (hasItemView(i)) {
                    mItemViews[i].get().setCheckable(checkable);
                }
            }
        }
        
        return this;
    }

    public void setExclusiveCheckable(boolean exclusive)
    {
        mFlags = (mFlags&~EXCLUSIVE) | (exclusive ? EXCLUSIVE : 0);
    }

    public boolean isExclusiveCheckable() {
        return (mFlags & EXCLUSIVE) != 0;
    }
    
    public boolean isChecked() {
        return (mFlags & CHECKED) == CHECKED;
    }

    public MenuItem setChecked(boolean checked) {
        if ((mFlags & EXCLUSIVE) != 0) {
            // Call the method on the Menu since it knows about the others in this
            // exclusive checkable group
            mMenu.setExclusiveItemChecked(this);
        } else {
            setCheckedInt(checked);
        }
        
        return this;
    }

    void setCheckedInt(boolean checked) {
        final int oldFlags = mFlags;
        mFlags = (mFlags & ~CHECKED) | (checked ? CHECKED : 0);
        if (oldFlags != mFlags) {
            for (int i = MenuBuilder.NUM_TYPES - 1; i >= 0; i--) {
                if (hasItemView(i)) {
                    mItemViews[i].get().setChecked(checked);
                }
            }
        }
    }
    
    public boolean isVisible() {
        return (mFlags & HIDDEN) == 0;
    }

    /**
     * Changes the visibility of the item. This method DOES NOT notify the
     * parent menu of a change in this item, so this should only be called from
     * methods that will eventually trigger this change.  If unsure, use {@link #setVisible(boolean)}
     * instead.
     * 
     * @param shown Whether to show (true) or hide (false).
     * @return Whether the item's shown state was changed
     */
    boolean setVisibleInt(boolean shown) {
        final int oldFlags = mFlags;
        mFlags = (mFlags & ~HIDDEN) | (shown ? 0 : HIDDEN);
        return oldFlags != mFlags;
    }
    
    public MenuItem setVisible(boolean shown) {
        // Try to set the shown state to the given state. If the shown state was changed
        // (i.e. the previous state isn't the same as given state), notify the parent menu that
        // the shown state has changed for this item
        if (setVisibleInt(shown)) mMenu.onItemVisibleChanged(this);
        
        return this;
    }

   public MenuItem setOnMenuItemClickListener(MenuItem.OnMenuItemClickListener clickListener) {
        mClickListener = clickListener;
        return this;
    }

    View getItemView(int menuType, ViewGroup parent) {
        if (!hasItemView(menuType)) {
            mItemViews[menuType] = new WeakReference<ItemView>(createItemView(menuType, parent));
        }
        
        return (View) mItemViews[menuType].get();
    }

    /**
     * Create and initializes a menu item view that implements {@link MenuView.ItemView}.
     * @param menuType The type of menu to get a View for (must be one of
     *            {@link MenuBuilder#TYPE_ICON}, {@link MenuBuilder#TYPE_EXPANDED},
     *            {@link MenuBuilder#TYPE_SUB}, {@link MenuBuilder#TYPE_CONTEXT}).
     * @return The inflated {@link MenuView.ItemView} that is ready for use
     */
    private MenuView.ItemView createItemView(int menuType, ViewGroup parent) {
        // Create the MenuView
        MenuView.ItemView itemView = (MenuView.ItemView) getLayoutInflater(menuType)
                .inflate(MenuBuilder.ITEM_LAYOUT_RES_FOR_TYPE[menuType], parent, false);
        itemView.initialize(this, menuType);
        return itemView;
    }
    
    void clearItemViews() {
        for (int i = mItemViews.length - 1; i >= 0; i--) {
            mItemViews[i] = null;
        }
    }
    
    @Override
    public String toString() {
        return mTitle.toString();
    }

    void setMenuInfo(ContextMenuInfo menuInfo) {
        mMenuInfo = menuInfo;
    }
    
    public ContextMenuInfo getMenuInfo() {
        return mMenuInfo;
    }
    
    /**
     * Returns a LayoutInflater that is themed for the given menu type.
     * 
     * @param menuType The type of menu.
     * @return A LayoutInflater.
     */
    public LayoutInflater getLayoutInflater(int menuType) {
        return mMenu.getMenuType(menuType).getInflater();
    }

    /**
     * @return Whether the given menu type should show icons for menu items.
     */
    public boolean shouldShowIcon(int menuType) {
        return menuType == MenuBuilder.TYPE_ICON || mMenu.getOptionalIconsVisible();
    }
}
