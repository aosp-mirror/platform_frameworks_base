/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.privacy.television;

import android.annotation.ColorRes;
import android.annotation.IntDef;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.android.systemui.R;
import com.android.systemui.privacy.PrivacyType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * View that shows indicator icons for privacy items.
 */
public class PrivacyItemsChip extends FrameLayout {
    private static final String TAG = "PrivacyItemsChip";
    private static final boolean DEBUG = false;

    /**
     * Configuration for a PrivacyItemsChip's appearance.
     */
    public static class ChipConfig {
        public final List<PrivacyType> privacyTypes;
        @ColorRes
        public final int colorRes;
        public final boolean collapseToDot;

        /**
         * @param privacyTypes Privacy types to show icons for, in order.
         * @param colorRes Color resource for the chip's foreground color.
         * @param collapseToDot Whether to collapse the chip in to a dot,
         *                      or just collapse it into a smaller chip with icons still visible.
         */
        public ChipConfig(@NonNull List<PrivacyType> privacyTypes, int colorRes,
                boolean collapseToDot) {
            this.privacyTypes = privacyTypes;
            this.colorRes = colorRes;
            this.collapseToDot = collapseToDot;
        }
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"STATE_"}, value = {
            STATE_NOT_SHOWN,
            STATE_EXPANDED,
            STATE_COLLAPSED,
    })
    public @interface State {
    }

    private static final int STATE_NOT_SHOWN = 0;
    private static final int STATE_EXPANDED = 1;
    private static final int STATE_COLLAPSED = 2;

    private final ChipConfig mConfig;
    private final int mIconSize;
    private final int mCollapsedIconSize;
    private final int mIconMarginHorizontal;
    private final PrivacyChipDrawable mChipBackgroundDrawable;
    private final List<ImageView> mIcons = new ArrayList<>();

    @State
    private int mState = STATE_NOT_SHOWN;

    public PrivacyItemsChip(@NonNull Context context, @NonNull ChipConfig config) {
        super(context);
        mConfig = config;
        setVisibility(View.GONE);

        Resources res = context.getResources();
        mIconSize = res.getDimensionPixelSize(R.dimen.privacy_chip_icon_size);
        mCollapsedIconSize = res.getDimensionPixelSize(R.dimen.privacy_chip_collapsed_icon_size);
        mIconMarginHorizontal =
                res.getDimensionPixelSize(R.dimen.privacy_chip_icon_margin_in_between);

        LayoutInflater.from(context).inflate(R.layout.tv_ongoing_privacy_chip, this);
        LinearLayout iconsContainer = findViewById(R.id.icons_container);

        mChipBackgroundDrawable = new PrivacyChipDrawable(
                context, config.colorRes, config.collapseToDot);
        mChipBackgroundDrawable.setCallback(new Drawable.Callback() {
            @Override
            public void invalidateDrawable(@NonNull Drawable who) {
                invalidate();
            }

            @Override
            public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
            }

            @Override
            public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
            }
        });

        setBackground(mChipBackgroundDrawable);

        for (PrivacyType type : config.privacyTypes) {
            ImageView typeIconView = new ImageView(context);
            Drawable icon = type.getIcon(context);
            icon.mutate().setTint(context.getColor(R.color.privacy_icon_tint));

            typeIconView.setImageDrawable(icon);
            typeIconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            mIcons.add(typeIconView);
            iconsContainer.addView(typeIconView, mIconSize, mIconSize);
            LinearLayout.LayoutParams lp =
                    (LinearLayout.LayoutParams) typeIconView.getLayoutParams();
            lp.leftMargin = mIconMarginHorizontal;
            lp.rightMargin = mIconMarginHorizontal;
            typeIconView.setVisibility(View.GONE);
        }
    }

    /**
     * Sets the active privacy types, and expands the chip if there are active items and the chip is
     * currently collapsed, or hides the chip if there are no active items.
     *
     * @param types The set of active privacy types. Only types configured in {@link ChipConfig}
     *              are shown.
     */
    public void expandForTypes(Set<PrivacyType> types) {
        if (DEBUG) Log.d(TAG, "expandForTypes, state=" + stateToString(mState));

        boolean hasActiveTypes = false;

        for (int i = 0; i < mConfig.privacyTypes.size(); i++) {
            PrivacyType type = mConfig.privacyTypes.get(i);
            ImageView icon = mIcons.get(i);
            boolean isTypeActive = types.contains(type);
            hasActiveTypes = hasActiveTypes || isTypeActive;

            icon.setVisibility(isTypeActive ? View.VISIBLE : View.GONE);

            // Set icon size to expanded size
            ViewGroup.LayoutParams lp = icon.getLayoutParams();
            lp.width = mIconSize;
            lp.height = mIconSize;
            icon.requestLayout();
        }

        if (hasActiveTypes) {
            if (DEBUG) Log.d(TAG, "Chip has active types, expanding");
            if (mState == STATE_NOT_SHOWN) {
                mChipBackgroundDrawable.expand(/* animate= */ false);
            } else if (mState == STATE_COLLAPSED) {
                mChipBackgroundDrawable.expand(/* animate= */ true);
            }
            setVisibility(View.VISIBLE);
            setState(STATE_EXPANDED);
        } else {
            if (DEBUG) Log.d(TAG, "Chip has no active types, hiding");
            setVisibility(View.GONE);
            setState(STATE_NOT_SHOWN);
        }
    }

    /**
     * Collapses this chip if currently expanded.
     */
    public void collapse() {
        if (DEBUG) Log.d(TAG, "collapse");

        if (mState != STATE_EXPANDED) {
            return;
        }
        setState(STATE_COLLAPSED);

        for (ImageView icon : mIcons) {
            if (mConfig.collapseToDot) {
                icon.setVisibility(View.GONE);
            } else {
                ViewGroup.LayoutParams lp = icon.getLayoutParams();
                lp.width = mCollapsedIconSize;
                lp.height = mCollapsedIconSize;
                icon.requestLayout();
            }
        }

        mChipBackgroundDrawable.collapse();
    }

    public boolean isExpanded() {
        return mState == STATE_EXPANDED;
    }

    private void setState(@State int state) {
        if (mState != state) {
            if (DEBUG) Log.d(TAG, "State changed: " + stateToString(state));
            mState = state;
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        mChipBackgroundDrawable.clipToForeground(canvas);
        super.dispatchDraw(canvas);
    }

    /**
     * Used in debug logs.
     */
    private static String stateToString(@State int state) {
        switch (state) {
            case STATE_NOT_SHOWN:
                return "NOT_SHOWN";
            case STATE_EXPANDED:
                return "EXPANDED";
            case STATE_COLLAPSED:
                return "COLLAPSED";
            default:
                return "INVALID";
        }
    }
}
