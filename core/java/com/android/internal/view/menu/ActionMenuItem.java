/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.ActionProvider;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.KeyEvent;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;

/**
 * @hide
 */
public class ActionMenuItem implements MenuItem {
    private final int mId;
    private final int mGroup;
    private final int mCategoryOrder;
    private final int mOrdering;

    private CharSequence mTitle;
    private CharSequence mTitleCondensed;
    private Intent mIntent;
    private char mShortcutNumericChar;
    private int mShortcutNumericModifiers = KeyEvent.META_CTRL_ON;
    private char mShortcutAlphabeticChar;
    private int mShortcutAlphabeticModifiers = KeyEvent.META_CTRL_ON;

    private Drawable mIconDrawable;
    private int mIconResId = NO_ICON;
    private ColorStateList mIconTintList = null;
    private PorterDuff.Mode mIconTintMode = null;
    private boolean mHasIconTint = false;
    private boolean mHasIconTintMode = false;

    private Context mContext;

    private MenuItem.OnMenuItemClickListener mClickListener;

    private CharSequence mContentDescription;
    private CharSequence mTooltipText;

    private static final int NO_ICON = 0;

    private int mFlags = ENABLED;
    private static final int CHECKABLE      = 0x00000001;
    private static final int CHECKED        = 0x00000002;
    private static final int EXCLUSIVE      = 0x00000004;
    private static final int HIDDEN         = 0x00000008;
    private static final int ENABLED        = 0x00000010;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public ActionMenuItem(Context context, int group, int id, int categoryOrder, int ordering,
            CharSequence title) {
        mContext = context;
        mId = id;
        mGroup = group;
        mCategoryOrder = categoryOrder;
        mOrdering = ordering;
        mTitle = title;
    }

    public char getAlphabeticShortcut() {
        return mShortcutAlphabeticChar;
    }

    public int getAlphabeticModifiers() {
        return mShortcutAlphabeticModifiers;
    }

    public int getGroupId() {
        return mGroup;
    }

    public Drawable getIcon() {
        return mIconDrawable;
    }

    public Intent getIntent() {
        return mIntent;
    }

    public int getItemId() {
        return mId;
    }

    public ContextMenuInfo getMenuInfo() {
        return null;
    }

    public char getNumericShortcut() {
        return mShortcutNumericChar;
    }

    public int getNumericModifiers() {
        return mShortcutNumericModifiers;
    }

    public int getOrder() {
        return mOrdering;
    }

    public SubMenu getSubMenu() {
        return null;
    }

    public CharSequence getTitle() {
        return mTitle;
    }

    public CharSequence getTitleCondensed() {
        return mTitleCondensed != null ? mTitleCondensed : mTitle;
    }

    public boolean hasSubMenu() {
        return false;
    }

    public boolean isCheckable() {
        return (mFlags & CHECKABLE) != 0; 
    }

    public boolean isChecked() {
        return (mFlags & CHECKED) != 0;
    }

    public boolean isEnabled() {
        return (mFlags & ENABLED) != 0;
    }

    public boolean isVisible() {
        return (mFlags & HIDDEN) == 0;
    }

    public MenuItem setAlphabeticShortcut(char alphaChar) {
        mShortcutAlphabeticChar = Character.toLowerCase(alphaChar);
        return this;
    }

    public MenuItem setAlphabeticShortcut(char alphachar, int alphaModifiers) {
        mShortcutAlphabeticChar = Character.toLowerCase(alphachar);
        mShortcutAlphabeticModifiers = KeyEvent.normalizeMetaState(alphaModifiers);
        return this;
    }

    public MenuItem setCheckable(boolean checkable) {
        mFlags = (mFlags & ~CHECKABLE) | (checkable ? CHECKABLE : 0);
        return this;
    }
    
    public ActionMenuItem setExclusiveCheckable(boolean exclusive) {
        mFlags = (mFlags & ~EXCLUSIVE) | (exclusive ? EXCLUSIVE : 0);
        return this;
    }

    public MenuItem setChecked(boolean checked) {
        mFlags = (mFlags & ~CHECKED) | (checked ? CHECKED : 0);
        return this;
    }

    public MenuItem setEnabled(boolean enabled) {
        mFlags = (mFlags & ~ENABLED) | (enabled ? ENABLED : 0);
        return this;
    }

    public MenuItem setIcon(Drawable icon) {
        mIconDrawable = icon;
        mIconResId = NO_ICON;

        applyIconTint();
        return this;
    }

    public MenuItem setIcon(int iconRes) {
        mIconResId = iconRes;
        mIconDrawable = mContext.getDrawable(iconRes);

        applyIconTint();
        return this;
    }

    @Override
    public MenuItem setIconTintList(@Nullable ColorStateList iconTintList) {
        mIconTintList = iconTintList;
        mHasIconTint = true;

        applyIconTint();

        return this;
    }

    @Nullable
    @Override
    public ColorStateList getIconTintList() {
        return mIconTintList;
    }

    @Override
    public MenuItem setIconTintMode(PorterDuff.Mode iconTintMode) {
        mIconTintMode = iconTintMode;
        mHasIconTintMode = true;

        applyIconTint();

        return this;
    }

    @Nullable
    @Override
    public PorterDuff.Mode getIconTintMode() {
        return mIconTintMode;
    }

    private void applyIconTint() {
        if (mIconDrawable != null && (mHasIconTint || mHasIconTintMode)) {
            mIconDrawable = mIconDrawable.mutate();

            if (mHasIconTint) {
                mIconDrawable.setTintList(mIconTintList);
            }

            if (mHasIconTintMode) {
                mIconDrawable.setTintMode(mIconTintMode);
            }
        }
    }

    public MenuItem setIntent(Intent intent) {
        mIntent = intent;
        return this;
    }

    public MenuItem setNumericShortcut(char numericChar) {
        mShortcutNumericChar = numericChar;
        return this;
    }

    public MenuItem setNumericShortcut(char numericChar, int numericModifiers) {
        mShortcutNumericChar = numericChar;
        mShortcutNumericModifiers = KeyEvent.normalizeMetaState(numericModifiers);
        return this;
    }

    public MenuItem setOnMenuItemClickListener(OnMenuItemClickListener menuItemClickListener) {
        mClickListener = menuItemClickListener;
        return this;
    }

    public MenuItem setShortcut(char numericChar, char alphaChar) {
        mShortcutNumericChar = numericChar;
        mShortcutAlphabeticChar = Character.toLowerCase(alphaChar);
        return this;
    }

    public MenuItem setShortcut(char numericChar, char alphaChar, int numericModifiers,
            int alphaModifiers) {
        mShortcutNumericChar = numericChar;
        mShortcutNumericModifiers = KeyEvent.normalizeMetaState(numericModifiers);
        mShortcutAlphabeticChar = Character.toLowerCase(alphaChar);
        mShortcutAlphabeticModifiers = KeyEvent.normalizeMetaState(alphaModifiers);
        return this;
    }

    public MenuItem setTitle(CharSequence title) {
        mTitle = title;
        return this;
    }

    public MenuItem setTitle(int title) {
        mTitle = mContext.getResources().getString(title);
        return this;
    }

    public MenuItem setTitleCondensed(CharSequence title) {
        mTitleCondensed = title;
        return this;
    }

    public MenuItem setVisible(boolean visible) {
        mFlags = (mFlags & HIDDEN) | (visible ? 0 : HIDDEN);
        return this;
    }

    public boolean invoke() {
        if (mClickListener != null && mClickListener.onMenuItemClick(this)) {
            return true;
        }
        
        if (mIntent != null) {
            mContext.startActivity(mIntent);
            return true;
        }
        
        return false;
    }
    
    public void setShowAsAction(int show) {
        // Do nothing. ActionMenuItems always show as action buttons.
    }

    public MenuItem setActionView(View actionView) {
        throw new UnsupportedOperationException();
    }

    public View getActionView() {
        return null;
    }

    @Override
    public MenuItem setActionView(int resId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ActionProvider getActionProvider() {
        return null;
    }

    @Override
    public MenuItem setActionProvider(ActionProvider actionProvider) {
        throw new UnsupportedOperationException();
    }

    @Override
    public MenuItem setShowAsActionFlags(int actionEnum) {
        setShowAsAction(actionEnum);
        return this;
    }

    @Override
    public boolean expandActionView() {
        return false;
    }

    @Override
    public boolean collapseActionView() {
        return false;
    }

    @Override
    public boolean isActionViewExpanded() {
        return false;
    }

    @Override
    public MenuItem setOnActionExpandListener(OnActionExpandListener listener) {
        // No need to save the listener; ActionMenuItem does not support collapsing items.
        return this;
    }

    @Override
    public MenuItem setContentDescription(CharSequence contentDescription) {
        mContentDescription = contentDescription;
        return this;
    }

    @Override
    public CharSequence getContentDescription() {
        return mContentDescription;
    }

    @Override
    public MenuItem setTooltipText(CharSequence tooltipText) {
        mTooltipText = tooltipText;
        return this;
    }

    @Override
    public CharSequence getTooltipText() {
        return mTooltipText;
    }
}
