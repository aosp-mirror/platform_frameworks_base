/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification;

import android.app.Notification;
import android.content.Context;
import android.content.res.Resources;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.TextView;

import com.android.systemui.R;

/**
 * A class managing hybrid groups that include {@link HybridNotificationView} and the notification
 * group overflow.
 */
public class HybridGroupManager {

    private final Context mContext;
    private final NotificationDozeHelper mDozer;
    private final ViewGroup mParent;

    private final float mOverflowNumberSizeDark;
    private final int mOverflowNumberPaddingDark;
    private final float mOverflowNumberSize;
    private final int mOverflowNumberPadding;

    private int mOverflowNumberColor;
    private int mOverflowNumberColorDark;
    private float mDarkAmount = 0f;

    public HybridGroupManager(Context ctx, ViewGroup parent) {
        mContext = ctx;
        mParent = parent;
        mDozer = new NotificationDozeHelper();

        Resources res = mContext.getResources();
        mOverflowNumberSize = res.getDimensionPixelSize(
                R.dimen.group_overflow_number_size);
        mOverflowNumberSizeDark = res.getDimensionPixelSize(
                R.dimen.group_overflow_number_size_dark);
        mOverflowNumberPadding = res.getDimensionPixelSize(
                R.dimen.group_overflow_number_padding);
        mOverflowNumberPaddingDark = mOverflowNumberPadding + res.getDimensionPixelSize(
                R.dimen.group_overflow_number_extra_padding_dark);
    }

    private HybridNotificationView inflateHybridViewWithStyle(int style) {
        LayoutInflater inflater = new ContextThemeWrapper(mContext, style)
                .getSystemService(LayoutInflater.class);
        HybridNotificationView hybrid = (HybridNotificationView) inflater.inflate(
                R.layout.hybrid_notification, mParent, false);
        mParent.addView(hybrid);
        return hybrid;
    }

    private TextView inflateOverflowNumber() {
        LayoutInflater inflater = mContext.getSystemService(LayoutInflater.class);
        TextView numberView = (TextView) inflater.inflate(
                R.layout.hybrid_overflow_number, mParent, false);
        mParent.addView(numberView);
        updateOverFlowNumberColor(numberView);
        return numberView;
    }

    private void updateOverFlowNumberColor(TextView numberView) {
        numberView.setTextColor(NotificationUtils.interpolateColors(
                mOverflowNumberColor, mOverflowNumberColorDark, mDarkAmount));
    }

    public void setOverflowNumberColor(TextView numberView, int colorRegular, int colorDark) {
        mOverflowNumberColor = colorRegular;
        mOverflowNumberColorDark = colorDark;
        if (numberView != null) {
            updateOverFlowNumberColor(numberView);
        }
    }

    public HybridNotificationView bindFromNotification(HybridNotificationView reusableView,
            Notification notification) {
        return bindFromNotificationWithStyle(reusableView, notification,
                R.style.HybridNotification);
    }

    public HybridNotificationView bindAmbientFromNotification(HybridNotificationView reusableView,
            Notification notification) {
        return bindFromNotificationWithStyle(reusableView, notification,
                R.style.HybridNotification_Ambient);
    }

    private HybridNotificationView bindFromNotificationWithStyle(
            HybridNotificationView reusableView, Notification notification, int style) {
        if (reusableView == null) {
            reusableView = inflateHybridViewWithStyle(style);
        }
        CharSequence titleText = resolveTitle(notification);
        CharSequence contentText = resolveText(notification);
        reusableView.bind(titleText, contentText);
        return reusableView;
    }

    private CharSequence resolveText(Notification notification) {
        CharSequence contentText = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (contentText == null) {
            contentText = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        }
        return contentText;
    }

    private CharSequence resolveTitle(Notification notification) {
        CharSequence titleText = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
        if (titleText == null) {
            titleText = notification.extras.getCharSequence(Notification.EXTRA_TITLE_BIG);
        }
        return titleText;
    }

    public TextView bindOverflowNumber(TextView reusableView, int number) {
        if (reusableView == null) {
            reusableView = inflateOverflowNumber();
        }
        String text = mContext.getResources().getString(
                R.string.notification_group_overflow_indicator, number);
        if (!text.equals(reusableView.getText())) {
            reusableView.setText(text);
        }
        String contentDescription = String.format(mContext.getResources().getQuantityString(
                R.plurals.notification_group_overflow_description, number), number);

        reusableView.setContentDescription(contentDescription);
        return reusableView;
    }

    public void setOverflowNumberDark(TextView view, boolean dark, boolean fade, long delay) {
        mDozer.setIntensityDark((f)->{
            mDarkAmount = f;
            updateOverFlowNumberColor(view);
        }, dark, fade, delay);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                dark ? mOverflowNumberSizeDark : mOverflowNumberSize);
        int paddingEnd = dark ? mOverflowNumberPaddingDark : mOverflowNumberPadding;
        view.setPaddingRelative(view.getPaddingStart(), view.getPaddingTop(), paddingEnd,
                view.getPaddingBottom());
    }
}
