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

package com.android.printspooler.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import com.android.printspooler.R;

/**
 * This class represents the frame of page in the print preview list
 * that contains the page and a footer.
 */
public final class PreviewPageFrame extends LinearLayout {
    private final float mSelectedElevation;
    private final float mNotSelectedElevation;

    private final float mSelectedPageAlpha;
    private final float mNotSelectedAlpha;

    public PreviewPageFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
        mSelectedElevation = mContext.getResources().getDimension(
                R.dimen.selected_page_elevation);
        mNotSelectedElevation = mContext.getResources().getDimension(
                R.dimen.unselected_page_elevation);
        mSelectedPageAlpha = mContext.getResources().getFraction(
                R.fraction.page_selected_alpha, 1, 1);
        mNotSelectedAlpha = mContext.getResources().getFraction(
                R.fraction.page_unselected_alpha, 1, 1);
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return CompoundButton.class.getName();
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setChecked(isSelected());
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setSelected(false);
        info.setCheckable(true);
        info.setChecked(isSelected());
    }

    public void setSelected(boolean selected, boolean animate) {
        if (isSelected() == selected) {
            return;
        }
        setSelected(selected);
        if (selected) {
            if (animate) {
                animate().translationZ(mSelectedElevation)
                        .alpha(mSelectedPageAlpha);
            } else {
                setTranslationZ(mSelectedElevation);
                setAlpha(mSelectedPageAlpha);
            }
        } else {
            if (animate) {
                animate().translationZ(mNotSelectedElevation)
                        .alpha(mNotSelectedAlpha);
            } else {
                setTranslationZ(mNotSelectedElevation);
                setAlpha(mNotSelectedAlpha);
            }
        }
    }
}
