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

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ActionMenuView;
import android.widget.ForwardingListener;
import android.widget.TextView;
import android.widget.Toast;

/**
 * @hide
 */
public class ActionMenuItemView extends TextView
        implements MenuView.ItemView, View.OnClickListener, View.OnLongClickListener,
        ActionMenuView.ActionMenuChildView {
    private static final String TAG = "ActionMenuItemView";

    private MenuItemImpl mItemData;
    private CharSequence mTitle;
    private Drawable mIcon;
    private MenuBuilder.ItemInvoker mItemInvoker;
    private ForwardingListener mForwardingListener;
    private PopupCallback mPopupCallback;

    private boolean mAllowTextWithIcon;
    private boolean mExpandedFormat;
    private int mMinWidth;
    private int mSavedPaddingLeft;

    private static final int MAX_ICON_SIZE = 32; // dp
    private int mMaxIconSize;

    public ActionMenuItemView(Context context) {
        this(context, null);
    }

    public ActionMenuItemView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ActionMenuItemView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public ActionMenuItemView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        final Resources res = context.getResources();
        mAllowTextWithIcon = shouldAllowTextWithIcon();
        final TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.ActionMenuItemView, defStyleAttr, defStyleRes);
        mMinWidth = a.getDimensionPixelSize(
                com.android.internal.R.styleable.ActionMenuItemView_minWidth, 0);
        a.recycle();

        final float density = res.getDisplayMetrics().density;
        mMaxIconSize = (int) (MAX_ICON_SIZE * density + 0.5f);

        setOnClickListener(this);
        setOnLongClickListener(this);

        mSavedPaddingLeft = -1;
        setSaveEnabled(false);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        mAllowTextWithIcon = shouldAllowTextWithIcon();
        updateTextButtonVisibility();
    }

    /**
     * Whether action menu items should obey the "withText" showAsAction flag. This may be set to
     * false for situations where space is extremely limited. -->
     */
    private boolean shouldAllowTextWithIcon() {
        final Configuration configuration = getContext().getResources().getConfiguration();
        final int width = configuration.screenWidthDp;
        final int height = configuration.screenHeightDp;
        return  width >= 480 || (width >= 640 && height >= 480)
                || configuration.orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    @Override
    public void setPadding(int l, int t, int r, int b) {
        mSavedPaddingLeft = l;
        super.setPadding(l, t, r, b);
    }

    public MenuItemImpl getItemData() {
        return mItemData;
    }

    @Override
    public void initialize(MenuItemImpl itemData, int menuType) {
        mItemData = itemData;

        setIcon(itemData.getIcon());
        setTitle(itemData.getTitleForItemView(this)); // Title only takes effect if there is no icon
        setId(itemData.getItemId());

        setVisibility(itemData.isVisible() ? View.VISIBLE : View.GONE);
        setEnabled(itemData.isEnabled());

        if (itemData.hasSubMenu()) {
            if (mForwardingListener == null) {
                mForwardingListener = new ActionMenuItemForwardingListener();
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (mItemData.hasSubMenu() && mForwardingListener != null
                && mForwardingListener.onTouch(this, e)) {
            return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public void onClick(View v) {
        if (mItemInvoker != null) {
            mItemInvoker.invokeItem(mItemData);
        }
    }

    public void setItemInvoker(MenuBuilder.ItemInvoker invoker) {
        mItemInvoker = invoker;
    }

    public void setPopupCallback(PopupCallback popupCallback) {
        mPopupCallback = popupCallback;
    }

    public boolean prefersCondensedTitle() {
        return true;
    }

    public void setCheckable(boolean checkable) {
        // TODO Support checkable action items
    }

    public void setChecked(boolean checked) {
        // TODO Support checkable action items
    }

    public void setExpandedFormat(boolean expandedFormat) {
        if (mExpandedFormat != expandedFormat) {
            mExpandedFormat = expandedFormat;
            if (mItemData != null) {
                mItemData.actionFormatChanged();
            }
        }
    }

    private void updateTextButtonVisibility() {
        boolean visible = !TextUtils.isEmpty(mTitle);
        visible &= mIcon == null ||
                (mItemData.showsTextAsAction() && (mAllowTextWithIcon || mExpandedFormat));

        setText(visible ? mTitle : null);
    }

    public void setIcon(Drawable icon) {
        mIcon = icon;
        if (icon != null) {
            int width = icon.getIntrinsicWidth();
            int height = icon.getIntrinsicHeight();
            if (width > mMaxIconSize) {
                final float scale = (float) mMaxIconSize / width;
                width = mMaxIconSize;
                height *= scale;
            }
            if (height > mMaxIconSize) {
                final float scale = (float) mMaxIconSize / height;
                height = mMaxIconSize;
                width *= scale;
            }
            icon.setBounds(0, 0, width, height);
        }
        setCompoundDrawables(icon, null, null, null);

        updateTextButtonVisibility();
    }

    public boolean hasText() {
        return !TextUtils.isEmpty(getText());
    }

    public void setShortcut(boolean showShortcut, char shortcutKey) {
        // Action buttons don't show text for shortcut keys.
    }

    public void setTitle(CharSequence title) {
        mTitle = title;

        setContentDescription(mTitle);
        updateTextButtonVisibility();
    }

    @Override
    public boolean dispatchPopulateAccessibilityEventInternal(AccessibilityEvent event) {
        onPopulateAccessibilityEvent(event);
        return true;
    }

    @Override
    public void onPopulateAccessibilityEventInternal(AccessibilityEvent event) {
        super.onPopulateAccessibilityEventInternal(event);
        final CharSequence cdesc = getContentDescription();
        if (!TextUtils.isEmpty(cdesc)) {
            event.getText().add(cdesc);
        }
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        // Don't allow children to hover; we want this to be treated as a single component.
        return onHoverEvent(event);
    }

    public boolean showsIcon() {
        return true;
    }

    public boolean needsDividerBefore() {
        return hasText() && mItemData.getIcon() == null;
    }

    public boolean needsDividerAfter() {
        return hasText();
    }

    @Override
    public boolean onLongClick(View v) {
        if (hasText()) {
            // Don't show the cheat sheet for items that already show text.
            return false;
        }

        final int[] screenPos = new int[2];
        final Rect displayFrame = new Rect();
        getLocationOnScreen(screenPos);
        getWindowVisibleDisplayFrame(displayFrame);

        final Context context = getContext();
        final int width = getWidth();
        final int height = getHeight();
        final int midy = screenPos[1] + height / 2;
        int referenceX = screenPos[0] + width / 2;
        if (v.getLayoutDirection() == View.LAYOUT_DIRECTION_LTR) {
            final int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            referenceX = screenWidth - referenceX; // mirror
        }
        Toast cheatSheet = Toast.makeText(context, mItemData.getTitle(), Toast.LENGTH_SHORT);
        if (midy < displayFrame.height()) {
            // Show along the top; follow action buttons
            cheatSheet.setGravity(Gravity.TOP | Gravity.END, referenceX,
                    screenPos[1] + height - displayFrame.top);
        } else {
            // Show along the bottom center
            cheatSheet.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, height);
        }
        cheatSheet.show();
        return true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final boolean textVisible = hasText();
        if (textVisible && mSavedPaddingLeft >= 0) {
            super.setPadding(mSavedPaddingLeft, getPaddingTop(),
                    getPaddingRight(), getPaddingBottom());
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        final int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        final int oldMeasuredWidth = getMeasuredWidth();
        final int targetWidth = widthMode == MeasureSpec.AT_MOST ? Math.min(widthSize, mMinWidth)
                : mMinWidth;

        if (widthMode != MeasureSpec.EXACTLY && mMinWidth > 0 && oldMeasuredWidth < targetWidth) {
            // Remeasure at exactly the minimum width.
            super.onMeasure(MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
                    heightMeasureSpec);
        }

        if (!textVisible && mIcon != null) {
            // TextView won't center compound drawables in both dimensions without
            // a little coercion. Pad in to center the icon after we've measured.
            final int w = getMeasuredWidth();
            final int dw = mIcon.getBounds().width();
            super.setPadding((w - dw) / 2, getPaddingTop(), getPaddingRight(), getPaddingBottom());
        }
    }

    private class ActionMenuItemForwardingListener extends ForwardingListener {
        public ActionMenuItemForwardingListener() {
            super(ActionMenuItemView.this);
        }

        @Override
        public ShowableListMenu getPopup() {
            if (mPopupCallback != null) {
                return mPopupCallback.getPopup();
            }
            return null;
        }

        @Override
        protected boolean onForwardingStarted() {
            // Call the invoker, then check if the expected popup is showing.
            if (mItemInvoker != null && mItemInvoker.invokeItem(mItemData)) {
                final ShowableListMenu popup = getPopup();
                return popup != null && popup.isShowing();
            }
            return false;
        }
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        // This might get called with the state of ActionView since it shares the same ID with
        // ActionMenuItemView. Do not restore this state as ActionMenuItemView never saved it.
        super.onRestoreInstanceState(null);
    }

    public static abstract class PopupCallback {
        public abstract ShowableListMenu getPopup();
    }
}
