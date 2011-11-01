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
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

/**
 * @hide
 */
public class ActionMenuItemView extends LinearLayout
        implements MenuView.ItemView, View.OnClickListener, View.OnLongClickListener,
        ActionMenuView.ActionMenuChildView {
    private static final String TAG = "ActionMenuItemView";

    private MenuItemImpl mItemData;
    private CharSequence mTitle;
    private MenuBuilder.ItemInvoker mItemInvoker;

    private ImageButton mImageButton;
    private Button mTextButton;
    private boolean mAllowTextWithIcon;
    private boolean mExpandedFormat;
    private int mMinWidth;

    public ActionMenuItemView(Context context) {
        this(context, null);
    }

    public ActionMenuItemView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ActionMenuItemView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        final Resources res = context.getResources();
        mAllowTextWithIcon = res.getBoolean(
                com.android.internal.R.bool.config_allowActionMenuItemTextWithIcon);
        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.ActionMenuItemView, 0, 0);
        mMinWidth = a.getDimensionPixelSize(
                com.android.internal.R.styleable.ActionMenuItemView_minWidth, 0);
        a.recycle();
    }

    @Override
    public void onFinishInflate() {
        mImageButton = (ImageButton) findViewById(com.android.internal.R.id.imageButton);
        mTextButton = (Button) findViewById(com.android.internal.R.id.textButton);
        mImageButton.setOnClickListener(this);
        mTextButton.setOnClickListener(this);
        mImageButton.setOnLongClickListener(this);
        setOnClickListener(this);
        setOnLongClickListener(this);
    }

    public MenuItemImpl getItemData() {
        return mItemData;
    }

    public void initialize(MenuItemImpl itemData, int menuType) {
        mItemData = itemData;

        setIcon(itemData.getIcon());
        setTitle(itemData.getTitleForItemView(this)); // Title only takes effect if there is no icon
        setId(itemData.getItemId());

        setVisibility(itemData.isVisible() ? View.VISIBLE : View.GONE);
        setEnabled(itemData.isEnabled());
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        mImageButton.setEnabled(enabled);
        mTextButton.setEnabled(enabled);
    }

    public void onClick(View v) {
        if (mItemInvoker != null) {
            mItemInvoker.invokeItem(mItemData);
        }
    }

    public void setItemInvoker(MenuBuilder.ItemInvoker invoker) {
        mItemInvoker = invoker;
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
        boolean visible = !TextUtils.isEmpty(mTextButton.getText());
        visible &= mImageButton.getDrawable() == null ||
                (mItemData.showsTextAsAction() && (mAllowTextWithIcon || mExpandedFormat));

        mTextButton.setVisibility(visible ? VISIBLE : GONE);
    }

    public void setIcon(Drawable icon) {
        mImageButton.setImageDrawable(icon);
        if (icon != null) {
            mImageButton.setVisibility(VISIBLE);
        } else {
            mImageButton.setVisibility(GONE);
        }

        updateTextButtonVisibility();
    }

    public boolean hasText() {
        return mTextButton.getVisibility() != GONE;
    }

    public void setShortcut(boolean showShortcut, char shortcutKey) {
        // Action buttons don't show text for shortcut keys.
    }

    public void setTitle(CharSequence title) {
        mTitle = title;

        mTextButton.setText(mTitle);

        setContentDescription(mTitle);
        updateTextButtonVisibility();
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        onPopulateAccessibilityEvent(event);
        return true;
    }

    @Override
    public void onPopulateAccessibilityEvent(AccessibilityEvent event) {
        super.onPopulateAccessibilityEvent(event);
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
        final int screenWidth = context.getResources().getDisplayMetrics().widthPixels;

        Toast cheatSheet = Toast.makeText(context, mItemData.getTitle(), Toast.LENGTH_SHORT);
        if (midy < displayFrame.height()) {
            // Show along the top; follow action buttons
            cheatSheet.setGravity(Gravity.TOP | Gravity.RIGHT,
                    screenWidth - screenPos[0] - width / 2, height);
        } else {
            // Show along the bottom center
            cheatSheet.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, height);
        }
        cheatSheet.show();
        return true;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        final int specSize = MeasureSpec.getSize(widthMeasureSpec);
        final int oldMeasuredWidth = getMeasuredWidth();
        final int targetWidth = widthMode == MeasureSpec.AT_MOST ? Math.min(specSize, mMinWidth)
                : mMinWidth;

        if (widthMode != MeasureSpec.EXACTLY && mMinWidth > 0 && oldMeasuredWidth < targetWidth) {
            // Remeasure at exactly the minimum width.
            super.onMeasure(MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
                    heightMeasureSpec);
        }
    }
}
