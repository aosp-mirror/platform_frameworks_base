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
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.statusbar.ExpandableNotificationRow;

import java.util.List;

/**
 * A class managing {@link HybridNotificationView} views
 */
public class HybridNotificationViewManager {

    private final Context mContext;
    private ViewGroup mParent;
    private String mConcadenationString;

    public HybridNotificationViewManager(Context ctx, ViewGroup parent) {
        mContext = ctx;
        mParent = parent;
        mConcadenationString = mContext.getString(R.string.group_summary_concadenation);
    }

    private HybridNotificationView inflateHybridView() {
        LayoutInflater inflater = mContext.getSystemService(LayoutInflater.class);
        HybridNotificationView hybrid = (HybridNotificationView) inflater.inflate(
                R.layout.hybrid_notification, mParent, false);
        mParent.addView(hybrid);
        return hybrid;
    }

    public HybridNotificationView bindFromNotification(HybridNotificationView reusableView,
            Notification notification) {
        CharSequence titleText = resolveTitle(notification);
        if (titleText == null) {
            if (reusableView != null) {
                mParent.removeView(reusableView);
            }
            return null;
        }
        if (reusableView == null) {
            reusableView = inflateHybridView();
        }
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

    public HybridNotificationView bindFromNotificationGroup(
            HybridNotificationView reusableView,
            List<ExpandableNotificationRow> group, int startIndex) {
        if (reusableView == null) {
            reusableView = inflateHybridView();
        }
        CharSequence summary = null;
        int childCount = group.size();
        for (int i = startIndex; i < childCount; i++) {
            ExpandableNotificationRow child = group.get(i);
            CharSequence titleText = resolveTitle(
                    child.getStatusBarNotification().getNotification());
            if (titleText == null) {
                continue;
            }
            if (TextUtils.isEmpty(summary)) {
                summary = titleText;
            } else if (reusableView.isLayoutRtl()) {
                summary = titleText + mConcadenationString + summary;
            } else {
                summary = summary + mConcadenationString + titleText;
            }
        }
        reusableView.bind(summary);
        return reusableView;
    }
}
