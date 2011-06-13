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

import com.android.internal.view.menu.MenuBuilder.ItemInvoker;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewDebug;
import android.widget.TextView;
import android.text.Layout;

/**
 * The item view for each item in the {@link IconMenuView}.  
 */
public final class IconMenuItemView extends TextView implements MenuView.ItemView {
    
    private static final int NO_ALPHA = 0xFF;
    
    private IconMenuView mIconMenuView;
    
    private ItemInvoker mItemInvoker;
    private MenuItemImpl mItemData; 
    
    private Drawable mIcon;
    
    private int mTextAppearance;
    private Context mTextAppearanceContext;
    
    private float mDisabledAlpha;

    private Rect mPositionIconAvailable = new Rect();
    private Rect mPositionIconOutput = new Rect();
    
    private boolean mShortcutCaptionMode;
    private String mShortcutCaption;
    
    private static String sPrependShortcutLabel;

    public IconMenuItemView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs);

        if (sPrependShortcutLabel == null) {
            /*
             * Views should only be constructed from the UI thread, so no
             * synchronization needed
             */
            sPrependShortcutLabel = getResources().getString(
                    com.android.internal.R.string.prepend_shortcut_label);
        }
        
        TypedArray a =
            context.obtainStyledAttributes(
                attrs, com.android.internal.R.styleable.MenuView, defStyle, 0);

        mDisabledAlpha = a.getFloat(
                com.android.internal.R.styleable.MenuView_itemIconDisabledAlpha, 0.8f);
        mTextAppearance = a.getResourceId(com.android.internal.R.styleable.
                                          MenuView_itemTextAppearance, -1);
        mTextAppearanceContext = context;
        
        a.recycle();
    }
    
    public IconMenuItemView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /**
     * Initializes with the provided title and icon
     * @param title The title of this item
     * @param icon The icon of this item
     */
    void initialize(CharSequence title, Drawable icon) {
        setClickable(true);
        setFocusable(true);

        if (mTextAppearance != -1) {
            setTextAppearance(mTextAppearanceContext, mTextAppearance);
        }

        setTitle(title);
        setIcon(icon);
    }
    
    public void initialize(MenuItemImpl itemData, int menuType) {
        mItemData = itemData;

        initialize(itemData.getTitleForItemView(this), itemData.getIcon());
        
        setVisibility(itemData.isVisible() ? View.VISIBLE : View.GONE);
        setEnabled(itemData.isEnabled());
    }

    public void setItemData(MenuItemImpl data) {
        mItemData = data;
    }

    @Override
    public boolean performClick() {
        // Let the view's click listener have top priority (the More button relies on this)
        if (super.performClick()) {
            return true;
        }
        
        if ((mItemInvoker != null) && (mItemInvoker.invokeItem(mItemData))) {
            playSoundEffect(SoundEffectConstants.CLICK);
            return true;
        } else {
            return false;
        }
    }
    
    public void setTitle(CharSequence title) {
        
        if (mShortcutCaptionMode) {
            /*
             * Don't set the title directly since it will replace the
             * shortcut+title being shown. Instead, re-set the shortcut caption
             * mode so the new title is shown.
             */
            setCaptionMode(true);
            
        } else if (title != null) {
            setText(title);
        }
    }
    
    void setCaptionMode(boolean shortcut) {
        /*
         * If there is no item model, don't do any of the below (for example,
         * the 'More' item doesn't have a model)
         */
        if (mItemData == null) {
            return;
        }
        
        mShortcutCaptionMode = shortcut && (mItemData.shouldShowShortcut());
        
        CharSequence text = mItemData.getTitleForItemView(this);
        
        if (mShortcutCaptionMode) {
            
            if (mShortcutCaption == null) {
                mShortcutCaption = mItemData.getShortcutLabel();
            }

            text = mShortcutCaption;
        }
        
        setText(text);
    }
    
    public void setIcon(Drawable icon) {
        mIcon = icon;
        
        if (icon != null) {
            
            /* Set the bounds of the icon since setCompoundDrawables needs it. */
            icon.setBounds(0, 0, icon.getIntrinsicWidth(), icon.getIntrinsicHeight());
            
            // Set the compound drawables
            setCompoundDrawables(null, icon, null, null);
            
            // When there is an icon, make sure the text is at the bottom
            setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);

            /*
             * Request a layout to reposition the icon. The positioning of icon
             * depends on this TextView's line bounds, which is only available
             * after a layout.
             */  
            requestLayout();
        } else {
            setCompoundDrawables(null, null, null, null);
            
            // When there is no icon, make sure the text is centered vertically
            setGravity(Gravity.CENTER_VERTICAL | Gravity.CENTER_HORIZONTAL);
        }
    }

    public void setItemInvoker(ItemInvoker itemInvoker) {
        mItemInvoker = itemInvoker;
    }
    
    @ViewDebug.CapturedViewProperty(retrieveReturn = true)
    public MenuItemImpl getItemData() {
        return mItemData;
    }

    @Override
    public void setVisibility(int v) {
        super.setVisibility(v);
        
        if (mIconMenuView != null) {
            // On visibility change, mark the IconMenuView to refresh itself eventually
            mIconMenuView.markStaleChildren();
        }
    }
    
    void setIconMenuView(IconMenuView iconMenuView) {
        mIconMenuView = iconMenuView;
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();

        if (mItemData != null && mIcon != null) {
            // When disabled, the not-focused state and the pressed state should
            // drop alpha on the icon
            final boolean isInAlphaState = !mItemData.isEnabled() && (isPressed() || !isFocused());
            mIcon.setAlpha(isInAlphaState ? (int) (mDisabledAlpha * NO_ALPHA) : NO_ALPHA);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        
        positionIcon();
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int before, int after) {
        super.onTextChanged(text, start, before, after);

        // our layout params depend on the length of the text
        setLayoutParams(getTextAppropriateLayoutParams());
    }

    /**
     * @return layout params appropriate for this view.  If layout params already exist, it will
     *         augment them to be appropriate to the current text size.
     */
    IconMenuView.LayoutParams getTextAppropriateLayoutParams() {
        IconMenuView.LayoutParams lp = (IconMenuView.LayoutParams) getLayoutParams();
        if (lp == null) {
            // Default layout parameters
            lp = new IconMenuView.LayoutParams(
                    IconMenuView.LayoutParams.MATCH_PARENT, IconMenuView.LayoutParams.MATCH_PARENT);
        }

        // Set the desired width of item
        lp.desiredWidth = (int) Layout.getDesiredWidth(getText(), getPaint());

        return lp;
    }

    /**
     * Positions the icon vertically (horizontal centering is taken care of by
     * the TextView's gravity).
     */
    private void positionIcon() {
        
        if (mIcon == null) {
            return;
        }
        
        // We reuse the output rectangle as a temp rect
        Rect tmpRect = mPositionIconOutput;
        getLineBounds(0, tmpRect);
        mPositionIconAvailable.set(0, 0, getWidth(), tmpRect.top);
        final int layoutDirection = getResolvedLayoutDirection();
        Gravity.apply(Gravity.CENTER_VERTICAL | Gravity.LEFT, mIcon.getIntrinsicWidth(), mIcon
                .getIntrinsicHeight(), mPositionIconAvailable, mPositionIconOutput,
                layoutDirection);
        mIcon.setBounds(mPositionIconOutput);
    }

    public void setCheckable(boolean checkable) {
    }

    public void setChecked(boolean checked) {
    }

    public void setShortcut(boolean showShortcut, char shortcutKey) {
        
        if (mShortcutCaptionMode) {
            /*
             * Shortcut has changed and we're showing it right now, need to
             * update (clear the old one first).
             */
            mShortcutCaption = null;
            setCaptionMode(true);
        }
    }

    public boolean prefersCondensedTitle() {
        return true;
    }

    public boolean showsIcon() {
        return true;
    }

}
