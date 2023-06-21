/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.accessibility.accessibilitymenu.view;

import android.content.Context;
import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.TouchDelegate;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.TextView;

import com.android.systemui.accessibility.accessibilitymenu.AccessibilityMenuService;
import com.android.systemui.accessibility.accessibilitymenu.R;
import com.android.systemui.accessibility.accessibilitymenu.activity.A11yMenuSettingsActivity.A11yMenuPreferenceFragment;
import com.android.systemui.accessibility.accessibilitymenu.model.A11yMenuShortcut;
import com.android.systemui.accessibility.accessibilitymenu.utils.ShortcutDrawableUtils;

import java.util.List;

/** GridView Adapter for a11y menu overlay. */
public class A11yMenuAdapter extends BaseAdapter {

    // The large scale of shortcut icon and label.
    private static final float LARGE_BUTTON_SCALE = 1.5f;
    private final int mLargeTextSize;

    private final AccessibilityMenuService mService;
    private final LayoutInflater mInflater;
    private final List<A11yMenuShortcut> mShortcutDataList;
    private final ShortcutDrawableUtils mShortcutDrawableUtils;

    public A11yMenuAdapter(
            AccessibilityMenuService service,
            Context displayContext, List<A11yMenuShortcut> shortcutDataList) {
        this.mService = service;
        this.mShortcutDataList = shortcutDataList;
        mInflater = LayoutInflater.from(displayContext);

        mShortcutDrawableUtils = new ShortcutDrawableUtils(service);

        mLargeTextSize =
                service.getResources().getDimensionPixelOffset(R.dimen.large_label_text_size);
    }

    @Override
    public int getCount() {
        return mShortcutDataList.size();
    }

    @Override
    public Object getItem(int position) {
        return mShortcutDataList.get(position);
    }

    @Override
    public long getItemId(int position) {
        return mShortcutDataList.get(position).getId();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = mInflater.inflate(R.layout.grid_item, parent, false);
        }

        A11yMenuShortcut shortcutItem = (A11yMenuShortcut) getItem(position);
        // Sets shortcut icon and label resource.
        configureShortcutView(convertView, shortcutItem);

        expandIconTouchArea(convertView);
        setActionForMenuShortcut(convertView);
        return convertView;
    }

    /**
     * Expand shortcut icon touch area to the border of grid item.
     * The height is from the top of icon to the bottom of label.
     * The width is from the left border of grid item to the right border of grid item.
     */
    private void expandIconTouchArea(View convertView) {
        ImageButton shortcutIconButton = convertView.findViewById(R.id.shortcutIconBtn);
        TextView shortcutLabel = convertView.findViewById(R.id.shortcutLabel);

        shortcutIconButton.post(
                () -> {
                    Rect iconHitRect = new Rect();
                    shortcutIconButton.getHitRect(iconHitRect);
                    Rect labelHitRect = new Rect();
                    shortcutLabel.getHitRect(labelHitRect);

                    final int widthAdjustment = iconHitRect.left;
                    iconHitRect.left = 0;
                    iconHitRect.right += widthAdjustment;
                    iconHitRect.top = 0;
                    iconHitRect.bottom = labelHitRect.bottom;
                    ((View) shortcutIconButton.getParent())
                            .setTouchDelegate(new TouchDelegate(iconHitRect, shortcutIconButton));
                });
    }

    private void setActionForMenuShortcut(View convertView) {
        ImageButton shortcutIconButton = convertView.findViewById(R.id.shortcutIconBtn);

        shortcutIconButton.setOnClickListener(
                (View v) -> {
                    // Handles shortcut click event by AccessibilityMenuService.
                    mService.handleClick(v);
                });
    }

    private void configureShortcutView(View convertView, A11yMenuShortcut shortcutItem) {
        ImageButton shortcutIconButton = convertView.findViewById(R.id.shortcutIconBtn);
        TextView shortcutLabel = convertView.findViewById(R.id.shortcutLabel);

        if (A11yMenuPreferenceFragment.isLargeButtonsEnabled(mService)) {
            ViewGroup.LayoutParams params = shortcutIconButton.getLayoutParams();
            params.width = (int) (params.width * LARGE_BUTTON_SCALE);
            params.height = (int) (params.height * LARGE_BUTTON_SCALE);
            shortcutLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, mLargeTextSize);
        }

        if (shortcutItem.getId() == A11yMenuShortcut.ShortcutId.UNSPECIFIED_ID_VALUE.ordinal()) {
            // Sets empty shortcut icon and label when the shortcut is ADD_ITEM.
            shortcutIconButton.setImageResource(android.R.color.transparent);
            shortcutIconButton.setBackground(null);
        } else {
            // Sets shortcut ID as tagId, to handle menu item click in AccessibilityMenuService.
            shortcutIconButton.setTag(shortcutItem.getId());
            shortcutIconButton.setContentDescription(
                    mService.getString(shortcutItem.imgContentDescription));
            shortcutLabel.setText(shortcutItem.labelText);
            shortcutIconButton.setImageResource(shortcutItem.imageSrc);

            shortcutIconButton.setBackground(
                    mShortcutDrawableUtils.createAdaptiveIconDrawable(shortcutItem.imageColor));

            shortcutIconButton.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(
                        View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setUniqueId(host.getTag().toString());
                }
            });
        }
    }
}
