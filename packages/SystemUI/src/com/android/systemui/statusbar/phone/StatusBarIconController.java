/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.annotation.ColorInt;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.support.annotation.VisibleForTesting;
import android.text.TextUtils;
import android.util.ArraySet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.policy.DarkIconDispatcher;

public interface StatusBarIconController {

    public void addIconGroup(IconManager iconManager);
    public void removeIconGroup(IconManager iconManager);
    public void setExternalIcon(String slot);
    public void setIcon(String slot, int resourceId, CharSequence contentDescription);
    public void setIcon(String slot, StatusBarIcon icon);
    public void setIconVisibility(String slotTty, boolean b);
    public void removeIcon(String slot);

    public static final String ICON_BLACKLIST = "icon_blacklist";

    public static ArraySet<String> getIconBlacklist(String blackListStr) {
        ArraySet<String> ret = new ArraySet<>();
        if (blackListStr == null) {
            blackListStr = "rotate,headset";
        }
        String[] blacklist = blackListStr.split(",");
        for (String slot : blacklist) {
            if (!TextUtils.isEmpty(slot)) {
                ret.add(slot);
            }
        }
        return ret;
    }

    /**
     * Version of ViewGroup that observers state from the DarkIconDispatcher.
     */
    public static class DarkIconManager extends IconManager {
        private final DarkIconDispatcher mDarkIconDispatcher;
        private int mIconHPadding;

        public DarkIconManager(LinearLayout linearLayout) {
            super(linearLayout);
            mIconHPadding = mContext.getResources().getDimensionPixelSize(
                    R.dimen.status_bar_icon_padding);
            mDarkIconDispatcher = Dependency.get(DarkIconDispatcher.class);
        }

        @Override
        protected void onIconAdded(int index, String slot, boolean blocked,
                StatusBarIcon icon) {
            StatusBarIconView v = addIcon(index, slot, blocked, icon);
            mDarkIconDispatcher.addDarkReceiver(v);
        }

        @Override
        protected LayoutParams onCreateLayoutParams() {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, mIconSize);
            lp.setMargins(mIconHPadding, 0, mIconHPadding, 0);
            return lp;
        }

        @Override
        protected void destroy() {
            for (int i = 0; i < mGroup.getChildCount(); i++) {
                mDarkIconDispatcher.removeDarkReceiver((ImageView) mGroup.getChildAt(i));
            }
            mGroup.removeAllViews();
        }

        @Override
        protected void onRemoveIcon(int viewIndex) {
            mDarkIconDispatcher.removeDarkReceiver((ImageView) mGroup.getChildAt(viewIndex));
            super.onRemoveIcon(viewIndex);
        }

        @Override
        public void onSetIcon(int viewIndex, StatusBarIcon icon) {
            super.onSetIcon(viewIndex, icon);
            mDarkIconDispatcher.applyDark((ImageView) mGroup.getChildAt(viewIndex));
        }
    }

    public static class TintedIconManager extends IconManager {
        private int mColor;

        public TintedIconManager(ViewGroup group) {
            super(group);
        }

        @Override
        protected void onIconAdded(int index, String slot, boolean blocked, StatusBarIcon icon) {
            StatusBarIconView v = addIcon(index, slot, blocked, icon);
            v.setStaticDrawableColor(mColor);
        }

        public void setTint(int color) {
            mColor = color;
            for (int i = 0; i < mGroup.getChildCount(); i++) {
                View child = mGroup.getChildAt(i);
                if (child instanceof StatusBarIconView) {
                    StatusBarIconView icon = (StatusBarIconView) child;
                    icon.setStaticDrawableColor(mColor);
                }
            }
        }
    }

    /**
     * Turns info from StatusBarIconController into ImageViews in a ViewGroup.
     */
    public static class IconManager {
        protected final ViewGroup mGroup;
        protected final Context mContext;
        protected final int mIconSize;

        public IconManager(ViewGroup group) {
            mGroup = group;
            mContext = group.getContext();
            mIconSize = mContext.getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.status_bar_icon_size);
        }

        protected void onIconAdded(int index, String slot, boolean blocked,
                StatusBarIcon icon) {
            addIcon(index, slot, blocked, icon);
        }

        protected StatusBarIconView addIcon(int index, String slot, boolean blocked,
                StatusBarIcon icon) {
            StatusBarIconView view = onCreateStatusBarIconView(slot, blocked);
            view.set(icon);
            mGroup.addView(view, index, onCreateLayoutParams());
            return view;
        }

        @VisibleForTesting
        protected StatusBarIconView onCreateStatusBarIconView(String slot, boolean blocked) {
            return new StatusBarIconView(mContext, slot, null, blocked);
        }

        protected LinearLayout.LayoutParams onCreateLayoutParams() {
            return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, mIconSize);
        }

        protected void destroy() {
            mGroup.removeAllViews();
        }

        protected void onIconExternal(int viewIndex, int height) {
            ImageView imageView = (ImageView) mGroup.getChildAt(viewIndex);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imageView.setAdjustViewBounds(true);
            setHeightAndCenter(imageView, height);
        }

        protected void onDensityOrFontScaleChanged() {
            for (int i = 0; i < mGroup.getChildCount(); i++) {
                View child = mGroup.getChildAt(i);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, mIconSize);
                child.setLayoutParams(lp);
            }
        }

        private void setHeightAndCenter(ImageView imageView, int height) {
            ViewGroup.LayoutParams params = imageView.getLayoutParams();
            params.height = height;
            if (params instanceof LinearLayout.LayoutParams) {
                ((LinearLayout.LayoutParams) params).gravity = Gravity.CENTER_VERTICAL;
            }
            imageView.setLayoutParams(params);
        }

        protected void onRemoveIcon(int viewIndex) {
            mGroup.removeViewAt(viewIndex);
        }

        public void onSetIcon(int viewIndex, StatusBarIcon icon) {
            StatusBarIconView view = (StatusBarIconView) mGroup.getChildAt(viewIndex);
            view.set(icon);
        }
    }
}
