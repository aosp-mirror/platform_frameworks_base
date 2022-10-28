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

package com.android.systemui.statusbar.notification.row;

import static com.android.systemui.util.PluralMessageFormaterKt.icuMessageFormat;

import android.annotation.Nullable;
import android.app.Notification;
import android.content.Context;
import android.content.res.Resources;
import android.service.notification.StatusBarNotification;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.android.internal.widget.ConversationLayout;
import com.android.systemui.R;

/**
 * A class managing hybrid groups that include {@link HybridNotificationView} and the notification
 * group overflow.
 */
public class HybridGroupManager {

    private final Context mContext;

    private float mOverflowNumberSize;
    private int mOverflowNumberPadding;

    private int mOverflowNumberColor;

    public HybridGroupManager(Context ctx) {
        mContext = ctx;
        initDimens();
    }

    public void initDimens() {
        Resources res = mContext.getResources();
        mOverflowNumberSize = res.getDimensionPixelSize(R.dimen.group_overflow_number_size);
        mOverflowNumberPadding = res.getDimensionPixelSize(R.dimen.group_overflow_number_padding);
    }

    private HybridNotificationView inflateHybridView(View contentView, ViewGroup parent) {
        LayoutInflater inflater = LayoutInflater.from(mContext);
        int layout = contentView instanceof ConversationLayout
                ? R.layout.hybrid_conversation_notification
                : R.layout.hybrid_notification;
        HybridNotificationView hybrid = (HybridNotificationView)
                inflater.inflate(layout, parent, false);
        parent.addView(hybrid);
        return hybrid;
    }

    private TextView inflateOverflowNumber(ViewGroup parent) {
        LayoutInflater inflater = mContext.getSystemService(LayoutInflater.class);
        TextView numberView = (TextView) inflater.inflate(
                R.layout.hybrid_overflow_number, parent, false);
        parent.addView(numberView);
        updateOverFlowNumberColor(numberView);
        return numberView;
    }

    private void updateOverFlowNumberColor(TextView numberView) {
        numberView.setTextColor(mOverflowNumberColor);
    }

    public void setOverflowNumberColor(TextView numberView, int colorRegular) {
        mOverflowNumberColor = colorRegular;
        if (numberView != null) {
            updateOverFlowNumberColor(numberView);
        }
    }

    public HybridNotificationView bindFromNotification(HybridNotificationView reusableView,
            View contentView, StatusBarNotification notification,
            ViewGroup parent) {
        if (reusableView == null) {
            reusableView = inflateHybridView(contentView, parent);
        }
        CharSequence titleText = resolveTitle(notification.getNotification());
        CharSequence contentText = resolveText(notification.getNotification());
        reusableView.bind(titleText, contentText, contentView);
        return reusableView;
    }

    @Nullable
    public static CharSequence resolveText(Notification notification) {
        CharSequence contentText = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (contentText == null) {
            contentText = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        }
        return contentText;
    }

    @Nullable
    public static CharSequence resolveTitle(Notification notification) {
        CharSequence titleText = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
        if (titleText == null) {
            titleText = notification.extras.getCharSequence(Notification.EXTRA_TITLE_BIG);
        }
        return titleText;
    }

    public TextView bindOverflowNumber(TextView reusableView, int number,
            ViewGroup parent) {
        if (reusableView == null) {
            reusableView = inflateOverflowNumber(parent);
        }
        String text = mContext.getResources().getString(
                R.string.notification_group_overflow_indicator, number);
        if (!text.equals(reusableView.getText())) {
            reusableView.setText(text);
        }
        String contentDescription = icuMessageFormat(mContext.getResources(),
                R.string.notification_group_overflow_description, number);

        reusableView.setContentDescription(contentDescription);
        reusableView.setTextSize(TypedValue.COMPLEX_UNIT_PX, mOverflowNumberSize);
        reusableView.setPaddingRelative(reusableView.getPaddingStart(),
                reusableView.getPaddingTop(), mOverflowNumberPadding,
                reusableView.getPaddingBottom());
        updateOverFlowNumberColor(reusableView);
        return reusableView;
    }
}
